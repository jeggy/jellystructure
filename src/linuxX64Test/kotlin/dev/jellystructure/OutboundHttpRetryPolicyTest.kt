package dev.jellystructure

import io.ktor.client.plugins.HttpRequestRetryEvent
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.AtomicInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 194's 2026-10-13 amendment (FR-194-7, DEB-914) — the shared outbound client retries a *thrown*
 * connection failure on an idempotent method, and nothing else.
 *
 * The four cases are a table, and the third is the one that must not regress with the rest: FR-194-6 was
 * built for it. Two independent witnesses per case where the request reaches a server — the stand-in's own
 * counter, and `HttpRequestRetryEvent`, which the plugin raises once per retry *decision*. The event is the
 * only witness available where the call never reaches anyone (a refused connection leaves nobody to count),
 * and it doubles as proof that `isConnectionLayerFailure()` recognised the cause: the retry cannot fire
 * unless it did.
 */
class OutboundHttpRetryPolicyTest {
    private val answered500 = AtomicInt(0)
    private val answered503 = AtomicInt(0)

    private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            get("/boom") {
                answered500.incrementAndGet()
                call.respondText("the server is unwell", status = HttpStatusCode.InternalServerError)
            }
            post("/busy") {
                answered503.incrementAndGet()
                call.respondText(
                    """{"error":"a measurement is already running","retry_after_s":20}""",
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
            }
        }
    }.start(wait = false)
    private val port = runBlocking { server.engine.resolvedConnectors().first().port }

    /** A port nothing listens on — bound only long enough to learn a number the kernel had free, then
     *  released, so a call to it is refused at the connection layer rather than answered. */
    private val refusedPort = runBlocking {
        val probe = embeddedServer(CIO, port = 0, host = "127.0.0.1") {}.start(wait = false)
        val free = probe.engine.resolvedConnectors().first().port
        probe.stop(0, 0)
        free
    }

    @AfterTest
    fun tearDown() {
        server.stop(0, 0)
    }

    /** [block]'s outcome, and how many times the plugin decided to retry while it ran. */
    private fun call(method: HttpMethod, url: String): Pair<Result<HttpResponse>, Int> {
        val retries = AtomicInt(0)
        val handle = OutboundHttp.client.monitor.subscribe(HttpRequestRetryEvent) { retries.incrementAndGet() }
        return try {
            runBlocking { runCatching { OutboundHttp.client.request(url) { this.method = method } } } to retries.value
        } finally {
            handle.dispose()
        }
    }

    // ── a request that reached the server and got an answer is never retried ──

    @Test
    fun `a GET answered 500 is sent once`() {
        val (result, retries) = call(HttpMethod.Get, "http://127.0.0.1:$port/boom")
        assertEquals(500, result.getOrThrow().status.value, "the 500 is handed to the caller as-is")
        assertEquals(1, answered500.value, "one GET, not two — a 5xx answer is the server's answer")
        assertEquals(0, retries, "no retry decision was taken")
    }

    @Test
    fun `a POST answered 503 is sent once`() {
        // The case DEB-914 was filed from: a replayed POST is a write done twice, and a 503 carries its own
        // "come back in n seconds" that a 200 ms retry would be ignoring.
        val (result, retries) = call(HttpMethod.Post, "http://127.0.0.1:$port/busy")
        assertEquals(503, result.getOrThrow().status.value)
        assertEquals(1, answered503.value, "one POST, not two")
        assertEquals(0, retries, "no retry decision was taken")
    }

    // ── a call that threw at the connection layer is retried once on an idempotent method ──

    @Test
    fun `a GET that cannot connect is tried twice`() {
        val (result, retries) = call(HttpMethod.Get, "http://127.0.0.1:$refusedPort/anything")
        val cause = result.exceptionOrNull()
        assertNotNull(cause, "a refused connection is a failure and stays one — the retry does not rescue it")
        assertEquals(
            1, retries,
            "FR-194-6's whole reason for existing: a stale pooled socket is retried once. Cause was: $cause",
        )
    }

    @Test
    fun `a POST that cannot connect is tried once`() {
        val (result, retries) = call(HttpMethod.Post, "http://127.0.0.1:$refusedPort/anything")
        assertTrue(result.isFailure, "a refused connection is a failure")
        assertEquals(0, retries, "a POST is not replayed even when it demonstrably never arrived")
    }
}
