package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.ClientCapabilities
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
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
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * DEB-5916 (DEB-5503, FR-44) — a Samsung start, prewarm and restream whose chosen audio is not the file's default track is negotiated
 * with no direct-play profile, carrying that audio; the default keeps FR-43's containers; an Android TV with the same pick still direct-plays.
 */
class SamsungAudioPickTest {
    private fun track(index: Int, default: Boolean = false, copyOf: Int? = null) = AudioTrack(index = index, language = "eng", label = "t$index", isDefault = default, copyOf = copyOf)

    @Test fun `the rule - no choice and the default and the first when none is marked and one track are not off the default`() {
        val two = listOf(track(1, default = true), track(2))
        assertFalse(audioIsOffDefault(two, emptySet(), null))
        assertFalse(audioIsOffDefault(two, emptySet(), 1))
        assertTrue(audioIsOffDefault(two, emptySet(), 2))
        assertFalse(audioIsOffDefault(listOf(track(1), track(2)), emptySet(), 1), "none marked: the first is the default")
        assertTrue(audioIsOffDefault(listOf(track(1), track(2)), emptySet(), 2))
        assertTrue(audioIsOffDefault(listOf(track(1), track(2, default = true)), emptySet(), 1), "Jellyfin's mark wins over the order")
        assertFalse(audioIsOffDefault(listOf(track(2)), emptySet(), 2), "a one-track file never leaves direct play for audio")
    }

    @Test fun `the rule - an empty track is not the default and and a copy of the default is the default`() {
        assertFalse(audioIsOffDefault(listOf(track(1, default = true), track(2), track(3)), setOf(1), 2), "the empty first track is skipped: 2 is the first real one")
        assertFalse(audioIsOffDefault(listOf(track(1), track(2)), setOf(2), 1), "one real track")
        assertFalse(audioIsOffDefault(listOf(track(1, default = true), track(2), track(9, copyOf = 1)), emptySet(), 9))
        assertTrue(audioIsOffDefault(listOf(track(1, default = true), track(2), track(9, copyOf = 2)), emptySet(), 9))
    }

    @Test fun `the switch helper reads the pick only for a tizen device`() {
        val caps = ClientCapabilities(containers = listOf("mkv", "mp4"))
        assertTrue(capabilitiesForPlatform(caps, "tizen", samsungDirectPlay = true, audioOffDefault = true).hlsOnly)
        assertSame(caps, capabilitiesForPlatform(caps, "tizen", samsungDirectPlay = true, audioOffDefault = false))
        assertSame(caps, capabilitiesForPlatform(caps, "tv", samsungDirectPlay = true, audioOffDefault = true))
    }

    private class Fake { val bodies = mutableListOf<String>() }

