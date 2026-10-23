package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.FileStamp
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
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2205 (FR-6, FR-8) — the ticket points an embedded text subtitle at OUR cache by the container's index (a sidecar
 * added later shifts Jellyfin's numbers and not that), leaves a sidecar on Jellyfin's URL, and a retry of the same title
 * leaves a second QoE row.
 */
class PlaybackSubtitleTicketTest {
    private fun rig(name: String, block: suspend (PlaybackService, PlaybackQoeStore, CoroutineScope) -> Unit) = runBlocking {
        val run = "${getpid()}-$name"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/Users/{id}") { call.respondText("{}", ContentType.Application.Json) }
                // A sidecar added after the scan: Jellyfin 12.1 numbers it FIRST (index 0) and shifts the streams in the file up by one.
                get("/Items/{id}") {
                    val id = call.parameters["id"]!!
                    call.respondText("""{"Id":"$id","Name":"Film","RunTimeTicks":${90 * 60_000L * 10_000},"UserData":{"PlaybackPositionTicks":0,"Played":false},"MediaStreams":[
                        {"Type":"Subtitle","Index":0,"Codec":"srt","Language":"dan","IsExternal":true,"IsTextSubtitleStream":true,"Path":"/media/film.da.srt"},
                        {"Type":"Video","Index":1,"Codec":"hevc"},{"Type":"Audio","Index":2,"Codec":"dts","Language":"eng","Channels":6},
                        {"Type":"Subtitle","Index":3,"Codec":"subrip","Language":"eng","IsTextSubtitleStream":true},
                        {"Type":"Subtitle","Index":4,"Codec":"subrip","Language":"ger","IsTextSubtitleStream":true},
                        {"Type":"Subtitle","Index":5,"Codec":"hdmv_pgs_subtitle","Language":"spa"}]}""", ContentType.Application.Json)
                }
                post("/Items/{id}/PlaybackInfo") {
                    val id = call.parameters["id"]!!
                    call.respondText("""{"MediaSources":[{"Id":"$id","Container":"mkv","SupportsDirectPlay":true}],"PlaySessionId":"psid-$id-${call.hashCode()}"}""", ContentType.Application.Json)
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
        val configStore = ConfigStore("/tmp/jellystructure-test-deb2205-$run.toml")
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "server-token-$run")))
        val db = createDatabase("/tmp/jellystructure-test-deb2205-$run.db")
        val mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-deb2205-$run-tags.json"), configStore)
        fun track(i: Int, kind: TrackKind, codec: String, spec: String, lang: String?) = Track(streamIndex = i, specifier = spec, kind = kind, codec = codec, language = lang, title = null, default = false, forced = false)
        mediaStore.addOrUpdate(MediaItem(
            id = "film-subs", title = "Film", year = 2026, kind = MediaKind.MOVIE, path = "/tmp/film-subs.mkv", tmdbId = null, imdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, jellyfinId = "film-subs",
            tracks = listOf(
                track(0, TrackKind.VIDEO, "hevc", "0:v:0", null), track(1, TrackKind.AUDIO, "dts", "0:a:0", "eng"),
                track(2, TrackKind.SUBTITLE, "subrip", "0:s:0", "eng"), track(3, TrackKind.SUBTITLE, "subrip", "0:s:1", "ger"),
                track(4, TrackKind.SUBTITLE, "hdmv_pgs_subtitle", "0:s:2", "spa"),
            ),
        ))
        val qoe = PlaybackQoeStore(db)
        val service = PlaybackService(mediaStore, JellyfinClient(), configStore, qoe, PlaybackStartSampleStore(db), RaviloDeviceService(db),
            writerScope = scope, playbackOutbox = SqlPlaybackOutbox(db, { _, _ -> null }))
        try {
            block(service, qoe, scope)
        } finally {
            scope.cancel()
            server.stop(100, 500)
            platform.posix.unlink("/tmp/jellystructure-test-deb2205-$run.db")
        }
    }

    private fun device(id: String) = DeviceData(
        deviceId = "$id-${getpid()}", deviceToken = "dt-$id", jellyfinUserId = "u-$id", jellyfinUsername = "viewer",
        jellyfinUserToken = "jt-$id", isAdmin = false,
    )

    @Test
    fun `an embedded text subtitle is served from our cache by its container index and a sidecar keeps Jellyfin's URL`() = rig("urls") { service, _, scope ->
        service.embeddedSubtitles = EmbeddedSubtitleCache("/tmp/jellystructure-test-deb2205-unused-${getpid()}", scope, stampOf = { FileStamp(1, 1, 1, 1) }, extract = { _, _, _ -> false })
        val ticket = service.startPlayback(device("tv-subs"), "film-subs", ClientCapabilities())
        val byIndex = ticket.subtitles.associateBy { it.index }
        assertEquals("/api/tv/subtitles/film-subs/2.vtt", byIndex[3]?.url, "English is container stream 2, whatever Jellyfin calls it")
        assertEquals(2, byIndex[3]?.containerIndex)
        assertEquals("/api/tv/subtitles/film-subs/3.vtt", byIndex[4]?.url)
        assertTrue(byIndex[0]?.url?.contains("/Videos/film-subs/film-subs/Subtitles/0/0/Stream.vtt") == true, "the sidecar is read directly by Jellyfin: ${byIndex[0]?.url}")
        assertNull(byIndex[0]?.containerIndex)
        assertNull(byIndex[5]?.url, "an image track is burned in, never fetched")
        assertEquals("encode", byIndex[5]?.deliveryMethod)
    }

    @Test
    fun `without the cache the ticket is what it was`() = rig("nocache") { service, _, _ ->
        val ticket = service.startPlayback(device("tv-nocache"), "film-subs", ClientCapabilities())
        val eng = ticket.subtitles.first { it.index == 3 }
        assertTrue(eng.url!!.contains("/Subtitles/3/0/Stream.vtt"), eng.url)
        assertNull(eng.containerIndex)
    }

    @Test
    fun `a retry of the same title one moment later leaves a second QoE row`() = rig("rows") { service, qoe, _ ->
        val tv = device("tv-retry")
        service.startPlayback(tv, "film-subs", ClientCapabilities())
        service.recordQoe(tv, PlaybackQoeReport(itemId = "film-subs", rebufferCount = 2, perItem = true, startFailureStatus = null))
        delay(20)
        service.startPlayback(tv, "film-subs", ClientCapabilities())   // the retry joins the live session: same session id
        service.recordQoe(tv, PlaybackQoeReport(itemId = "film-subs", rebufferCount = 0, perItem = true))
        val rows = qoe.recentForDevice(tv.deviceId)
        assertEquals(2, rows.size, "the retry did not overwrite the first play's row: ${rows.map { it.playSessionId }}")
        assertEquals(setOf(0, 2), rows.map { it.rebufferCount }.toSet())
    }
}
