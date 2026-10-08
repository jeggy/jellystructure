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
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R381 (FR-R381-7, dev review item 12) — `prepare` sends Jellyfin nothing a start sends (no `/Sessions/Playing*`, no
 * encode release, no user data) and starts no tracker or session; the real start afterwards sends exactly the start
 * report. A transcode-only item is answered `directPlay = false` with no URL.
 */
class PlaybackPrepareIntegrationTest {
    private class Fake {
        val lock = Mutex()
        val requests = mutableListOf<String>()   // "METHOD /path"
    }

    private fun rig(name: String, block: suspend (PlaybackService, Fake) -> Unit) = runBlocking {
        val run = "${getpid()}-$name"
        val fake = Fake()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            intercept(ApplicationCallPipeline.Plugins) {
                fake.lock.withLock { fake.requests += "${call.request.httpMethod.value} ${call.request.path()}" }
            }
            routing {
                get("/Users/{id}") { call.respondText("{}", ContentType.Application.Json) }
                get("/Items/{id}") {
                    val id = call.parameters["id"]!!
                    call.respondText("""{"Id":"$id","Name":"Episode $id","RunTimeTicks":${30 * 60_000L * 10_000},"UserData":{"PlaybackPositionTicks":0,"Played":false}}""", ContentType.Application.Json)
                }
                post("/Items/{id}/PlaybackInfo") {
                    val id = call.parameters["id"]!!
                    val body = if (id.startsWith("transcode")) """{"MediaSources":[{"Id":"$id","Container":"mkv","SupportsDirectPlay":false,"TranscodingUrl":"/videos/$id/master.m3u8?ApiKey=x&TranscodeReasons=VideoCodecNotSupported"}],"PlaySessionId":"psid-$id"}"""
                        else """{"MediaSources":[{"Id":"$id","Container":"mkv","SupportsDirectPlay":true}],"PlaySessionId":"psid-$id"}"""
                    call.respondText(body, ContentType.Application.Json)
                }
                post("/Sessions/Playing") { call.receiveText(); call.respond(HttpStatusCode.NoContent) }
                post("/Sessions/Playing/Progress") { call.receiveText(); call.respond(HttpStatusCode.NoContent) }
                post("/Sessions/Playing/Stopped") { call.receiveText(); call.respond(HttpStatusCode.NoContent) }
                post("/UserItems/{id}/UserData") { call.receiveText(); call.respondText("{}", ContentType.Application.Json) }
                delete("/Videos/ActiveEncodings") { call.respond(HttpStatusCode.NoContent) }
                post("/Sessions/Capabilities/Full") { call.respond(HttpStatusCode.NoContent) }
            }
        }.start(wait = false)
        val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
        val configStore = ConfigStore("/tmp/jellystructure-test-r381-$run.toml")
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "server-token-$run")))
        val db = createDatabase("/tmp/jellystructure-test-r381-$run.db")
        val mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-r381-$run-tags.json"), configStore)
        for (id in listOf("ep-next", "transcode-next")) mediaStore.addOrUpdate(MediaItem(
            id = id, title = id, year = 2026, kind = MediaKind.MOVIE, path = "/tmp/$id.mkv", tmdbId = null, imdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = emptyList(), jellyfinId = id,
        ))
        val service = PlaybackService(mediaStore, JellyfinClient(), configStore, PlaybackQoeStore(db), PlaybackStartSampleStore(db), RaviloDeviceService(db),
            writerScope = scope, playbackOutbox = SqlPlaybackOutbox(db, { _, _ -> null }))
        try {
            block(service, fake)
        } finally {
            scope.cancel()
            server.stop(100, 500)
            platform.posix.unlink("/tmp/jellystructure-test-r381-$run.db")
        }
    }

    private fun device(id: String) = DeviceData(
        deviceId = "$id-${getpid()}", deviceToken = "dt-$id", jellyfinUserId = "u-$id", jellyfinUsername = "viewer",
        jellyfinUserToken = "jt-$id", isAdmin = false,
    )

    private val startSideEffects = listOf("POST /Sessions/Playing", "DELETE /Videos/ActiveEncodings", "/UserData")

    @Test
    fun `prepare reports nothing to Jellyfin and starts no session — the start then reports exactly once`() = rig("direct") { service, fake ->
        val tv = device("tv-prepare")
        val prepared = service.preparePlayback(tv, "ep-next", ClientCapabilities())
        assertTrue(prepared.directPlay, "a direct-playable next item is prepared as one")
        assertNotNull(prepared.url, "with its direct-play URL")
        assertEquals(0L, prepared.startPositionMs)
        assertTrue(prepared.expiresAt > 0)
        fake.lock.withLock {
            val bad = fake.requests.filter { r -> startSideEffects.any { it in r } }
            assertTrue(bad.isEmpty(), "prepare sent a start's side effect: $bad (all: ${fake.requests})")
            fake.requests.clear()
        }
        assertNull(nowPlayingItem(tv.deviceId), "prepare starts no tracker")
        // The real start when the item actually begins: exactly one start report.
        service.startPlayback(tv, "ep-next", ClientCapabilities())
        fake.lock.withLock {
            assertEquals(1, fake.requests.count { it == "POST /Sessions/Playing" }, "the start reports once: ${fake.requests}")
        }
        assertEquals("ep-next", nowPlayingItem(tv.deviceId))
    }

    @Test
    fun `a transcode-only next item is not prepared`() = rig("transcode") { service, fake ->
        val tv = device("tv-prepare-tc")
        val prepared = service.preparePlayback(tv, "transcode-next", ClientCapabilities())
        assertFalse(prepared.directPlay)
        assertNull(prepared.url, "no URL: a transcoded next item is not preloaded until 309/313")
        fake.lock.withLock { assertTrue(fake.requests.none { r -> startSideEffects.any { it in r } || "master.m3u8" in r }, "${fake.requests}") }
    }
}
