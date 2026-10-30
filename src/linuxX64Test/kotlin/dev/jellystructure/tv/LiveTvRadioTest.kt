package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionData
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.closeLastDatabaseForTests
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.music.FanartTvClient
import dev.jellystructure.music.MusicLibraryRows
import dev.jellystructure.music.MusicMediaService
import dev.jellystructure.music.MusicStore
import dev.jellystructure.music.MusicTvService
import dev.jellystructure.server.routes.liveTvRoutes
import dev.jellystructure.server.routes.musicTvRoutes
import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.shared.tv.LiveTvGuideProgram
import dev.jellystructure.shared.tv.MusicHome
import dev.jellystructure.tmdb.TmdbClient
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2655 (DEB-2628, FR-1, FR-2, FR-3, FR-9 server half). A fake Jellyfin lists two TV channels and two stations
 * (`ChannelType`), the real [LiveTvService] stores their kind, and the three endpoints send a station only to a client that
 * asked with `radio=1`. [LiveTvTuneTokenTest] owns the per-viewer visibility rules this file only touches once.
 */
class LiveTvRadioTest {
    private val run = getpid().toString()
    private val dir = "/tmp/jellystructure-test-liveradio-$run"
    private val cfgPath = "$dir.toml"
    private val storePath = "$dir-store.json"
    private val dbPath = "$dir.db"
    private val wire = Json { ignoreUnknownKeys = true }

    @AfterTest fun tearDown() {
        closeLastDatabaseForTests()
        for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$s") }
        for (f in listOf(cfgPath, storePath, "$dir-tags.json")) runCatching { platform.posix.remove(f) }
        DeviceIdentityRegistry.clear()
    }

    private val viewer = DeviceData(
        deviceId = "dev-radio-$run", deviceToken = "dt-radio-$run", jellyfinUserId = "u1", jellyfinUsername = "u1",
        jellyfinUserToken = "tok-radio", isAdmin = false, kind = "phone",
    )

    /** Jellyfin lists `tv1`, `tv2`, `r1`, `r2`; `r2` has no guide. [listedForViewer] is what `userId=` answers. */
    private class Fake {
        var channels = """[{"Id":"tv1","Name":"News 1","ChannelType":"TV"},{"Id":"tv2","Name":"Films"},""" +
            """{"Id":"r1","Name":"Station One","ChannelType":"Radio"},{"Id":"r2","Name":"Station Two","ChannelType":"Radio"}]"""
        var listedForViewer = listOf("tv1", "tv2", "r1", "r2")
        /** DEB-2656 — what Jellyfin's `LiveStreams/Open` answers as `Path`; `/up/...` is made absolute against the fake broadcaster below. */
        var openPath = "https://feed.invalid/live/master.m3u8"
    }

