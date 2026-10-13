package dev.jellystructure.media

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.io.FileIo
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingSnapshot
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 317's 2026-10-13 fourth amendment (FR-317-29) — the lane gives up on a sidecar that is never
 * free, and says which.
 *
 * Two halves, because the defect has two: the rule itself ([AvSyncBusyGiveUp], pure, both of its ways to
 * decide), and what a `503` for ever actually does to a row — against a stand-in sidecar on `127.0.0.1`
 * answering `503` to every `/measure`, the same shape [AvSyncSecondPassTest] uses. Thirty waits of the
 * sidecar's real 20 s is ten minutes, which is the point of the cap and no way to run a test, so the wait
 * is not what is exercised here: the decision is, and the rule above it is pure for exactly that reason.
 */
class AvSyncBusyGiveUpTest {
    private val dir = "/tmp/jellystructure-test-avsync-busy-${getpid()}"

    /** What the stand-in's `/health` says it has been busy for — `null` = the field is absent entirely,
     *  which is an older sidecar image and must read as *unknown* rather than as idle. */
    private val busyForS = AtomicReference<Int?>(null)

    /** How many `/measure` calls the stand-in refused, and how many `/health` reads it answered. */
    private val measures = AtomicInt(0)
    private val healths = AtomicInt(0)

