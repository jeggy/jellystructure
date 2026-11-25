package dev.jellystructure.auth

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-5541 (FR-R219-2, R231) — the finished-history read is paged in parallel, at most four pages at a time, and comes
 * back whole and in `StartIndex` order; one failed page fails the read. A fake Jellyfin on the loopback counts the
 * pages in flight (there is no `MockEngine` in `linuxX64Test`).
 */
class RecentlyPlayedAllReadTest {
    private class Fake(val total: Int, val failAt: Int? = null, val shortAt: Int? = null) {
        val lock = Mutex()
        val starts = mutableListOf<Int>()
        var inFlight = 0
        var maxInFlight = 0
    }

    private fun run(fake: Fake, block: suspend (JellyfinRead<List<JellyfinPlayItem>>) -> Unit) = runBlocking {
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/Items") {
                    val start = call.request.queryParameters["StartIndex"]!!.toInt()
                    val limit = call.request.queryParameters["Limit"]!!.toInt()
                    fake.lock.withLock { fake.starts += start; fake.inFlight++; fake.maxInFlight = maxOf(fake.maxInFlight, fake.inFlight) }
                    // Later pages answer faster than earlier ones: the join must still be in StartIndex order.
                    delay(if (start == 0) 20L else 150L - start / 20)
                    fake.lock.withLock { fake.inFlight-- }
                    if (start == fake.failAt) return@get call.respondText("boom", status = HttpStatusCode.InternalServerError)
                    val end = minOf(start + limit, fake.total) - (if (start == fake.shortAt) 1 else 0)
                    val items = (start until end).joinToString(",") { """{"Id":"i$it","Type":"Episode","Name":"n$it"}""" }
                    call.respondText("""{"Items":[$items],"TotalRecordCount":${fake.total}}""", ContentType.Application.Json)
                }
            }
        }.start(wait = false)
        try {
            val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            block(JellyfinClient().getRecentlyPlayedAllRead(base, "tok", "u1"))
        } finally {
            server.stop(100, 500)
        }
    }

    @Test
    fun `1944 items over ten pages come back complete and in order with the pages after the first read concurrently`() {
        val fake = Fake(1944)
        run(fake) { read ->
            assertFalse(read.failed)
            assertEquals((0 until 1944).map { "i$it" }, read.value.map { it.id })
            assertEquals(10, fake.starts.size)
            assertEquals(0, fake.starts.first(), "page 1 is read first, alone")
            assertEquals((0 until 1944 step 200).toList(), fake.starts.sorted())
            assertTrue(fake.maxInFlight in 2..4, "pages after the first overlap, never more than 4: ${fake.maxInFlight}")
        }
    }

    @Test
    fun `a history that fits one page is one request`() {
        val fake = Fake(37)
        run(fake) { read ->
            assertFalse(read.failed)
            assertEquals(37, read.value.size)
            assertEquals(listOf(0), fake.starts)
        }
    }

    @Test
    fun `one failing page fails the read`() {
        val fake = Fake(1944, failAt = 600)
        run(fake) { read ->
            assertTrue(read.failed)
            assertEquals(500, read.status)
            assertEquals((0 until 600).map { "i$it" }, read.value.map { it.id }, "the pages before the failed one, as the sequential read gave")
        }
    }

    @Test
    fun `a short page that is not the last fails the read rather than leaving a hole`() {
        val fake = Fake(1944, shortAt = 400)
        run(fake) { read -> assertTrue(read.failed) }
    }
}
