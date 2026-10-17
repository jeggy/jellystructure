package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.FileCheckSteps
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.db.createDatabase
import dev.jellystructure.io.FileIo
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.server.routes.trackRoutes
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.torrent.SeedingSnapshot
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Phase 317, FR-317-41 — the Checks card's ↻ is disabled while re-measuring cannot change anything, and the
 * route refuses the forced re-measure the page would not send. In-process (`testApplication`) against the
 * real `trackRoutes`; no sidecar is needed because the rows are stored by hand, which is also the point: the
 * rule is about what is *stored*, not about what a GPU says.
 */
class AvSyncRefreshBlockRouteTest {
    private val dir = "/tmp/jellystructure-test-avsync-block-${getpid()}"

    @AfterTest
    fun tearDown() {
        platform.posix.system("rm -rf '$dir'")
    }

    private fun film(): MediaItem {
        val path = "$dir/film.mp4"
        FileIo.writeText(Path(path), "not a real film — only its size and mtime are read here")
        return MediaItem(
            id = "film", title = "Film", year = 2020, kind = MediaKind.MOVIE, path = path, tmdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L,
            runtime = 90,
            tracks = listOf(
                Track(0, "0:v:0", TrackKind.VIDEO, "h264", null, null, true, false),
                Track(1, "0:a:0", TrackKind.AUDIO, "eac3", "eng", null, true, false),
            ),
        )
    }

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(MediaItem, (String?, String) -> Unit) -> Unit) = testApplication {
        SystemFileSystem.createDirectories(Path(dir))
        val db = createDatabase("$dir/test.db")
        val configStore = ConfigStore("$dir/config.toml")
        configStore.update(
            configStore.current.copy(
                avsync = dev.jellystructure.config.AvSyncConfig(enabled = true),
                scan = configStore.current.scan.copy(
                    pipeline = listOf(PipelineStep(step = "scan_files"), PipelineStep(step = FileCheckSteps.AVSYNC)),
                ),
            ),
        )
        val store = MediaStore(db, JsTagStore("$dir/tags.json"), configStore)
        val integrity = FileIntegrityService(db, SeedingSnapshot(configStore, QBittorrentClient()))
        FileDamage.service = integrity
        val service = AvSyncService(db, integrity, configStore, store, null)
        val broadcaster = WsBroadcaster()
        val seeding = SeedingGuard(SeedingSnapshot(configStore, QBittorrentClient()))
        val scope = CoroutineScope(Dispatchers.Default)
        val queue = MediaJobQueue(db, store, broadcaster, JellyfinClient(), configStore, MediaHistory(db), seeding, null, scope, fileIntegrity = integrity, avSync = service)
        val item = film()
        runBlocking { store.addOrUpdate(item) }
        /** Stores the file's A/V sync row as it stands: `windows` as given, `reason` as given, current plan. */
        fun store(reason: String?, windows: String) {
            val stamp = FileIntegrityService.stampOf(item.path)!!
            db.fileAvSyncQueries.put(item.path, stamp.size, stamp.mtime, 1L, "test-model", null, reason, windows, "[]", null, AvSyncVerdict.PASSES_MAX.toLong())
        }
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            routing { route("/api") { trackRoutes(store, configStore, JellyfinClient(), MediaHistory(db), seeding, null, scope, broadcaster, queue) } }
        }
        block(item, ::store)
    }

    private val url = "/api/media/film/health/integrity/check?check=avsync"

    @Test
    fun `a forced re-measure of a title with no reading is accepted`() = app { _, _ ->
        assertNotEquals(HttpStatusCode.Conflict, client.post("$url&force=true").status)
    }

    @Test
    fun `a forced re-measure while every file is current gets 409 with the sentence`() = app { _, store ->
        store(null, "[]")                                        // measured, nothing readable: can't tell
        val r = client.post("$url&force=true")
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(TitleChecks.AVSYNC_CANT_TELL_ALL in r.bodyAsText(), r.bodyAsText())

        store(AvSyncReason.ANIMATION, "[]")                      // a skip is current too, and not can't tell
        val skipped = client.post("$url&force=true")
        assertEquals(HttpStatusCode.Conflict, skipped.status)
        assertTrue(TitleChecks.AVSYNC_MEASURED_ALREADY in skipped.bodyAsText(), skipped.bodyAsText())
    }

    @Test
    fun `an unforced check and the other checks' forced one are not refused`() = app { _, store ->
        store(null, "[]")
        assertNotEquals(HttpStatusCode.Conflict, client.post(url).status)
        assertNotEquals(HttpStatusCode.Conflict, client.post("/api/media/film/health/integrity/check?check=verify&force=true").status)
    }

    @Test
    fun `the card carries the block and the sentence — and the file reads can't tell`() = app { _, store ->
        store(null, "[]")
        val body = client.get("/api/media/film/checks").bodyAsText()
        val step = Json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }["steps"]!!
            .let { it as kotlinx.serialization.json.JsonArray }.map { it as kotlinx.serialization.json.JsonObject }
            .single { it["step"].toString() == "\"${FileCheckSteps.AVSYNC}\"" }
        assertEquals("true", step["refresh_blocked"].toString())
        assertTrue(TitleChecks.AVSYNC_CANT_TELL_ALL in step["refresh_blocked_why"].toString().replace("\\u2014", "—"), step.toString())
        assertTrue("\"state\":\"cant_tell\"" in body.replace(" ", ""), body)
    }
}
