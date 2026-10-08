package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.ClientCapabilities
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 310 / 312 (FR-310-8, FR-312-6, 312 dev review item 7) — a play's stops, end to end in process: the real
 * [PlaybackService] and its writer on a temporary database, against a fake Jellyfin over the loopback that records
 * every `/Sessions/Playing/Stopped` and user-data write.
 *
 * - A stop lands once, at its place, followed by one user-data write (unwatched at that place).
 * - An abandoned start (the viewer's stop arrived while it was negotiating) re-sends the viewer's own stop, never one
 *   at its own start position (0 for a play from the beginning: the stray stop at 0 that wiped 24 places).
 * - A superseded start sends Jellyfin no stop at all, only releases the old encode.
 * - A stray stop at 0 landing after ours is undone by the read-back.
 */
class PlaybackStopIntegrationTest {
    private class FakeJellyfin {
        val lock = Mutex()
        val stops = mutableListOf<Pair<String, Long>>()        // item → PositionTicks / 10 000
        val userDataWrites = mutableListOf<Pair<String, String>>()
        val releases = mutableListOf<String>()
        var psidSeq = 0
        var position = mutableMapOf<String, Long>()             // item → ticks Jellyfin holds
        var played = mutableMapOf<String, Boolean>()
        /** When set: this many ms after a user-data write for the item, a stray stop at 0 resets the place. */
        var strayStopAfterMs: Long? = null
    }

    private suspend fun waitFor(what: String, timeoutMs: Long = 15_000, check: suspend () -> Boolean) {
        if (runCatching { withTimeout(timeoutMs) { while (!check()) delay(25) } }.isFailure) fail("timed out waiting for: $what")
    }

    private val ticksRe = Regex("\"PositionTicks\":(\\d+)")
    private val posRe = Regex("\"PlaybackPositionTicks\":(\\d+)")

    private class Rig(val service: PlaybackService, val fake: FakeJellyfin, val scope: CoroutineScope, val stopServer: () -> Unit)

    private fun rig(name: String, block: suspend Rig.() -> Unit) = runBlocking {
        val run = "${getpid()}-$name"
        val fake = FakeJellyfin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/Users/{id}") { call.respondText("{}", ContentType.Application.Json) }
                get("/Items/{id}") {
                    val id = call.parameters["id"]!!
                    val (pos, pl) = fake.lock.withLock { (fake.position[id] ?: 0L) to (fake.played[id] ?: false) }
                    call.respondText("""{"Id":"$id","Name":"Film $id","RunTimeTicks":${2 * 3_600_000L * 10_000},"UserData":{"PlaybackPositionTicks":$pos,"Played":$pl}}""", ContentType.Application.Json)
                }
                post("/Items/{id}/PlaybackInfo") {
                    val id = call.parameters["id"]!!
                    val psid = fake.lock.withLock { "psid-${++fake.psidSeq}" }
                    call.respondText("""{"MediaSources":[{"Id":"$id","Container":"mkv","SupportsDirectPlay":true}],"PlaySessionId":"$psid"}""", ContentType.Application.Json)
                }
                post("/Sessions/Playing") { call.receiveText(); call.respond(HttpStatusCode.NoContent) }
                post("/Sessions/Playing/Progress") { call.receiveText(); call.respond(HttpStatusCode.NoContent) }
                post("/Sessions/Playing/Stopped") {
                    val body = call.receiveText()
                    val id = Regex("\"ItemId\":\"([^\"]+)\"").find(body)!!.groupValues[1]
                    val ticks = ticksRe.find(body)!!.groupValues[1].toLong()
                    fake.lock.withLock { fake.stops += id to ticks / 10_000; fake.position[id] = ticks }
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/UserItems/{id}/UserData") {
                    val id = call.parameters["id"]!!
                    val body = call.receiveText()
                    val stray = fake.lock.withLock {
                        fake.userDataWrites += id to body
                        posRe.find(body)?.groupValues?.get(1)?.toLong()?.let { fake.position[id] = it }
                        if ("\"Played\":true" in body) fake.played[id] = true
                        if ("\"Played\":false" in body) fake.played[id] = false
                        fake.strayStopAfterMs.also { fake.strayStopAfterMs = null }
                    }
                    // The stray stop at 0 that 312 is about: it lands after ours and resets the place.
                    if (stray != null) scope.launch { delay(stray); fake.lock.withLock { fake.stops += id to 0L; fake.position[id] = 0L } }
                    call.respondText("{}", ContentType.Application.Json)
                }
                delete("/Videos/ActiveEncodings") {
                    fake.lock.withLock { fake.releases += call.request.queryParameters["playSessionId"].orEmpty() }
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/Sessions/Capabilities/Full") { call.respond(HttpStatusCode.NoContent) }
            }
        }.start(wait = false)
        val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
        val configStore = ConfigStore("/tmp/jellystructure-test-312-$run.toml")
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "server-token-$run")))
        val db = createDatabase("/tmp/jellystructure-test-312-$run.db")
        val mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-312-$run-tags.json"), configStore)
        for (id in listOf("film-a", "film-b", "film-c", "film-d")) mediaStore.addOrUpdate(MediaItem(
            id = id, title = id, year = 2026, kind = MediaKind.MOVIE, path = "/tmp/$id.mkv", tmdbId = null, imdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = emptyList(), jellyfinId = id,
        ))
        val service = PlaybackService(mediaStore, JellyfinClient(), configStore, PlaybackQoeStore(db), PlaybackStartSampleStore(db), RaviloDeviceService(db),
            writerScope = scope, playbackOutbox = SqlPlaybackOutbox(db, { _, _ -> null }))
        try {
            Rig(service, fake, scope) { server.stop(100, 500) }.block()
        } finally {
            scope.cancel()
            server.stop(100, 500)
            platform.posix.unlink("/tmp/jellystructure-test-312-$run.db")
        }
    }

    private fun device(id: String) = DeviceData(
        deviceId = "$id-${getpid()}", deviceToken = "dt-$id", jellyfinUserId = "u-$id", jellyfinUsername = "viewer",
        jellyfinUserToken = "jt-$id", isAdmin = false,
    )

    @Test
    fun `a stop lands once at its place — then one user-data write`() = rig("once") {
        val tv = device("phone-once")
        service.startPlayback(tv, "film-a", ClientCapabilities())
        service.stopPlayback(tv, "film-a", 1_117_014L)
        waitFor("the stop and its user data land") { fake.lock.withLock { fake.userDataWrites.any { it.first == "film-a" } } }
        delay(4_000)   // past the read-back
        fake.lock.withLock {
            assertEquals(listOf("film-a" to 1_117_014L), fake.stops.filter { it.first == "film-a" }, "one stop, at the place")
            val w = fake.userDataWrites.filter { it.first == "film-a" }
            assertEquals(1, w.size, "one user-data write, no read-back correction needed: $w")
            assertTrue("\"Played\":false" in w.single().second && "\"PlaybackPositionTicks\":11170140000" in w.single().second, w.single().second)
        }
    }

    @Test
    fun `an abandoned start re-sends the viewer's own stop — never one at its start position`() = rig("abandon") {
        val tv = device("phone-abandon")
        // The viewer pressed Back at 500 s while the start was still negotiating: the stop arrives first.
        service.stopPlayback(tv, "film-b", 500_000L)
        service.startPlayback(tv, "film-b", ClientCapabilities())   // starts from 0 (nothing saved yet) and finds the stop
        waitFor("the stops land") { fake.lock.withLock { fake.stops.count { it.first == "film-b" } >= 1 } }
        delay(4_000)
        fake.lock.withLock {
            val stops = fake.stops.filter { it.first == "film-b" }.map { it.second }
            assertTrue(stops.isNotEmpty() && stops.all { it == 500_000L }, "every stop at the viewer's own place, none at 0: $stops")
            assertTrue(fake.releases.contains("psid-1"), "the abandoned start's encode was released: ${fake.releases}")
        }
    }

    @Test
    fun `a superseded start sends Jellyfin no stop — only releases the old encode`() = rig("supersede") {
        val tv = device("phone-supersede")
        service.startPlayback(tv, "film-c", ClientCapabilities())
        service.reportProgress(tv, "film-c", 30_000L, false)
        service.startPlayback(tv, "film-c", ClientCapabilities())   // the same play again (a retry, a return from the background)
        delay(500)
        fake.lock.withLock {
            assertTrue(fake.stops.none { it.first == "film-c" }, "no stop while the play goes on: ${fake.stops}")
            assertTrue(fake.releases.contains("psid-1"), "the first start's encode was released: ${fake.releases}")
        }
        service.stopPlayback(tv, "film-c", 600_000L)
        waitFor("the real stop lands") { fake.lock.withLock { fake.stops.any { it.first == "film-c" } } }
        fake.lock.withLock { assertEquals(listOf(600_000L), fake.stops.filter { it.first == "film-c" }.map { it.second }) }
    }

    @Test
    fun `a stray stop at 0 after ours is undone by the read-back`() = rig("stray") {
        val tv = device("phone-stray")
        fake.lock.withLock { fake.strayStopAfterMs = 500L }
        service.startPlayback(tv, "film-d", ClientCapabilities())
        service.stopPlayback(tv, "film-d", 1_117_014L)
        waitFor("the read-back writes the place again") { fake.lock.withLock { fake.userDataWrites.count { it.first == "film-d" } >= 2 } }
        fake.lock.withLock {
            assertEquals(11_170_140_000L, fake.position["film-d"], "Jellyfin holds our place again")
            assertTrue(fake.stops.any { it == ("film-d" to 0L) }, "the stray stop did land")
        }
    }
}
