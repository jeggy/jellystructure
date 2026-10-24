package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.PlaybackQoeReport
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2205 (FR-2, FR-3, FR-4) — the negotiation end to end against a fake Jellyfin: what a client that reports decode health is asked
 * of (nothing, until its decode has failed), what a failure the client reports does to the next start, an installed client's unchanged
 * rule, and a hand-over's ticket starting where the player will switch.
 */
class DecodeHealthNegotiationTest {
    private class Fake { val playbackInfoBodies = mutableListOf<String>() }

    private fun rig(name: String, block: suspend (PlaybackService, Fake, StreamRecordStore) -> Unit) = runBlocking {
        val run = "${getpid()}-$name"
        val fake = Fake()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/Users/{id}") { call.respondText("{}", ContentType.Application.Json) }
                get("/Items/{id}") {
                    val id = call.parameters["id"]!!
                    call.respondText("""{"Id":"$id","Name":"Film $id","RunTimeTicks":${100 * 60_000L * 10_000},"UserData":{"PlaybackPositionTicks":0,"Played":false}}""", ContentType.Application.Json)
                }
                post("/Items/{id}/PlaybackInfo") {
                    val id = call.parameters["id"]!!
                    fake.playbackInfoBodies += call.receiveText()
                    val body = if (id.startsWith("transcode")) """{"MediaSources":[{"Id":"$id","Container":"mkv","SupportsDirectPlay":false,"TranscodingUrl":"/videos/$id/master.m3u8?ApiKey=x&TranscodeReasons=VideoBitrateNotSupported&VideoCodec=h264"}],"PlaySessionId":"psid-$id"}"""
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
        val configStore = ConfigStore("/tmp/jellystructure-test-2205n-$run.toml")
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "server-token-$run")))
        val db = createDatabase("/tmp/jellystructure-test-2205n-$run.db")
        val mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-2205n-$run-tags.json"), configStore)
        // A 2160p HDR10 HEVC file of 69.6 Mbps video, not on this disk: its stream bitrate is the scan's video bitrate (+5 %, +256 kb/s).
        val video = Track(0, "0:v:0", TrackKind.VIDEO, "hevc", null, null, default = true, forced = false, width = 3840, height = 2160, videoRange = "HDR",
            videoBitrate = 69_600_000, videoBitrateSource = "stream")
        for (id in listOf("film", "transcode-film")) mediaStore.addOrUpdate(MediaItem(
            id = id, title = id, year = 2026, kind = MediaKind.MOVIE, path = "/tmp/jellystructure-test-2205n-$id.mkv", tmdbId = null, imdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = listOf(video), jellyfinId = id,
        ))
        val records = StreamRecordStore(db)
        val service = PlaybackService(mediaStore, JellyfinClient(), configStore, PlaybackQoeStore(db), PlaybackStartSampleStore(db), RaviloDeviceService(db),
            writerScope = scope, playbackOutbox = SqlPlaybackOutbox(db, { _, _ -> null }), streamRecords = records)
        try {
            block(service, fake, records)
        } finally {
            scope.cancel()
            server.stop(100, 500)
            platform.posix.unlink("/tmp/jellystructure-test-2205n-$run.db")
        }
    }

    private fun device(id: String) = DeviceData(
        deviceId = "$id-${getpid()}", deviceToken = "dt-$id", jellyfinUserId = "u-$id", jellyfinUsername = "viewer",
        jellyfinUserToken = "jt-$id", isAdmin = false, platform = "tv", kind = "tv",
    )

    private val stueTv = ClientCapabilities(
        hlsAdaptive = true, supportsHdr10 = true, maxHevcBitrate = 60_000_000, maxH264Bitrate = 60_000_000, linkKind = "ethernet", linkMbps = 1000,
    )

    @Test fun `a client that reports decode health is asked no decode ceiling until its decode has failed`() = rig("learn") { service, fake, records ->
        val tv = device("tv-learn")
        val caps = stueTv.copy(reportsDecodeHealth = true)
        val ticket = service.startPlayback(tv, "film", caps)
        assertTrue(ticket.directPlay, "the reported film direct-plays")
        val streamBps = assertNotNull(ticket.streamBps, "a direct play of a client that reports decode health says how big its stream is")
        assertEquals(73_336_000L, streamBps, "69.6 Mbps of picture, +5 %, +256 kb/s (the scan has no file on this disk)")
        assertFalse("VideoBitrate" in fake.playbackInfoBodies.single(), "the advertised 60 Mbps is not a condition: ${fake.playbackInfoBodies.single()}")

        // Its decode fails: the client says so with the post that precedes its hand-over; the file's own video bitrate is the figure.
        service.recordHandoverFacts(tv, PlaybackQoeReport(itemId = "film", perItem = true, directPlay = true, droppedFrames = 400, renderedFrames = 9_000, decodeFailedCodec = "hevc"))
        assertEquals(69_600_000L, records.get(tv.deviceId)!!.decodeFailed["hevc"]?.bps)

        // The next start of the same file goes to the ladder: Jellyfin is told hevc above 0.9 × 69.6 Mbps does not play.
        service.startPlayback(tv, "film", caps)
        val second = fake.playbackInfoBodies.last()
        assertTrue(""""Codec":"hevc","Conditions":[{"Condition":"LessThanEqual","Property":"VideoBitrate","Value":"62640000"""" in second, second)
        assertFalse("54000000" in second, "and nothing is advertised")
    }

    @Test fun `an installed client keeps the advertised gate whatever is learned about it`() = rig("installed") { service, fake, records ->
        val tv = device("tv-installed")
        records.put(tv.deviceId, StreamRecord(decodeFailed = mapOf("hevc" to DecodeFailure(50_000_000, 10))), now = 10)
        service.startPlayback(tv, "film", stueTv.copy(maxVideoBitrate = 60_000_000))
        val body = fake.playbackInfoBodies.single()
        assertTrue(""""Codec":"hevc","Conditions":[{"Condition":"LessThanEqual","Property":"VideoBitrate","Value":"54000000"""" in body, body)
        assertFalse("45000000" in body, "a learned figure it never reported does not apply to a client that cannot hand over")
        assertEquals(null, service.startPlayback(device("tv-installed-2"), "film", stueTv).streamBps, "and its ticket is the one it always got")
    }

    @Test fun `a direct play left because its path could not carry it is held for 24 hours`() = rig("stalled") { service, _, records ->
        val tv = device("tv-stalled")
        service.recordHandoverFacts(tv, PlaybackQoeReport(itemId = "film", perItem = true, directPlay = true, stalledBps = 73_336_000))
        val rec = assertNotNull(records.get(tv.deviceId))
        assertEquals(73_336_000L, rec.stalledBps)
        assertTrue((service.takeOf(tv) ?: Long.MAX_VALUE) < 73_336_000L, "the file is no longer under what the device can take")
        // A repeat of the same fact does not restart the hold.
        val at = rec.stalledAt
        service.recordHandoverFacts(tv, PlaybackQoeReport(itemId = "film", perItem = true, directPlay = true, stalledBps = 73_336_000))
        assertEquals(at, records.get(tv.deviceId)!!.stalledAt)
        // Facts that are not a hand-over's change nothing.
        service.recordHandoverFacts(tv, PlaybackQoeReport(itemId = "film", perItem = true, directPlay = true))
        assertNull(records.get(tv.deviceId)!!.decodeFailed["hevc"])
    }

    @Test fun `a hand-over ticket starts where the player will switch and an ordinary restream where it is`() = rig("handover") { service, _, _ ->
        val tv = device("tv-handover")
        val caps = stueTv.copy(reportsDecodeHealth = true)
        service.startPlayback(tv, "transcode-film", caps)
        val ahead = service.restream(tv, "transcode-film", -1, positionMs = 100_000, capabilities = caps, handoverAtMs = 112_000)
        assertEquals(112_000L, ahead.startPositionMs, "H, so our encoder starts at H's segment and the player cuts over there")
        assertFalse(ahead.directPlay)
        val now = service.restream(tv, "transcode-film", -1, positionMs = 100_000, capabilities = caps)
        assertEquals(100_000L, now.startPositionMs)
        val behind = service.restream(tv, "transcode-film", -1, positionMs = 100_000, capabilities = caps, handoverAtMs = 90_000)
        assertEquals(100_000L, behind.startPositionMs, "an H before the playhead is no hand-over at all")
    }
}