    private fun rig(name: String, block: suspend (PlaybackService, Fake) -> Unit) = runBlocking {
        val run = "${getpid()}-$name"
        val fake = Fake()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/Users/{id}") { call.respondText("{}", ContentType.Application.Json) }
                get("/Items/{id}") {
                    val id = call.parameters["id"]!!
                    call.respondText("""{"Id":"$id","Name":"Film $id","RunTimeTicks":${100 * 60_000L * 10_000},"UserData":{"PlaybackPositionTicks":0,"Played":false},"MediaStreams":[
                        {"Type":"Video","Index":0,"Codec":"h264"},
                        {"Type":"Audio","Index":1,"Codec":"aac","Language":"eng","Channels":2,"IsDefault":true},
                        {"Type":"Audio","Index":2,"Codec":"aac","Language":"dan","Channels":2}]}""", ContentType.Application.Json)
                }
                post("/Items/{id}/PlaybackInfo") {
                    val id = call.parameters["id"]!!
                    fake.bodies += call.receiveText()
                    call.respondText("""{"MediaSources":[{"Id":"$id","Container":"mkv","SupportsDirectPlay":true}],"PlaySessionId":"psid-$id-${fake.bodies.size}"}""", ContentType.Application.Json)
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
        val configStore = ConfigStore("/tmp/jellystructure-test-5916-$run.toml")
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "server-token-$run")))
        val db = createDatabase("/tmp/jellystructure-test-5916-$run.db")
        val mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-5916-$run-tags.json"), configStore)
        mediaStore.addOrUpdate(MediaItem(
            id = "film", title = "film", year = 2026, kind = MediaKind.MOVIE, path = "/tmp/jellystructure-test-5916-film.mkv", tmdbId = null, imdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = emptyList(), jellyfinId = "film",
        ))
        val service = PlaybackService(mediaStore, JellyfinClient(), configStore, PlaybackQoeStore(db), PlaybackStartSampleStore(db), RaviloDeviceService(db),
            writerScope = scope, playbackOutbox = SqlPlaybackOutbox(db, { _, _ -> null }), streamRecords = StreamRecordStore(db))
        try {
            block(service, fake)
        } finally {
            scope.cancel()
            server.stop(100, 500)
            platform.posix.unlink("/tmp/jellystructure-test-5916-$run.db")
        }
    }

    private fun device(id: String, platform: String) = DeviceData(
        deviceId = "$id-${getpid()}", deviceToken = "dt-$id", jellyfinUserId = "u-$id", jellyfinUsername = "viewer",
        jellyfinUserToken = "jt-$id", isAdmin = false, platform = platform, kind = "tv",
    )

    private val caps = ClientCapabilities(containers = listOf("mkv", "mp4"), videoCodecs = listOf("h264"), audioCodecs = listOf("aac"), hlsOnly = false)

    private fun assertLadder(body: String, audio: Int?) {
        assertTrue(""""DirectPlayProfiles":[]""" in body, "no direct-play profile: $body")
        if (audio != null) assertTrue(""""AudioStreamIndex":$audio""" in body, "carries audio $audio: $body")
    }

    private fun assertDirect(body: String) {
        assertFalse(""""DirectPlayProfiles":[]""" in body, "FR-43's containers: $body")
        assertTrue(""""Container":"mkv,mp4"""" in body, body)
    }

    // The viewer's remembered audio is Danish ("dan"), the file's second track; its default is English (index 1).
    @Test fun `a tizen start whose remembered audio is not the default leaves direct play carrying that audio`() = rig("start") { service, fake ->
        service.startPlayback(device("s1", "tizen"), "film", caps, audioLanguage = "dan")
        assertLadder(fake.bodies.last(), audio = 2)
    }

    @Test fun `a tizen start with the default chosen and or no choice and keeps FR-43's containers`() = rig("startdefault") { service, fake ->
        service.startPlayback(device("s2", "tizen"), "film", caps, audioLanguage = "eng")
        assertDirect(fake.bodies.last())
        service.startPlayback(device("s2b", "tizen"), "film", caps)
        assertDirect(fake.bodies.last())
    }

    @Test fun `an Android TV start with the same pick still direct-plays`() = rig("androidtv") { service, fake ->
        service.startPlayback(device("a1", "tv"), "film", caps, audioLanguage = "dan")
        assertDirect(fake.bodies.last())
        assertTrue(""""AudioStreamIndex":2""" in fake.bodies.last())
    }

    @Test fun `a tizen restream onto a non-default audio is the ladder and onto the default is direct`() = rig("restream") { service, fake ->
        val tizen = device("s3", "tizen")
        service.restream(tizen, "film", -1, 1_000L, caps, audioStreamIndex = 2)
        assertLadder(fake.bodies.last(), audio = 2)
        service.restream(tizen, "film", -1, 1_000L, caps, audioStreamIndex = 1)
        assertDirect(fake.bodies.last())
        service.restream(device("a2", "tv"), "film", -1, 1_000L, caps, audioStreamIndex = 2)
        assertDirect(fake.bodies.last())
    }

    @Test fun `a tizen prewarm with a non-default remembered audio is the ladder and with the default direct`() = rig("prewarm") { service, fake ->
        val tizen = device("s4", "tizen")
        service.prewarmPlayback(tizen, "film", caps, audioLanguage = "dan")
        assertLadder(fake.bodies.last(), audio = 2)
        service.prewarmPlayback(tizen, "film", caps, audioLanguage = "eng")
        assertDirect(fake.bodies.last())
        service.prewarmPlayback(device("a3", "tv"), "film", caps, audioLanguage = "dan")
        assertDirect(fake.bodies.last())
    }

    @Test fun `a tizen prepare carries no pick and so it keeps FR-43's containers`() = rig("prepare") { service, fake ->
        service.preparePlayback(device("s5", "tizen"), "film", caps)
        assertDirect(fake.bodies.last())
    }
}
