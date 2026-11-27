package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionData
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.config.ProcessingFeatures
import dev.jellystructure.db.createDatabase
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.server.routes.triageRoutes
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.torrent.SeedingSnapshot
import io.ktor.client.plugins.websocket.webSocket as clientWebSocket
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.getpid
import kotlin.concurrent.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-5709 (FR-1..FR-4) — a failed or implausible read of Jellyfin never hides the library. A Jellyfin that answers `/Items` the way the test
 * says (503, an unparseable body, an empty list, a list of ids), a store holding 23 stand-in titles, and the real pipeline, client, store and
 * triage routes in between. Stand-in ids only.
 */
class JellyfinReadFailureTest {
    private val dir = "/tmp/jellystructure-test-read-failure-${getpid()}"
    private val json = Json { ignoreUnknownKeys = true }

    /** What the fake answers `GET /Items` with: "503", "garbage", or a comma list of film ids ("" is a good, empty answer). */
    private val listing = AtomicReference("")
    private val posts = AtomicReference(listOf<String>())
    private fun recordPost(entry: String) { while (true) { val b = posts.value; if (posts.compareAndSet(b, b + entry)) return } }
    private fun answer(ids: Collection<String>) { listing.value = ids.joinToString(",") }

    private val jellyfin = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            get("/Items") {
                when (val mode = listing.value) {
                    "503" -> call.respondText("starting up", status = HttpStatusCode.ServiceUnavailable)
                    "garbage" -> call.respondText("<html>not json</html>", ContentType.Application.Json)
                    else -> call.respondText(
                        """{"Items":[${mode.split(',').filter { it.isNotBlank() }.joinToString(",") { """{"Id":"$it","Name":"n-$it","Type":"Movie"}""" }}],"TotalRecordCount":0,"StartIndex":0}""",
                        ContentType.Application.Json,
                    )
                }
            }
            post("/Library/Refresh") { recordPost("POST ${call.request.uri}"); call.respondText("", status = HttpStatusCode.NoContent) }
        }
    }.also { it.start(wait = false) }

    private lateinit var base: String
    private lateinit var db: dev.jellystructure.db.JellystructureDb
    private lateinit var store: MediaStore
    private lateinit var deps: PipelineDeps
    private lateinit var configStore: ConfigStore
    private lateinit var activity: ActivityLog
    private lateinit var runs: ProcessingRuns
    private val broadcaster = WsBroadcaster()
    private val jf = JellyfinClient()

    @BeforeTest
    fun setUp() {
        platform.posix.system("rm -rf '$dir'; mkdir -p '$dir'")
        base = "http://127.0.0.1:${runBlocking { jellyfin.engine.resolvedConnectors().first().port }}"
        db = createDatabase("$dir/test.db")
        configStore = ConfigStore("$dir/config.toml")
        runBlocking {
            configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "token")))
        }
        val jsTags = JsTagStore("$dir/tags.json")
        store = MediaStore(db, jsTags, configStore)
        runBlocking { for (n in 1..23) store.addOrUpdate(title(n)) }
        val scanner = Scanner(configStore, TmdbClient(configStore), jf, jsTags, store = store)
        val artwork = ArtworkDownloader(TmdbClient(configStore), Screengrabber())
        val seeding = SeedingGuard(SeedingSnapshot(configStore, QBittorrentClient()))
        val scope = CoroutineScope(Dispatchers.Default)
        val queue = MediaJobQueue(db, store, broadcaster, jf, configStore, MediaHistory(db), seeding, null, scope)
        val segments = MediaSegmentStore(db)
        val dirty = DirtyItemStore(db)
        val ingest = RealtimeIngestService(scanner, store, jf, configStore, artwork, scope, broadcaster, MediaHistory(db), null, null, null, queue, db, segments, dirty)
        runs = ProcessingRuns(db)
        deps = PipelineDeps(
            store = store, scanner = scanner, broadcaster = broadcaster, configStore = configStore, jellyfinClient = jf,
            scanDispatcher = Dispatchers.Default, artworkDownloader = artwork, mediaSegmentStore = segments,
            mediaJobQueue = queue, realtimeIngest = ingest, mediaHistory = MediaHistory(db), dirtyItemStore = dirty, processingRuns = runs,
        )
        activity = ActivityLog("$dir/activity.json", broadcaster, scope)
        Logger.activityLog = activity
        LibraryHold.set(null)
        FileDamage.service = null   // another test's service is bound to a database it closed: the count route reads it
    }

    @AfterTest
    fun tearDown() {
        Logger.activityLog = null
        LibraryHold.set(null)
        jellyfin.stop(0, 0)
        dev.jellystructure.db.closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private fun id(n: Int) = "jf-$n"

    private fun title(n: Int) = MediaItem(
        id = "t-$n", title = "Title $n", year = 2020, kind = MediaKind.MOVIE, path = "$dir/$n", jellyfinId = id(n), tmdbId = null,
        originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = emptyList(),
    )

    private fun flagged() = runBlocking { store.allItems().filter { it.missingFromSource }.map { it.jellyfinId!! }.toSet() }
    private fun errorLines() = runBlocking { activity.list(1, 100, "scan", "ERROR").entries.map { it.message } }
    private fun allLines() = runBlocking { activity.list(1, 100, "scan", null).entries.map { it.message } }
    private fun ids(range: IntRange) = range.map(::id)

    // ── FR-1 — a failed read is null, never an empty list ──────────────────────────────────────────────────────────

    @Test
    fun `getItems answers null for a 503 an unparseable body and a refused connection`() = runBlocking {
        listing.value = "503"
        assertNull(jf.getItems(base, "token"), "a 503")
        listing.value = "garbage"
        assertNull(jf.getItems(base, "token"), "a body that does not parse")
        assertNull(jf.getItems("http://127.0.0.1:1", "token"), "a refused connection")
    }

    @Test
    fun `getItems answers an empty list for a good read that lists nothing and the titles for one that lists some`() = runBlocking {
        answer(emptyList())
        assertEquals(emptyList(), jf.getItems(base, "token")!!.map { it.id })
        answer(ids(1..3))
        assertEquals(ids(1..3), jf.getItems(base, "token")!!.map { it.id })
    }

    // ── FR-2 — a full scan whose read failed changes no flag, says so and ends as failed ───────────────────────────

    /** Runs a full library pipeline with a websocket client attached, and answers the `finished` event's `failed` count. */
    private fun runWatched(): Int = runBlocking {
        var failed = -1
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/events") {
                    broadcaster.register(this)
                    send(Frame.Text("ready"))
                    try { for (f in incoming) { /* nothing */ } } finally { broadcaster.unregister(this) }
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val ws = HttpClient(Curl) { install(io.ktor.client.plugins.websocket.WebSockets) }
        try {
            ws.clientWebSocket("ws://127.0.0.1:$port/events") {
                assertEquals("ready", (incoming.receive() as Frame.Text).readText())
                runPipeline(RunTarget.Library(), listOf(PipelineStep(step = "scan_files")), "job-1", ScanTracker(db, persistToDb = false), deps)
                withTimeout(10_000) {
                    while (true) {
                        val o = json.parseToJsonElement((incoming.receive() as Frame.Text).readText()) as JsonObject
                        if (o["type"]?.jsonPrimitive?.content == "finished") { failed = o["failed"]!!.jsonPrimitive.content.toInt(); break }
                    }
                }
            }
        } finally {
            ws.close()
            server.stop(0, 0)
        }
        failed
    }

    @Test
    fun `a full scan whose read failed flags none of 23 titles says so ends with one failure and does not record Find`() {
        listing.value = "503"
        val failed = runWatched()
        assertEquals(emptySet(), flagged(), "no title is flagged")
        assertEquals(23, runBlocking { store.liveItems().size }, "every title is still visible")
        assertEquals(1, failed, "the run's Finished counts one failure")
        assertTrue(JELLYFIN_READ_FAILED_SENTENCE in errorLines(), "an error line in category scan: ${errorLines()}")
        assertNull(runs.all()[ProcessingFeatures.FIND], "Find's last run keeps its value, so a boot catch-up is still due")
        assertEquals(emptyList(), posts.value, "no whole-library refresh after a scan that read nothing: ${posts.value}")
        assertNull(LibraryHold.current, "a failed read is not a hold")
    }

    @Test
    fun `the same scan with a good read records Find and ends clean`() {
        answer(ids(1..23))
        val failed = runWatched()
        assertEquals(0, failed)
        assertNotNull(runs.all()[ProcessingFeatures.FIND], "a scan that read Jellyfin records Find")
        assertEquals(emptySet(), flagged())
    }

    // ── FR-3 — one scan never flags more than half the library ─────────────────────────────────────────────────────

    private fun reconcile(listed: Collection<String>) = runBlocking { store.reconcileWithSource(listed.toSet(), 1_000L) }

    @Test
    fun `a read of none or of 11 of 23 is held back and a read of 12 flags the 11 that are gone`() {
        reconcile(emptyList()).also { assertEquals(HeldBack(1_000L, 23, 23), it.heldBack) }
        assertEquals(emptySet(), flagged(), "a read of 0 flags nothing")
        reconcile(ids(1..11)).also { assertEquals(HeldBack(1_000L, 12, 23), it.heldBack) }
        assertEquals(emptySet(), flagged(), "a read of 11 flags nothing")
        val ok = reconcile(ids(1..12))
        assertNull(ok.heldBack)
        assertEquals(ids(13..23).toSet(), ok.newlyMissing.map { it.jellyfinId!! }.toSet())
        assertEquals(ids(13..23).toSet(), flagged())
    }

    @Test
    fun `with one title held its disappearance is held back`() {
        runBlocking { store.flagMissingFromSource(setOf(id(1)), 5L) }   // only jf-1 listed: the other 22 are flagged, one is held
        val r = reconcile(emptyList())
        assertEquals(HeldBack(1_000L, 1, 1), r.heldBack)
        assertEquals(ids(2..23).toSet(), flagged(), "the one held title was not flagged")
    }

    @Test
    fun `a flagged title the held back read lists comes back and the others stay as they were`() {
        runBlocking { store.flagMissingFromSource(ids(1..23).drop(1).toSet(), 5L) }   // only jf-2..jf-23 listed → jf-1 flagged
        assertEquals(setOf(id(1)), flagged())
        val r = reconcile(emptyList())   // held 22, all gone → held back
        assertEquals(HeldBack(1_000L, 22, 22), r.heldBack)
        assertEquals(setOf(id(1)), flagged(), "nothing newly flagged")
        reconcile(listOf(id(1)))   // jf-1 listed again, 22 others gone of 22 held → held back, but jf-1 is back
        assertEquals(emptySet(), flagged(), "a title that returns always comes back")
    }

    @Test
    fun `a scan that holds back writes the error line and sets the hold and the next normal scan clears it`() {
        answer(emptyList())
        runWatched()
        assertEquals(emptySet(), flagged())
        val hold = assertNotNull(LibraryHold.current)
        assertEquals(23, hold.gone); assertEquals(23, hold.held)
        assertTrue(heldBackSentence(23, 23) in errorLines(), "${errorLines()}")
        answer(ids(1..23))
        runWatched()
        assertNull(LibraryHold.current, "a plausible read clears the hold")
    }

    // ── FR-4 — the hold is visible and released by hand ────────────────────────────────────────────────────────────

    private fun routes(block: suspend io.ktor.client.HttpClient.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(json) }
            intercept(ApplicationCallPipeline.Plugins) { call.attributes.put(SessionKey, SessionData("t", "u-root", "root", "jf", Long.MAX_VALUE)) }
            routing {
                route("/api") {
                    val guard = SeedingGuard(SeedingSnapshot(configStore, QBittorrentClient()))
                    triageRoutes(store, jf, configStore, MediaHistory(db), guard, MediaSegmentStore(db))
                }
            }
        }
        runBlocking { client.block() }
    }

    private suspend fun io.ktor.client.HttpClient.heldBack(): JsonObject? {
        val r = get("/api/triage/count")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return (json.parseToJsonElement(r.bodyAsText()) as JsonObject)["heldBack"]?.takeIf { it is JsonObject }?.jsonObject
    }

    @Test
    fun `the count carries heldBack after a held back scan and drops it after a normal one`() = routes {
        assertNull(heldBack())
        LibraryHold.set(reconcile(emptyList()).heldBack)
        val h = assertNotNull(heldBack())
        assertEquals(listOf(23, 23), listOf(h["gone"]!!.jsonPrimitive.content.toInt(), h["held"]!!.jsonPrimitive.content.toInt()))
        assertEquals(1_000L, h["at"]!!.jsonPrimitive.content.toLong())
        LibraryHold.set(reconcile(ids(1..23)).heldBack)
        assertNull(heldBack())
    }

    @Test
    fun `the flag route re-reads Jellyfin flags what it does not list without the floor and clears the hold`() = routes {
        LibraryHold.set(HeldBack(1_000L, 23, 23))
        answer(ids(1..5))   // 18 of 23 gone: far over half, and the hand-flag does not mind
        val r = post("/api/triage/held-back/flag")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(ids(6..23).toSet(), flagged())
        assertNull(LibraryHold.current)
        assertNull(heldBack())
        assertTrue("Flagged 18 titles missing by hand." in allLines(), "${allLines()}")
    }

    @Test
    fun `the flag route answers 503 with the sentence and flags nothing when its re-read fails`() = routes {
        LibraryHold.set(HeldBack(1_000L, 23, 23))
        listing.value = "503"
        val r = post("/api/triage/held-back/flag")
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertTrue(JELLYFIN_READ_FAILED_SENTENCE in r.bodyAsText(), r.bodyAsText())
        assertEquals(emptySet(), flagged())
        assertNotNull(LibraryHold.current, "the hold stays")
    }
}