    private fun harness(body: suspend (Fake, LiveTvService) -> Unit) = runBlocking {
        DeviceIdentityRegistry.clear()
        val fake = Fake()
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/LiveTv/Channels") {
                    val body = if (call.request.queryParameters["userId"] == null) fake.channels else
                        "[" + fake.listedForViewer.joinToString(",") { """{"Id":"$it","Name":"$it"}""" } + "]"
                    call.respondText("""{"Items":$body}""", ContentType.Application.Json)
                }
                post("/Items/{id}/PlaybackInfo") { call.respondText("""{"PlaySessionId":"ps1","MediaSources":[{"Id":"ms1","OpenToken":"open1"}]}""", ContentType.Application.Json) }
                post("/LiveStreams/Open") {
                    val path = if (fake.openPath.startsWith("/up/")) "http://127.0.0.1:${call.request.local.localPort}${fake.openPath}" else fake.openPath
                    call.respondText("""{"MediaSource":{"Id":"ms1","Path":"$path","LiveStreamId":"ls1"}}""", ContentType.Application.Json)
                }
                get("/up/mp3") {
                    call.response.headers.append("icy-br", "128")
                    call.respondText("RADIO", ContentType("audio", "mpeg"))
                }
                get("/up/live.m3u8") { call.respondText("#EXTM3U\n#EXT-X-VERSION:3\n", ContentType("application", "vnd.apple.mpegurl")) }
                get("/Users/{id}") { call.respondText("{}", ContentType.Application.Json) }
                get("/LiveTv/Programs") {
                    val ids = call.request.queryParameters["ChannelIds"].orEmpty().split(",")
                    val items = ids.filter { it != "r2" }.joinToString(",") {
                        """{"Name":"Show on $it","ChannelId":"$it","StartDate":"2999-01-01T00:00:00Z","EndDate":"2999-01-01T01:00:00Z"}"""
                    }
                    call.respondText("""{"Items":[$items]}""", ContentType.Application.Json)
                }
            }
        }.start(wait = false)
        val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
        val configStore = ConfigStore(cfgPath)
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "server-key")))
        val service = LiveTvService(dir, LiveTvStore(storePath), JellyfinClient(), configStore)
        try {
            body(fake, service)
        } finally {
            server.stop(100, 500)
        }
    }

    private fun ids(l: List<LiveTvChannel>) = l.map { it.channelId }

    @Test fun `a sync stores the kind from ChannelType and a channel with none is tv`() = harness { _, service ->
        service.sync()
        val kinds = service.lineup().associate { it.channelId to it.kind }
        assertEquals(mapOf("tv1" to "tv", "tv2" to "tv", "r1" to "radio", "r2" to "radio"), kinds)
    }

    @Test fun `a later sync rewrites the kind of a channel already stored`() = harness { fake, service ->
        service.sync()
        fake.channels = """[{"Id":"tv1","ChannelType":"Radio"},{"Id":"r1","ChannelType":"TV"}]"""
        service.sync()
        val kinds = service.lineup().filter { !it.unavailable }.associate { it.channelId to it.kind }
        assertEquals(mapOf("tv1" to "radio", "r1" to "tv"), kinds)
    }

    @Test fun `lineup after a restart and before the next sync still says radio`() = harness { _, service ->
        service.sync()
        val restarted = LiveTvService(dir, LiveTvStore(storePath).also { it.load() }, JellyfinClient(), ConfigStore(cfgPath).also { it.load() })
        val kinds = restarted.lineup().associate { it.channelId to it.kind }
        assertEquals("radio", kinds["r1"]); assertEquals("radio", kinds["r2"]); assertEquals("tv", kinds["tv1"])
    }

    @Test fun `a new station arrives hidden and badged new and nothing shows it`() = harness { _, service ->
        service.sync()
        val station = service.lineup().single { it.channelId == "r1" }
        assertFalse(station.shown); assertTrue(station.isNew)
        service.sync()   // a second sync flips nothing either
        assertFalse(service.lineup().single { it.channelId == "r1" }.shown)
        assertEquals(emptyList(), ids(service.homeChannels(viewer, radio = true)), "hidden means hidden, radio or not")
    }

    @Test fun `channels omit stations without radio=1 and include them with it`() = harness { _, service ->
        service.sync(); service.bulkShowNew()
        assertEquals(listOf("tv1", "tv2"), ids(service.homeChannels(viewer)))
        assertEquals(listOf("tv1", "tv2", "r1", "r2"), ids(service.homeChannels(viewer, radio = true)))
    }

    @Test fun `stations follow the channel rules of shown and available and in the viewer's list`() = harness { fake, service ->
        service.sync(); service.bulkShowNew()
        service.updateChannel("r1", shown = false)
        fake.listedForViewer = listOf("tv1", "r1")   // r2 is not Jellyfin-listed for this viewer
        assertEquals(listOf("tv1"), ids(service.homeChannels(viewer, radio = true)))
        service.updateChannel("r1", shown = true)
        assertEquals(listOf("tv1", "r1"), ids(service.homeChannels(viewer, radio = true)))
        fake.channels = """[{"Id":"tv1"},{"Id":"tv2"}]"""; service.sync()   // the stations are gone from Jellyfin
        assertEquals(listOf("tv1"), ids(service.homeChannels(viewer, radio = true)))
    }

    @Test fun `kind filters the channels answer`() = harness { _, service ->
        service.sync(); service.bulkShowNew()
        assertEquals(listOf("r1", "r2"), ids(service.homeChannels(viewer, radio = true, kind = "radio")))
        assertEquals(listOf("tv1", "tv2"), ids(service.homeChannels(viewer, radio = true, kind = "tv")))
        assertEquals(emptyList(), ids(service.homeChannels(viewer, radio = false, kind = "radio")), "asking for radio without radio=1 gets none")
    }

    @Test fun `the admin lineup always sees every station`() = harness { _, service ->
        service.sync()
        assertEquals(setOf("tv1", "tv2", "r1", "r2"), ids(service.lineup()).toSet())
    }

    private val caps = dev.jellystructure.shared.tv.ClientCapabilities()

    @Test fun `a station tune gives stream_kind audio with its content type and a relay path`() = harness { fake, service ->
        service.sync(); service.bulkShowNew()
        fake.openPath = "/up/mp3"
        val ticket = assertNotNull(service.tune(viewer, "r1", caps))
        assertEquals("audio", ticket.streamKind)
        assertEquals("audio/mpeg", ticket.contentType)
        assertEquals("radio", ticket.channelKind)
        assertTrue(Regex("/api/tv/livetv/radio/[0-9a-f]{32}").matches(ticket.hlsUrl), "a path, never an absolute URL: ${ticket.hlsUrl.take(30)}")
    }

    @Test fun `an https HLS station is handed over as-is`() = harness { fake, service ->
        service.sync(); service.bulkShowNew()
        fake.openPath = "https://feed.invalid/live/master.m3u8"
        val ticket = assertNotNull(service.tune(viewer, "r1", caps))
        assertEquals("https://feed.invalid/live/master.m3u8", ticket.hlsUrl)
        assertEquals("hls", ticket.streamKind)
        assertEquals("radio", ticket.channelKind)
        assertNull(ticket.contentType)
    }

    @Test fun `an http HLS station is relayed and a TV channel keeps the plain ticket`() = harness { fake, service ->
        service.sync(); service.bulkShowNew()
        fake.openPath = "/up/live.m3u8"
        val http = assertNotNull(service.tune(viewer, "r1", caps))
        assertEquals("audio", http.streamKind, "plain http HLS is not playable everywhere, so it goes through the relay")
        assertTrue(http.hlsUrl.startsWith("/api/tv/livetv/radio/"))
        val tv = assertNotNull(service.tune(viewer, "tv1", caps))
        assertEquals("tv", tv.channelKind)
        assertNull(tv.contentType)
        assertTrue(tv.streamKind != "audio" && tv.hlsUrl.startsWith("http"))
    }

    // DEB-3039 (FR-1 as amended) — Jellyfin 12.1.0 lists every channel as TV, so the operator sets the kind.
    private val allTv = """[{"Id":"tv1","Name":"News 1","ChannelType":"TV"},{"Id":"r1","Name":"Station One","ChannelType":"TV"},{"Id":"r2","Name":"Station Two","ChannelType":"TV"}]"""

    @Test fun `a Jellyfin that lists every channel as TV leaves all tv until the operator says otherwise`() = harness { fake, service ->
        fake.channels = allTv
        service.sync(); service.bulkShowNew()
        assertEquals(setOf("tv"), service.lineup().map { it.kind }.toSet())
        assertEquals(listOf("tv1", "r1", "r2"), ids(service.homeChannels(viewer, radio = true)))
        assertTrue(service.updateChannel("r1", kind = "radio"))
        assertEquals(listOf("r1"), ids(service.homeChannels(viewer, radio = true, kind = "radio")))
        assertEquals(listOf("tv1", "r2"), ids(service.homeChannels(viewer, radio = false)), "an old client no longer sees the station")
    }

    @Test fun `an operator kind survives a sync and a Jellyfin that says otherwise and a restart`() = harness { fake, service ->
        fake.channels = allTv
        service.sync()
        service.updateChannel("r1", kind = "radio")
        service.updateChannel("tv1", kind = "tv")
        fake.channels = """[{"Id":"tv1","ChannelType":"Radio"},{"Id":"r1","ChannelType":"TV"},{"Id":"r2","ChannelType":"Radio"}]"""
        service.sync()
        val kinds = service.lineup().associate { it.channelId to it.kind }
        assertEquals(mapOf("tv1" to "tv", "r1" to "radio", "r2" to "radio"), kinds, "r2 was never set, so it still follows Jellyfin")
        val restarted = LiveTvService(dir, LiveTvStore(storePath).also { it.load() }, JellyfinClient(), ConfigStore(cfgPath).also { it.load() })
        assertEquals(kinds, restarted.lineup().associate { it.channelId to it.kind })
        fake.channels = """[{"Id":"tv1"}]"""; service.sync()   // r1 leaves Jellyfin's lineup and comes back
        fake.channels = allTv; service.sync()
        assertEquals("radio", service.lineup().single { it.channelId == "r1" }.kind)
    }

    @Test fun `any other edit leaves the kind unset so a sync still seeds it`() = harness { fake, service ->
        fake.channels = allTv
        service.sync()
        service.updateChannel("r1", shown = true)
        fake.channels = """[{"Id":"tv1"},{"Id":"r1","ChannelType":"Radio"},{"Id":"r2"}]"""
        service.sync()
        assertEquals("radio", service.lineup().single { it.channelId == "r1" }.kind)
    }

    @Test fun `the admin PUT sets the kind and an unknown kind is a 400`() = harness { fake, service ->
        fake.channels = allTv
        service.sync()
        testApplication {
            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                intercept(ApplicationCallPipeline.Plugins) {
                    call.attributes.put(SessionKey, SessionData("t", "u-root", "root", "jf", Long.MAX_VALUE))
                }
                routing { route("/api") { liveTvRoutes(service) } }
            }
            suspend fun put(body: String) = client.put("/api/tv/admin/livetv/channels/r1") { contentType(ContentType.Application.Json); setBody(body) }
            assertEquals(HttpStatusCode.OK, put("""{"kind":"radio"}""").status)
            assertEquals("radio", service.lineup().single { it.channelId == "r1" }.kind)
            assertEquals(HttpStatusCode.BadRequest, put("""{"kind":"podcast"}""").status)
            assertEquals("radio", service.lineup().single { it.channelId == "r1" }.kind)
        }
    }

    /** The real routes over the real services; the music side has one album so Music Home has rows. */
    private fun routes(service: LiveTvService, block: suspend (HttpClient) -> Unit) = testApplication {
        val cs = ConfigStore(cfgPath).also { it.load() }
        val db = createDatabase(dbPath)
        val jf = JellyfinClient()
        val store = MusicStore(db)
        val hl = MusicCredit("x", "Harbour Lights")
        store.replaceLibrary(MusicLibraryRows(
            "liba", listOf(MusicArtist(id = "x", libraryId = "liba", name = "Harbour Lights", path = "/m/x")),
            listOf(MusicAlbum(id = "a1", libraryId = "liba", title = "Album 1", albumArtists = listOf(hl), genres = listOf("folk"), coverState = MusicArt.NONE, addedAt = 1)),
            listOf(MusicTrack(id = "t1", albumId = "a1", libraryId = "liba", title = "Song", position = 1, artists = listOf(hl))),
        ))
        val music = MusicTvService(store, { null }, jf, cs, stations = { d -> service.homeChannels(d, radio = true, kind = "radio") })
        val media = MediaStore(db, JsTagStore("$dir-tags.json"), cs)
        val playback = PlaybackService(media, jf, cs, PlaybackQoeStore(db), PlaybackStartSampleStore(db), RaviloDeviceService(db))
        val musicMedia = MusicMediaService(store, ArtworkDownloader(TmdbClient(cs), Screengrabber()), cs, jf, FanartTvClient { "" })
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }   // the server's own: defaults are not written
            routing {
                route("/api") {
                    intercept(ApplicationCallPipeline.Plugins) { call.attributes.put(DeviceKey, viewer) }
                    liveTvRoutes(service)
                    musicTvRoutes(music, playback, jf, cs, store, musicMedia, null)
                }
            }
        }
        block(client)
    }

    @Test fun `the three endpoints answer an old client with no station and a radio client with them`() = harness { _, service ->
        service.sync(); service.bulkShowNew()
        routes(service) { c ->
            suspend fun channels(q: String) = wire.decodeFromString<List<LiveTvChannel>>(c.get("/api/tv/livetv/channels$q").bodyAsText())
            suspend fun guide(q: String) = wire.decodeFromString<List<LiveTvGuideProgram>>(c.get("/api/tv/livetv/guide$q").bodyAsText())
            suspend fun home(q: String) = wire.decodeFromString<MusicHome>(c.get("/api/tv/music/home$q").bodyAsText())

            assertEquals(listOf("tv1", "tv2"), ids(channels("")))
            // Not one new byte for an old client: a default `kind` and an empty `stations` are not written (the server's Json omits defaults).
            val oldBody = c.get("/api/tv/livetv/channels").bodyAsText()
            assertFalse("\"kind\"" in oldBody, oldBody)
            assertFalse("\"stations\"" in c.get("/api/tv/music/home").bodyAsText())
            assertTrue("\"kind\":\"radio\"" in c.get("/api/tv/livetv/channels?radio=1&kind=radio").bodyAsText())
            assertEquals(listOf("tv1", "tv2"), ids(channels("?radio=0")))
            assertEquals(listOf("tv1", "tv2", "r1", "r2"), ids(channels("?radio=1")))
            assertEquals(listOf("r1", "r2"), ids(channels("?radio=1&kind=radio")))
            assertEquals(listOf("tv1", "tv2"), ids(channels("?radio=1&kind=tv")))

            assertEquals(setOf("tv1", "tv2"), guide("").map { it.channelId }.toSet())
            assertEquals(setOf("tv1", "tv2", "r1"), guide("?radio=1").map { it.channelId }.toSet(), "r2 has no guide to send")

            assertTrue(home("").rows.none { it.key == "radio" }, "no Radio row without radio=1")
            val radio = home("?radio=1").rows
            assertEquals("radio", radio.last().key)
            assertEquals("Radio", radio.last().title)
            assertEquals(listOf("r1", "r2"), ids(radio.last().stations))
            assertTrue(radio.last().stations.all { it.kind == "radio" })
            assertEquals(radio.dropLast(1).map { it.key }, home("").rows.map { it.key }, "the other rows are the same as before")
        }
    }

    @Test fun `the Radio row is absent when the viewer has no station`() = harness { fake, service ->
        service.sync(); service.bulkShowNew()
        fake.listedForViewer = listOf("tv1", "tv2")
        routes(service) { c ->
            val home = wire.decodeFromString<MusicHome>(c.get("/api/tv/music/home?radio=1").bodyAsText())
            assertTrue(home.rows.isNotEmpty() && home.rows.none { it.key == "radio" })
        }
    }
}
