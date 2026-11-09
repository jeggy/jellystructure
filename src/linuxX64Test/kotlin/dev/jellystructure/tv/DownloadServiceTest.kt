package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaSegmentStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.DownloadStartRequest
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.tmdb.TmdbClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-4061 (DEB-3995 FR-2, FR-4) — the server offers the original file to download. Against a fake Jellyfin: the options for a
 * film, a viewer the operator switched off, a codec the phone cannot decode, the ticket's shape, a fresh URL on a second
 * `start`, and that a `start` is never a play (nothing reaches Jellyfin that a play sends).
 */
class DownloadServiceTest {
    private val bytes = 4_815_162_342L

    private class Fake {
        val lock = Mutex()
        val requests = mutableListOf<String>()
    }

    private fun rig(name: String, block: suspend (DownloadService, Fake, DeviceData, DeviceData) -> Unit) = runBlocking {
        val run = "${getpid()}-$name"
        val fake = Fake()
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            intercept(ApplicationCallPipeline.Plugins) {
                fake.lock.withLock { fake.requests += "${call.request.httpMethod.value} ${call.request.path()}" }
            }
            routing {
                // The viewer named "off" has the operator's switch off; "silent" has a policy that does not carry the field.
                get("/Users/{id}") {
                    val id = call.parameters["id"]!!
                    val policy = when (id) {
                        "u-off" -> """{"IsAdministrator":false,"EnableContentDownloading":false}"""
                        else -> """{"IsAdministrator":false}"""
                    }
                    call.respondText("""{"Id":"$id","Name":"viewer","Policy":$policy}""", ContentType.Application.Json)
                }
                get("/Items/{id}") {
                    val id = call.parameters["id"]!!
                    val codec = if (id.startsWith("av1")) "av1" else "hevc"
                    val streams = """[{"Type":"Video","Index":0,"Codec":"$codec","Width":3840,"Height":2160,"BitRate":40000000},
                        {"Type":"Audio","Index":1,"Codec":"eac3","Language":"eng","Channels":6},
                        {"Type":"Subtitle","Index":2,"Codec":"subrip","Language":"eng","DisplayTitle":"English","IsTextSubtitleStream":true},
                        {"Type":"Subtitle","Index":3,"Codec":"subrip","Language":"dan","DisplayTitle":"Danish","IsTextSubtitleStream":true,"IsForced":true},
                        {"Type":"Subtitle","Index":4,"Codec":"hdmv_pgs_subtitle","Language":"fre","DisplayTitle":"French"}]"""
                    call.respondText(
                        """{"Id":"$id","Name":"Title $id","RunTimeTicks":${100 * 60_000L * 10_000},"MediaStreams":$streams,
                            "MediaSources":[{"Id":"src-$id","Container":"mkv","Size":$bytes,"MediaStreams":$streams}]}""",
                        ContentType.Application.Json,
                    )
                }
            }
        }.start(wait = false)
        val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
        val configStore = ConfigStore("/tmp/jellystructure-test-deb4061-$run.toml")
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "server-token-$run")))
        val dbPath = "/tmp/jellystructure-test-deb4061-$run.db"
        val db = createDatabase(dbPath)
        val mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-deb4061-$run-tags.json"), configStore)
        for (id in listOf("film-jf", "av1-jf")) mediaStore.addOrUpdate(MediaItem(
            id = id, title = id, year = 2026, kind = MediaKind.MOVIE, path = "/tmp/$id.mkv", tmdbId = null, imdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = emptyList(), jellyfinId = id,
        ))
        mediaStore.addOrUpdate(MediaItem(
            id = "show", title = "Show", year = 2026, kind = MediaKind.TV_SHOW, path = "/tv/Show", tmdbId = null,
            originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L, jellyfinId = "series-jf",
            episodes = listOf(Episode(filename = "S01E01.mkv", path = "/tv/Show/S01E01.mkv", seasonNumber = 1, episodeNumber = 1, tracks = emptyList(), issueCount = 0, jellyfinId = "ep-jf", runtime = 40)),
        ))
        val detail = DetailService(mediaStore, JellyfinClient(), configStore, ArtworkDownloader(TmdbClient(configStore), Screengrabber()), MediaSegmentStore(db), RaviloDeviceService(db), PlaybackStartSampleStore(db))
        val playback = PlaybackService(mediaStore, JellyfinClient(), configStore, PlaybackQoeStore(db), PlaybackStartSampleStore(db), RaviloDeviceService(db))
        val service = DownloadService(mediaStore, JellyfinClient(), configStore, detail, playback)
        try {
            block(service, fake, device("on"), device("off"))
        } finally {
            server.stop(100, 500)
            for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$s") }
        }
    }

    private fun device(id: String) = DeviceData(
        deviceId = "$id-${getpid()}", deviceToken = "dt-$id", jellyfinUserId = "u-$id", jellyfinUsername = "viewer",
        jellyfinUserToken = "jt-$id", isAdmin = false, kind = "phone",
    )

    private fun <T> DownloadOutcome<T>.ok(): T = (this as? DownloadOutcome.Ok)?.value ?: error("expected an answer, got $this")

    @Test
    fun `options lists the original with its exact size`() = rig("options") { service, _, on, _ ->
        val options = service.options(on, "film-jf", null, null).ok().options
        val original = options.single()
        assertEquals("original", original.quality)
        assertEquals(bytes, original.bytes)
        assertTrue(original.exact)
        assertEquals(2160, original.height)
    }

    @Test
    fun `a policy that does not carry the field reads as on`() = rig("default-on") { service, _, on, _ ->
        assertEquals(true, service.allowed(on))
    }

    @Test
    fun `a viewer the operator switched off is refused with 403 on both routes`() = rig("off") { service, _, _, off ->
        assertEquals(false, service.allowed(off))
        val options = service.options(off, "film-jf", null, null)
        val start = service.start(off, DownloadStartRequest(itemId = "film-jf"))
        assertEquals(DownloadOutcome.NotAllowed, options)
        assertEquals(DownloadOutcome.NotAllowed, start)
        assertEquals(HttpStatusCode.Forbidden, options.httpStatus())
        assertEquals(HttpStatusCode.Forbidden, start.httpStatus())
    }

    @Test
    fun `a codec the phone cannot decode is not listed and start refuses it`() = rig("codec") { service, _, on, _ ->
        val h264Only = ClientCapabilities(videoCodecs = listOf("h264"))
        assertTrue(service.options(on, "av1-jf", null, h264Only).ok().options.isEmpty())
        assertEquals(HttpStatusCode.BadRequest, service.start(on, DownloadStartRequest(itemId = "av1-jf", capabilities = h264Only)).httpStatus())
        // The same file for a phone that does decode it, and for one that declared nothing.
        assertEquals(1, service.options(on, "av1-jf", null, ClientCapabilities(videoCodecs = listOf("h264", "av1"))).ok().options.size)
        assertEquals(1, service.options(on, "av1-jf", null, ClientCapabilities()).ok().options.size)
    }

    @Test
    fun `codec names match across spellings`() {
        assertTrue(DownloadService.decodes(ClientCapabilities(videoCodecs = listOf("hevc")), "H265"))
        assertTrue(DownloadService.decodes(ClientCapabilities(videoCodecs = listOf("avc")), "h264"))
        assertFalse(DownloadService.decodes(ClientCapabilities(videoCodecs = listOf("h264")), "hevc"))
        assertTrue(DownloadService.decodes(null, "av1"))
        assertTrue(DownloadService.decodes(ClientCapabilities(videoCodecs = listOf("h264")), null))
    }

    @Test
    fun `start answers a file ticket with the url size text subtitles artwork and the page payload`() = rig("start") { service, _, on, _ ->
        val t = service.start(on, DownloadStartRequest(itemId = "film-jf")).ok()
        assertEquals("file", t.kind)
        assertEquals("original", t.quality)
        assertEquals(bytes, t.bytes)
        assertEquals("src-film-jf", t.mediaSourceId)
        assertTrue("/Videos/film-jf/stream?Static=true&MediaSourceId=src-film-jf" in t.url, t.url)
        assertTrue("apikey=jt-on" in t.url, "the viewer's own token, as a direct play's URL carries: ${t.url}")
        assertTrue(t.expiresAt > 0)
        // Every text track, as VTT, with its language and label; the image track (PGS) is not one.
        assertEquals(listOf("eng", "dan"), t.subtitles.map { it.language })
        assertEquals(listOf("English", "Danish"), t.subtitles.map { it.label })
        assertEquals(listOf(false, true), t.subtitles.map { it.forced })
        assertTrue(t.subtitles.all { "/Subtitles/" in it.url && it.url.contains("Stream.vtt") }, t.subtitles.toString())
        // The film page's own payload, verbatim.
        assertEquals("movie", t.itemKind)
        assertNull(t.seriesId)
        assertEquals("film-jf", t.item.jsonObject["card"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(t.artwork.posterUrl!!.startsWith("/api/tv/image/film-jf/poster"), t.artwork.toString())
        assertNull(t.artwork.stillUrl)
    }

    @Test
    fun `an episode's ticket carries its series page and its own still`() = rig("episode") { service, _, on, _ ->
        val t = service.start(on, DownloadStartRequest(itemId = "ep-jf")).ok()
        assertEquals("episode", t.itemKind)
        assertEquals("series-jf", t.seriesId)
        assertEquals("series-jf", t.item.jsonObject["card"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertNotNull(t.artwork.stillUrl)
        assertTrue(t.artwork.stillUrl!!.contains("/still/"), t.artwork.toString())
    }

    @Test
    fun `a second start for the same item mints a fresh url`() = rig("fresh") { service, _, on, _ ->
        val a = service.start(on, DownloadStartRequest(itemId = "film-jf")).ok()
        val b = service.start(on, DownloadStartRequest(itemId = "film-jf")).ok()
        assertNotEquals(a.url, b.url)
        assertEquals(a.bytes, b.bytes)
        assertTrue(b.expiresAt >= a.expiresAt)
    }

    @Test
    fun `a start is never a play — nothing a play sends reaches Jellyfin`() = rig("not-a-play") { service, fake, on, _ ->
        service.start(on, DownloadStartRequest(itemId = "film-jf")).ok()
        fake.lock.withLock {
            val bad = fake.requests.filter { r -> listOf("/Sessions/", "PlaybackInfo", "ActiveEncodings", "/UserData", "POST ", "DELETE ").any { it in r } }
            assertTrue(bad.isEmpty(), "a start sent a play's request to Jellyfin: $bad (all: ${fake.requests})")
        }
    }

    @Test
    fun `an unknown title a series a missing version and a quality not built yet are each refused`() = rig("refusals") { service, _, on, _ ->
        assertEquals(HttpStatusCode.NotFound, service.start(on, DownloadStartRequest(itemId = "nope")).httpStatus())
        assertEquals(HttpStatusCode.NotFound, service.start(on, DownloadStartRequest(itemId = "series-jf")).httpStatus())
        assertEquals(HttpStatusCode.BadRequest, service.start(on, DownloadStartRequest(itemId = "film-jf", mediaSourceId = "other")).httpStatus())
        assertEquals(HttpStatusCode.BadRequest, service.start(on, DownloadStartRequest(itemId = "film-jf", quality = "medium")).httpStatus())
    }

    @Test
    fun `an old config without downloads decodes to false and the server's true round-trips`() {
        val json = Json { ignoreUnknownKeys = true }
        assertFalse(json.decodeFromString(RaviloConfig.serializer(), "{}").downloads)
        assertFalse(json.decodeFromString(RaviloConfig.serializer(), """{"merge_newly_added":true}""").downloads)
        val on = json.decodeFromString(RaviloConfig.serializer(), Json.encodeToString(RaviloConfig.serializer(), RaviloConfig(downloads = true)))
        assertTrue(on.downloads)
        assertEquals(true, json.decodeFromString(RaviloConfig.serializer(), """{"downloads":true}""").downloads)
    }
}