    private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            get("/health") {
                healths.incrementAndGet()
                val busy = busyForS.value?.let { ""","busy_for_s":$it""" } ?: ""
                call.respondText(
                    """{"ok":true,"gpu":"stand-in","vram_total_gb":8.0,"model":"test-model","busy":true$busy}""",
                    ContentType.Application.Json,
                )
            }
            post("/stat") { call.respondText("""{"exists":true,"size":1}""", ContentType.Application.Json) }
            post("/measure") {
                measures.incrementAndGet()
                // FR-317-8's own answer for busy, which is what DEB-897's sidecar gave 700-odd times.
                call.respondText(
                    """{"error":"a measurement is already running","retry_after_s":20}""",
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
            }
        }
    }.start(wait = false)
    private val port = runBlocking { server.engine.resolvedConnectors().first().port }

    private lateinit var db: JellystructureDb
    private lateinit var configStore: ConfigStore
    private lateinit var store: MediaStore
    private lateinit var service: AvSyncService

    @BeforeTest
    fun setUp() {
        SystemFileSystem.createDirectories(Path(dir))
        db = createDatabase("$dir/test.db")
        configStore = ConfigStore("$dir/config.toml")
        runBlocking {
            configStore.update(
                configStore.current.copy(
                    avsync = dev.jellystructure.config.AvSyncConfig(enabled = true, url = "http://127.0.0.1:$port", timeoutS = 30),
                ),
            )
        }
        store = MediaStore(db, JsTagStore("$dir/tags.json"), configStore)
        service = AvSyncService(db, FileIntegrityService(db, SeedingSnapshot(configStore, QBittorrentClient())), configStore, store, null)
    }

    @AfterTest
    fun tearDown() {
        server.stop(0, 0)
        platform.posix.system("rm -rf '$dir'")
    }

    private fun film(name: String): MediaItem {
        val path = "$dir/$name.mp4"
        FileIo.writeText(Path(path), "not a real film — only its size and mtime are read here")
        return MediaItem(
            id = name, title = "Film", year = 2020, kind = MediaKind.MOVIE, path = path, tmdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L,
            runtime = 90,
            tracks = listOf(
                Track(0, "0:v:0", TrackKind.VIDEO, "h264", null, null, true, false),
                Track(1, "0:a:0", TrackKind.AUDIO, "eac3", "eng", null, true, false),
            ),
        )
    }

    // ── the rule: the count, and the sooner answer where /health allows one ──

    @Test
    fun `the thirtieth busy answer in a row gives up and the twenty-ninth waits`() {
        // 580 s is 29 waits of the sidecar's own 20 s — ten minutes, which is what the cap buys.
        assertNull(AvSyncBusyGiveUp.reason(29, 560, null), "a run still inside the cap waits")
        val reason = AvSyncBusyGiveUp.reason(30, 580, null)
        assertNotNull(reason)
        assertTrue(reason.startsWith("the sidecar never became free"), reason)
        assertTrue(reason.contains("10 min"), "the wait is named in the words the operator sees: $reason")
    }

    @Test
    fun `a busy_for_s past the ceiling gives up on the first answer`() {
        val reason = AvSyncBusyGiveUp.reason(1, 0, AvSyncBusyGiveUp.WEDGED_AFTER_S + 1)
        assertNotNull(reason, "past its own ceiling the sidecar is wedged by definition")
        assertTrue(reason.startsWith("the sidecar never became free"), reason)
        assertTrue(reason.contains("wedged"), reason)
        // DEB-897's own number: four hours of busy, on the very first answer.
        assertNotNull(AvSyncBusyGiveUp.reason(1, 0, 4 * 3600))
    }

    @Test
    fun `a busy_for_s inside the ceiling is a measurement in progress and is waited out`() {
        assertNull(AvSyncBusyGiveUp.reason(1, 0, 0), "idle-but-busy is a race between two reads — not a verdict")
        assertNull(AvSyncBusyGiveUp.reason(5, 100, 120), "two minutes in is a long measurement, not a wedge")
        assertNull(AvSyncBusyGiveUp.reason(5, 100, AvSyncBusyGiveUp.WEDGED_AFTER_S), "the ceiling itself is not past it")
    }

    @Test
    fun `an absent busy_for_s is unknown and never idle — the count decides alone`() {
        assertNull(AvSyncBusyGiveUp.reason(1, 0, null), "an older sidecar image says nothing — so wait")
        assertNotNull(AvSyncBusyGiveUp.reason(AvSyncBusyGiveUp.AFTER, 600, null), "and the count still ends it")
    }

    // ── what a 503 for ever does to the row ──

    @Test
    fun `a sidecar that is never free refuses the measurement and stores nothing`() = runBlocking {
        val item = film("refused")
        val result = service.check(item, item.path)
        val failure = result.exceptionOrNull()
        assertTrue(failure is AvSyncService.AvSyncBusy, "a 503 is busy and not a refusal: $failure")
        assertEquals(20, (failure as AvSyncService.AvSyncBusy).retryAfterS, "the sidecar's own retry_after_s")
        // TWO POSTs for one busy answer, not one, and that is the shared client rather than this phase:
        // `HttpRequestRetryConfig`'s own `init` calls `retryOnExceptionOrServerErrors(3)`, and
        // `OutboundHttp`'s block replaces `retryOnExceptionIf`/`maxRetries` without replacing
        // `shouldRetry` — so every 5xx is re-sent once, 200 ms later, POST or not. It does not change what
        // the lane counts (one `AvSyncBusy` per `check`, so FR-317-29's thirty answers are still thirty
        // waits of `retry_after_s`), and it is asserted here so that the day it is fixed this test says so
        // instead of a sidecar quietly seeing half the traffic it used to. Reported out of DEB-902.
        assertEquals(2, measures.value, "one busy answer, re-sent once by the shared client's 5xx retry")
        // The whole point of failing the row rather than storing a reason: FR-317-8's NO_RESULT rule makes
        // the file due again, and a stored row would have hidden it from the next sweep.
        assertEquals(0, db.fileAvSyncQueries.all().executeAsList().size, "no reading, and no reason row either")
    }

    @Test
    fun `busy_for_s is read off health where the sidecar reports it and is null where it does not`() = runBlocking {
        busyForS.value = null
        assertNull(service.busyForS(), "absent is unknown")
        busyForS.value = 4 * 3600
        assertEquals(4 * 3600, service.busyForS())
        busyForS.value = 0
        assertEquals(0, service.busyForS(), "idle is zero and not absent — the two are different answers")
        assertTrue(healths.value >= 3)
    }
}
