package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionData
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.DirtyItemStore
import dev.jellystructure.media.FingerprintService
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.LogoDownloader
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaJobQueue
import dev.jellystructure.media.MediaSegmentStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.RealtimeIngestService
import dev.jellystructure.media.ScanTracker
import dev.jellystructure.media.Scanner
import dev.jellystructure.media.Screengrabber
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.torrent.SeedingSnapshot
import dev.jellystructure.tv.RaviloConfigService
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * DEB-5540 (FR-1, FR-2, FR-3; Acceptance 1) — an admin save refreshes its title, not the Jellyfin library. The real
 * `mediaRoutes` and `trackRoutes` against a fake Jellyfin that records every request it is sent. On the real thing a
 * `POST /Library/Refresh` cancels a running scan, so the assertion that matters is the absence of one.
 */
class AdminSaveRefreshTest {
    private val dir = "/tmp/jellystructure-test-admin-save-refresh-${getpid()}"
    private val calls = kotlin.concurrent.AtomicReference<List<String>>(emptyList())
    private fun record(line: String) { while (true) { val cur = calls.value; if (calls.compareAndSet(cur, cur + line)) return } }
    @kotlin.concurrent.Volatile private var scanRunning = false

    private val fake = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            post("/Items/{id}/Refresh") {
                record("POST /Items/${call.parameters["id"]}/Refresh?${call.request.uri.substringAfter('?', "")}")
                call.respondText("", status = HttpStatusCode.NoContent)
            }
            post("/Library/Refresh") {
                record("POST /Library/Refresh")
                call.respondText("", status = HttpStatusCode.NoContent)
            }
            get("/ScheduledTasks") {
                call.respondText(
                    """[{"Id":"t1","Key":"RefreshLibrary","Name":"Scan Media Library","State":"${if (scanRunning) "Running" else "Idle"}"}]""",
                    io.ktor.http.ContentType.Application.Json,
                )
            }
        }
    }.start(wait = false)
    private val jellyfinUrl = "http://127.0.0.1:${runBlocking { fake.engine.resolvedConnectors().first().port }}"

    private lateinit var scope: CoroutineScope

    @BeforeTest fun setUp() {
        SystemFileSystem.createDirectories(Path("$dir/Show (2020)"))
        SystemFileSystem.createDirectories(Path("$dir/Film (2020)"))
        scope = CoroutineScope(Dispatchers.Default)
    }

    @AfterTest fun tearDown() {
        fake.stop(0, 0)
        scope.cancel()
        dev.jellystructure.db.closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private fun item(id: String, kind: MediaKind, jellyfinId: String?): MediaItem = MediaItem(
        id = id, title = id, year = 2020, kind = kind,
        path = if (kind == MediaKind.TV_SHOW) "$dir/Show (2020)" else "$dir/Film (2020)/Film (2020).mkv",
        tmdbId = null, originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L,
        jellyfinId = jellyfinId, tracks = emptyList(),
    )

    private fun requests() = calls.value

    private fun app(
        debounceMs: Long = 60_000,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.(MediaStore, JellyfinClient, ConfigStore) -> Unit,
    ) = testApplication {
        val db = createDatabase("$dir/test.db")
        val configStore = ConfigStore("$dir/config.toml")
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = jellyfinUrl, jellyfinToken = "tok")))
        val jellyfin = JellyfinClient(libraryRefreshDebounce = debounceMs.milliseconds, libraryRefreshScanPoll = 50.milliseconds)
        val store = MediaStore(db, JsTagStore("$dir/tags.json"), configStore)
        val history = MediaHistory(db)
        val broadcaster = WsBroadcaster()
        val seeding = SeedingGuard(SeedingSnapshot(configStore, QBittorrentClient()))
        val tmdb = TmdbClient(configStore)
        val artwork = ArtworkDownloader(tmdb, Screengrabber())
        val segments = MediaSegmentStore(db)
        val queue = MediaJobQueue(db, store, broadcaster, jellyfin, configStore, history, seeding, null, scope)
        val scanner = Scanner(configStore, tmdb, jellyfin, JsTagStore("$dir/tags2.json"), store = store)
        val imdb = dev.jellystructure.imdb.ImdbClient(dir)
        val dirty = DirtyItemStore(db)
        val ingest = RealtimeIngestService(scanner, store, jellyfin, configStore, artwork, scope, broadcaster, history, null, null, imdb, queue, db, segments, dirty)
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(SessionKey, SessionData("t", "u-root", "root", "jf", Long.MAX_VALUE))
            }
            routing {
                route("/api") {
                    mediaRoutes(
                        store, scanner, artwork, tmdb, scope, ScanTracker(db), broadcaster, jellyfin, configStore, history,
                        Dispatchers.Default, seeding, SeedingSnapshot(configStore, QBittorrentClient()), RaviloConfigService(db),
                        LogoDownloader(dir, tmdb), mediaJobQueue = queue, imdbClient = imdb, fingerprintService = FingerprintService(dir),
                        mediaSegmentStore = segments, realtimeIngest = ingest, dirtyItemStore = dirty,
                    )
                    trackRoutes(store, configStore, jellyfin, history, seeding, null, scope, broadcaster, queue)
                }
            }
        }
        block(store, jellyfin, configStore)
    }

    @Test
    fun `Save to Jellyfin on a series is one recursive item refresh and no library refresh`() = app { store, _, _ ->
        runBlocking { store.addOrUpdate(item("show", MediaKind.TV_SHOW, "jf-show")) }
        // The admin's chain, as MediaDetail sends it: NFO and artwork without their own refresh, then the one refresh.
        assertEquals(HttpStatusCode.OK, client.post("/api/media/show/nfo?refresh=false").status)
        client.post("/api/media/show/artwork?refresh=false")
        assertEquals(HttpStatusCode.OK, client.post("/api/media/show/jellyfin-refresh").status)
        val sent = requests()
        assertEquals(1, sent.size, sent.toString())
        assertTrue(sent.single().startsWith("POST /Items/jf-show/Refresh?"), sent.toString())
        assertTrue("Recursive=true" in sent.single() && "MetadataRefreshMode=FullRefresh" in sent.single() && "ImageRefreshMode=FullRefresh" in sent.single(), sent.toString())
        assertTrue(sent.none { "/Library/Refresh" in it }, sent.toString())
    }

    @Test
    fun `Save to Jellyfin on a film is one item refresh and not recursive`() = app { store, _, _ ->
        runBlocking { store.addOrUpdate(item("film", MediaKind.MOVIE, "jf-film")) }
        client.post("/api/media/film/nfo?refresh=false")
        client.post("/api/media/film/artwork?refresh=false")
        client.post("/api/media/film/jellyfin-refresh")
        val sent = requests()
        assertEquals(1, sent.size, sent.toString())
        assertTrue(sent.single().startsWith("POST /Items/jf-film/Refresh?") && "Recursive" !in sent.single(), sent.toString())
    }

    @Test
    fun `an NFO write on its own still refreshes its series once and recursively`() = app { store, _, _ ->
        runBlocking { store.addOrUpdate(item("show", MediaKind.TV_SHOW, "jf-show")) }
        client.post("/api/media/show/nfo")
        val sent = requests()
        assertEquals(1, sent.size, sent.toString())
        assertTrue("Recursive=true" in sent.single(), sent.toString())
    }

    @Test
    fun `a title Jellyfin has no id for waits on one coalesced library refresh and none while a scan runs`() = app(debounceMs = 300) { store, _, _ ->
        runBlocking { store.addOrUpdate(item("show", MediaKind.TV_SHOW, null)) }
        scanRunning = true
        repeat(3) { client.post("/api/media/show/jellyfin-refresh"); delay(50.milliseconds) }
        delay(900.milliseconds)
        assertEquals(emptyList(), requests(), "a scan is running: nothing is sent")
        scanRunning = false
        delay(600.milliseconds)
        assertEquals(listOf("POST /Library/Refresh"), requests())
        delay(700.milliseconds)
        assertEquals(1, requests().size, "once")
    }
}
