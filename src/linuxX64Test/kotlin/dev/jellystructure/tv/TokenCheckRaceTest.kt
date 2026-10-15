package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.JellyfinClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Phase 300 (FR-300-10) — a token check in flight cannot undo a forget. A fake Jellyfin over the loopback whose
 * `/Users/{id}` can hold a request on a gate the test opens, so the interleaving is forced rather than hoped for:
 * the answer is decided when the request ARRIVES (live or revoked), and delivered when the gate opens.
 *
 * A check is observed through [tvToken]: the device's own token when the verdict is VALID, the server token otherwise.
 */
class TokenCheckRaceTest {

    private class Fake {
        val lock = Mutex()
        val live = mutableSetOf<String>()
        val arrivals = mutableListOf<String>()                       // one entry per /Users/{id} request, by token
        val gates = ArrayDeque<CompletableDeferred<Unit>>()          // the next request to arrive takes the first gate
        suspend fun reached(token: String) = lock.withLock { arrivals.count { it == token } }
        suspend fun hold(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { g -> lock.withLock { gates.addLast(g) } }
        suspend fun revoke(token: String) = lock.withLock { live.remove(token); Unit }
    }

    private suspend fun waitFor(what: String, timeoutMs: Long = 10_000, check: suspend () -> Boolean) {
        val ok = runCatching { withTimeout(timeoutMs) { while (!check()) delay(10) } }.isSuccess
        if (!ok) fail("timed out waiting for: $what")
    }

    private fun tokenOf(authorization: String?): String? =
        Regex("Token=\"([^\"]+)\"").find(authorization.orEmpty())?.groupValues?.get(1)

    private fun device(token: String) = DeviceData(
        deviceId = "race-$token", deviceToken = "dt-$token", jellyfinUserId = "u-race", jellyfinUsername = "race",
        jellyfinUserToken = token, isAdmin = false, kind = "phone", // a phone never borrows: no heal in the way
    )

    private suspend fun <T> withFake(block: suspend (fake: Fake, base: String, run: String) -> T): T {
        DeviceIdentityRegistry.clear()
        val fake = Fake()
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/Users/{id}") {
                    val token = tokenOf(call.request.headers["Authorization"])
                    val (accepted, gate) = fake.lock.withLock {
                        fake.arrivals += token.orEmpty()
                        (token != null && token in fake.live) to fake.gates.removeFirstOrNull()
                    }
                    gate?.await()
                    if (accepted) call.respondText("{}", ContentType.Application.Json) else call.respond(HttpStatusCode.Unauthorized)
                }
            }
        }.start(wait = false)
        try {
            val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            return block(fake, base, getpid().toString())
        } finally {
            server.stop(100, 500)
            DeviceIdentityRegistry.clear()
        }
    }

    @Test
    fun `a 200 that left before a forget is returned but not cached`() = runBlocking {
        withFake { fake, base, run ->
            val token = "jf-race-a-$run"          // unique per run: the token caches are process-wide
            fake.lock.withLock { fake.live += token }
            val jellyfin = JellyfinClient()
            val gate = fake.hold()

            val inFlight = async { jellyfin.tvToken(base, device(token), "SERVER") }
            waitFor("the fake holds the check's request") { fake.reached(token) == 1 }
            fake.revoke(token); forgetTokenValidity(token)
            gate.complete(Unit)

            assertEquals(token, inFlight.await(), "the in-flight check is told what Jellyfin said for its request")
            assertEquals("SERVER", jellyfin.tvToken(base, device(token), "SERVER"), "and the next check reaches Jellyfin and is rejected")
            assertEquals(3, fake.reached(token), "in flight, then the rejection and FR-194-5's single re-probe")
        }
    }

    @Test
    fun `an older 200 does not clear a newer rejection`() = runBlocking {
        withFake { fake, base, run ->
            val token = "jf-race-b-$run"
            fake.lock.withLock { fake.live += token }
            val jellyfin = JellyfinClient()
            val gate = fake.hold()

            val older = async { jellyfin.tvToken(base, device(token), "SERVER") }
            waitFor("the fake holds the older check's request") { fake.reached(token) == 1 }
            fake.revoke(token)                                   // no forget: the 401 is the only thing that lands
            assertEquals("SERVER", jellyfin.tvToken(base, device(token), "SERVER"), "the newer check is rejected")
            gate.complete(Unit)

            assertEquals(token, older.await(), "the older check still returns its own verdict")
            val before = fake.reached(token)
            assertEquals("SERVER", jellyfin.tvToken(base, device(token), "SERVER"), "the rejection stands")
            assertEquals(before, fake.reached(token), "…served from the negative cache, not re-probed")
        }
    }

    @Test
    fun `with no forget a valid verdict is cached as before`() = runBlocking {
        withFake { fake, base, run ->
            val token = "jf-race-c-$run"
            fake.lock.withLock { fake.live += token }
            val jellyfin = JellyfinClient()
            val gate = fake.hold()

            val inFlight = async { jellyfin.tvToken(base, device(token), "SERVER") }
            waitFor("the fake holds the check's request") { fake.reached(token) == 1 }
            gate.complete(Unit)
            assertEquals(token, inFlight.await())

            assertEquals(token, jellyfin.tvToken(base, device(token), "SERVER"))
            assertEquals(1, fake.reached(token), "the second check never reached Jellyfin")
        }
    }
}
