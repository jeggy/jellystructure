package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.EncoderConfig
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * DEB-5024 (FR-32) — a Samsung's freeze of 5 s restreams with `below_bandwidth_bps` set to the rung that froze. For a play that
 * was Jellyfin's video copy, on a path the record has proven (so a plain start keeps the copy), the answer is our encoder's
 * ladder with every rung under the bound, never Jellyfin's copy again. The negotiation runs end to end against a fake Jellyfin
 * that always answers a copy (the container is the only reason to transcode); the encoder's GPU and ffmpeg are the test seams.
 */
class FrozenRestreamTest {
    private val bodies = mutableListOf<String>()

    private fun rig(name: String, block: suspend (PlaybackService, StreamRecordStore) -> Unit) = runBlocking {
        val run = "${getpid()}-$name"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/Users/{id}") { call.respondText("{}", ContentType.Application.Json) }
                get("/Items/{id}") {
                    val id = call.parameters["id"]!!
                    val streams = """[{"Type":"Video","Index":0,"Codec":"h264","Width":1920,"Height":1080,"BitRate":2400000},
                        {"Type":"Audio","Index":1,"Codec":"aac","Language":"eng","Channels":2,"IsDefault":true}]"""
                    call.respondText(
                        """{"Id":"$id","Name":"Film $id","RunTimeTicks":${100 * 60_000L * 10_000},"UserData":{"PlaybackPositionTicks":0,"Played":false},
                            "MediaStreams":$streams,"MediaSources":[{"Id":"src-$id","Container":"mkv","MediaStreams":$streams}]}""",
                        ContentType.Application.Json,
                    )
                }
                post("/Items/{id}/PlaybackInfo") {
                    val id = call.parameters["id"]!!
                    bodies += call.receiveText()
                    call.respondText(
                        """{"MediaSources":[{"Id":"$id","Container":"mkv","SupportsDirectPlay":false,"MediaStreams":[{"Type":"Video","Index":0,"Codec":"h264","Width":1920,"Height":1080,"BitRate":2400000}],"TranscodingUrl":"/videos/$id/master.m3u8?ApiKey=x&TranscodeReasons=ContainerNotSupported&VideoCodec=h264&AudioCodec=aac&AudioBitrate=192000"}],"PlaySessionId":"psid-$id"}""",
                        ContentType.Application.Json,
                    )
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
        val configStore = ConfigStore("/tmp/jellystructure-test-deb5079-$run.toml")
        configStore.update(configStore.current.copy(
            apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "server-token-$run"),
            encoder = EncoderConfig(enabled = true, workDir = "/tmp/js-test-5079-$run", ffmpegDir = "/nonexistent"),
        ))
        val dbPath = "/tmp/jellystructure-test-deb5079-$run.db"
        val db = createDatabase(dbPath)
        val mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-deb5079-$run-tags.json"), configStore)
        // A 1080p H.264 file of 2.4 Mb/s video: Jellyfin copies the picture into HLS (the container is the only reason to transcode).
        val tracks = listOf(
            Track(0, "0:v:0", TrackKind.VIDEO, "h264", null, null, default = true, forced = false, width = 1920, height = 1080, videoRange = "SDR", videoBitrate = 2_400_000, durationMs = 6_000_000),
            Track(1, "0:a:0", TrackKind.AUDIO, "aac", "eng", null, default = true, forced = false),
        )
        mediaStore.addOrUpdate(MediaItem(
            id = "film", title = "film", year = 2026, kind = MediaKind.MOVIE, path = "/tmp/jellystructure-test-deb5079-$run.mkv", tmdbId = null, imdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = tracks, jellyfinId = "film",
        ))
        val records = StreamRecordStore(db)
        val service = PlaybackService(mediaStore, JellyfinClient(), configStore, PlaybackQoeStore(db), PlaybackStartSampleStore(db), RaviloDeviceService(db),
            writerScope = scope, playbackOutbox = SqlPlaybackOutbox(db, { _, _ -> null }), streamRecords = records)
        service.encoder.testCards = listOf(EncoderCard(0, "Quadro P4000", null, 0))
        service.encoder.testFfmpeg = true
        try {
            block(service, records)
        } finally {
            scope.cancel()
            server.stop(100, 500)
            for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$s") }
        }
    }

    private fun device(id: String) = DeviceData(
        deviceId = "$id-${getpid()}", deviceToken = "dt-$id", jellyfinUserId = "u-$id", jellyfinUsername = "viewer",
        jellyfinUserToken = "jt-$id", isAdmin = false, platform = "tizen", kind = "tv",
    )

    private val samsung = ClientCapabilities(
        containers = listOf("mp4", "ts"), videoCodecs = listOf("h264", "hevc"), audioCodecs = listOf("aac", "ac3", "eac3"),
        hlsOnly = true, hlsAdaptive = true, linkKind = "unknown",
    )

    @Test fun `a freeze restream on a proven path leaves Jellyfin's copy for our ladder with every rung under the bound`() = rig("frozen") { service, records ->
        val tv = device("tv-frozen")
        val now = Clock.System.now().epochSeconds
        records.put(tv.deviceId, StreamRecord(provenBps = 40_000_000, provenAt = now), now = now)

        // The path is proven at far over twice the copy: a plain start and a plain restream keep Jellyfin's copy.
        val start = service.startPlayback(tv, "film", samsung)
        assertEquals("jellyfin", start.encoder, "a proven path keeps the copy: ${start.hlsUrl}")
        assertEquals("jellyfin", service.restream(tv, "film", -1, 60_000, samsung).encoder)

        // The copy froze under a stream of 2.8 Mb/s (its own bitrate, from AVPlay): the restream carries that bound.
        val bound = 2_800_000L
        val answer = service.restream(tv, "film", -1, 60_000, samsung, belowBandwidthBps = bound)
        assertEquals("ours", answer.encoder, "never the stream that froze")
        assertTrue(assertNotNull(answer.hlsUrl).startsWith("/api/tv/stream/"), answer.hlsUrl)
        assertTrue(""""MaxStreamingBitrate":${bound - 1}""" in bodies.last(), "and Jellyfin is asked under it too: ${bodies.last()}")
        val start1080 = assertNotNull(answer.startVariantBps, "a ladder, with a start rung")
        assertTrue(start1080 < bound, "the start rung is under the bound: $start1080")
        assertFalse(answer.directPlay)
    }
}
