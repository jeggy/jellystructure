package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.db.createDatabase
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.torrent.SeedingSnapshot
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.concurrent.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-1672, FR-145-7 — `runPipeline` over a fake Jellyfin that records every POST it is sent. A
 * `RunTarget.SingleItem` run (a title that arrived by webhook) ends with one `POST /Items/{id}/Refresh` and
 * nothing else; a `RunTarget.Library` run still ends with the whole-library refresh and the `scan_complete`
 * webhook (the same `signalPipelineComplete` that broadcasts `Finished`).
 */
class PipelineSingleItemSignalTest {
    private val dir = "/tmp/jellystructure-test-pipeline-signal-${getpid()}"
    private val posts = AtomicReference(listOf<String>())
    private fun record(entry: String) {
        while (true) {
            val before = posts.value
            if (posts.compareAndSet(before, before + entry)) return
        }
    }

    private val jellyfin = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            post("/Library/Refresh") { record("POST ${call.request.uri}"); call.respondText("", status = HttpStatusCode.NoContent) }
            post("/Items/{id}/Refresh") { record("POST ${call.request.uri}"); call.respondText("", status = HttpStatusCode.NoContent) }
            post("/hook") { record("WEBHOOK"); call.respondText("ok") }
        }
    }.also { it.start(wait = false) }

    private lateinit var db: dev.jellystructure.db.JellystructureDb
    private lateinit var store: MediaStore
    private lateinit var deps: PipelineDeps
    private lateinit var configStore: ConfigStore

    @BeforeTest
    fun setUp() {
        platform.posix.system("rm -rf '$dir'; mkdir -p '$dir'")
        val base = "http://127.0.0.1:${runBlocking { jellyfin.engine.resolvedConnectors().first().port }}"
        db = createDatabase("$dir/test.db")
        configStore = ConfigStore("$dir/config.toml")
        runBlocking {
            configStore.update(
                configStore.current.copy(
                    apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "token"),
                    behavior = configStore.current.behavior.copy(notificationsWebhook = "$base/hook", notifyOnScanDone = true),
                ),
            )
        }
        val jsTags = JsTagStore("$dir/tags.json")
        store = MediaStore(db, jsTags, configStore)
        val jf = JellyfinClient()
        val broadcaster = WsBroadcaster()
        val history = MediaHistory(db)
        val segments = MediaSegmentStore(db)
        val scanner = Scanner(configStore, TmdbClient(configStore), jf, jsTags, store = store)
        val artwork = ArtworkDownloader(TmdbClient(configStore), Screengrabber())
        val seeding = SeedingGuard(SeedingSnapshot(configStore, QBittorrentClient()))
        val scope = CoroutineScope(Dispatchers.Default)
        val queue = MediaJobQueue(db, store, broadcaster, jf, configStore, history, seeding, null, scope)
        val dirty = DirtyItemStore(db)
        val ingest = RealtimeIngestService(scanner, store, jf, configStore, artwork, scope, broadcaster, history, null, null, null, queue, db, segments, dirty)
        deps = PipelineDeps(
            store = store, scanner = scanner, broadcaster = broadcaster, configStore = configStore, jellyfinClient = jf,
            scanDispatcher = Dispatchers.Default, artworkDownloader = artwork, mediaSegmentStore = segments,
            mediaJobQueue = queue, realtimeIngest = ingest, mediaHistory = history, dirtyItemStore = dirty,
        )
    }

    @AfterTest
    fun tearDown() {
        jellyfin.stop(0, 0)
        dev.jellystructure.db.closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private fun title(kind: MediaKind, jellyfinId: String, nfoWrittenAt: Long? = null) = MediaItem(
        id = "t-$jellyfinId", title = "Title $jellyfinId", year = 2020, kind = kind, path = "$dir/$jellyfinId",
        jellyfinId = jellyfinId, tmdbId = null, originalLanguage = "en", posterPath = null, overview = null,
        issueCount = 0, scannedAt = 0L, tracks = emptyList(), nfoWrittenAt = nfoWrittenAt,
    )

    private fun run(target: RunTarget, pipeline: List<PipelineStep>) = runBlocking {
        runPipeline(target, pipeline, "job-1", ScanTracker(db, persistToDb = false), deps)
    }

    private val scanOnly = listOf(PipelineStep(step = "scan_files"))

    @Test
    fun `a single series run posts one recursive item refresh and no library refresh`() {
        val series = title(MediaKind.TV_SHOW, "jf-series").also { runBlocking { store.addOrUpdate(it) } }
        run(RunTarget.SingleItem(series), scanOnly)
        val sent = posts.value
        assertEquals(1, sent.size, "exactly one call: $sent")
        assertTrue(sent[0].startsWith("POST /Items/jf-series/Refresh?"), sent[0])
        assertTrue("Recursive=true" in sent[0], "a series refreshes its children: ${sent[0]}")
        assertTrue(sent.none { it.contains("/Library/Refresh") || it == "WEBHOOK" }, "no library scan and no scan_complete: $sent")
    }

    @Test
    fun `a single film run posts one non recursive item refresh`() {
        val film = title(MediaKind.MOVIE, "jf-film").also { runBlocking { store.addOrUpdate(it) } }
        run(RunTarget.SingleItem(film), scanOnly)
        val sent = posts.value
        assertEquals(1, sent.size, "exactly one call: $sent")
        assertTrue(sent[0].startsWith("POST /Items/jf-film/Refresh?") && "Recursive" !in sent[0], sent[0])
    }

    @Test
    fun `a title sync_jellyfin already refreshed is not refreshed a second time`() {
        val film = title(MediaKind.MOVIE, "jf-synced", nfoWrittenAt = 100L)
        runBlocking { store.addOrUpdate(film); store.updateOne(film) }   // addOrUpdate does not carry nfoWrittenAt
        run(RunTarget.SingleItem(film), listOf(PipelineStep(step = "scan_files"), PipelineStep(step = "sync_jellyfin")))
        val sent = posts.value
        assertEquals(1, runBlocking { store.get(film.id) }?.jfSyncedAt?.let { 1 } ?: 0, "sync_jellyfin ran for the title")
        assertEquals(1, sent.count { it.startsWith("POST /Items/jf-synced/Refresh?") }, "refreshed once, by sync_jellyfin: $sent")
        assertEquals(1, sent.size, "$sent")
    }

    @Test
    fun `a library run still ends with the library refresh and the scan_complete webhook`() {
        run(RunTarget.Library(), scanOnly)
        val sent = posts.value
        assertEquals(1, sent.count { it == "POST /Library/Refresh" }, "$sent")
        assertEquals(1, sent.count { it == "WEBHOOK" }, "the scan_complete webhook fires with Finished: $sent")
        assertTrue(sent.none { it.contains("/Items/") }, "a library run refreshes no single item: $sent")
    }
}
