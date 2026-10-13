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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 317's 2026-10-13 sixth amendment (FR-317-31, DEB-912) — a busy retry re-sends only `/measure`.
 *
 * Two halves, like FR-317-29's test: the decision itself ([AvSyncProbeMemo], pure — every way it can hit
 * and every way it can miss), and what two `503`s in a row actually cost against a stand-in sidecar that
 * refuses every measurement. The spawns are counted through FR-317-31's test seam rather than by watching
 * `ffprobe` run, so the proof needs no media file at all — and **none of the household's is used here**.
 */
class AvSyncProbeMemoTest {
    private val dir = "/tmp/jellystructure-test-avsync-memo-${getpid()}"

    /** How many times the lane asked for each of the two process answers, and what the stand-in saw. */
    private val runtimeProbes = AtomicInt(0)
    private val idProbes = AtomicInt(0)
    private val measures = AtomicInt(0)
    private val healths = AtomicInt(0)

    private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            get("/health") {
                healths.incrementAndGet()
                call.respondText(
                    """{"ok":true,"gpu":"stand-in","vram_total_gb":8.0,"model":"test-model","busy":true,"busy_for_s":30}""",
                    ContentType.Application.Json,
                )
            }
            post("/stat") { call.respondText("""{"exists":true,"size":1}""", ContentType.Application.Json) }
            post("/measure") {
                measures.incrementAndGet()
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
        val store = MediaStore(db, JsTagStore("$dir/tags.json"), configStore)
        service = AvSyncService(
            db, FileIntegrityService(db, SeedingSnapshot(configStore, QBittorrentClient())), configStore, store, null,
            probeRuntimeSeconds = { runtimeProbes.incrementAndGet(); 5_400.0 },
            probeMkvIds = { idProbes.incrementAndGet(); mapOf("0:a:0" to 2) },
        )
    }

    @AfterTest
    fun tearDown() {
        server.stop(0, 0)
        platform.posix.system("rm -rf '$dir'")
    }

    private fun film(name: String, extension: String, animation: Boolean = false): MediaItem {
        val path = "$dir/$name.$extension"
        FileIo.writeText(Path(path), "not a real film — only its size and mtime are read here")
        return MediaItem(
            id = name, title = "Film", year = 2020, kind = MediaKind.MOVIE, path = path, tmdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L,
            runtime = 90,
            tmdbGenreIds = if (animation) listOf(16) else emptyList(),
            tracks = listOf(
                Track(0, "0:v:0", TrackKind.VIDEO, "h264", null, null, true, false),
                Track(1, "0:a:0", TrackKind.AUDIO, "eac3", "eng", null, true, false),
            ),
        )
    }

    // ── the decision: every hit, every miss ──

    private val stamp = AvSyncProbeMemo.Stamp("/mnt/media/a.mkv", size = 1_000, mtime = 10)

    @Test
    fun `an answer kept under a stamp is returned under that same stamp`() {
        val memo = AvSyncProbeMemo()
        memo.keepRuntimeS(stamp, 5_400.0)
        memo.keepMkvIds(stamp, mapOf("0:a:0" to 2))
        assertEquals(5_400.0, memo.runtimeS(stamp)?.value)
        assertEquals(mapOf("0:a:0" to 2), memo.mkvIds(stamp)?.value)
    }

    @Test
    fun `asked and got nothing is an answer and is not asked again`() {
        val memo = AvSyncProbeMemo()
        // The whole reason the memo boxes its answers: these two are exactly the files whose probe costs
        // the most, and a null-means-absent memo would re-pay both on every single attempt.
        memo.keepRuntimeS(stamp, null)
        val runtime = memo.runtimeS(stamp)
        assertNotNull(runtime, "a null runtime is *asked and got nothing*, not *not asked*")
        assertNull(runtime.value)
        memo.keepMkvIds(stamp, emptyMap())
        val ids = memo.mkvIds(stamp)
        assertNotNull(ids, "an empty id map has its own meaning (FR-317-22, FR-317-26) — it is an answer")
        assertTrue(ids.value.isEmpty())
    }

    @Test
    fun `a file rewritten between attempts misses and is probed again`() {
        val memo = AvSyncProbeMemo()
        memo.keepRuntimeS(stamp, 5_400.0)
        memo.keepMkvIds(stamp, mapOf("0:a:0" to 2))
        assertNull(memo.runtimeS(stamp.copy(size = stamp.size + 1)), "a new size is a different file")
        assertNull(memo.mkvIds(stamp.copy(size = stamp.size + 1)))
        assertNull(memo.runtimeS(stamp.copy(mtime = stamp.mtime + 1)), "and so is a new mtime")
        assertNull(memo.mkvIds(stamp.copy(mtime = stamp.mtime + 1)))
    }

    @Test
    fun `another path never reads the entry — and replaces it`() {
        val memo = AvSyncProbeMemo()
        memo.keepRuntimeS(stamp, 5_400.0)
        val other = stamp.copy(path = "/mnt/media/b.mkv")
        assertNull(memo.runtimeS(other), "one entry, and it is not this file's")
        memo.keepRuntimeS(other, 1_200.0)
        assertEquals(1_200.0, memo.runtimeS(other)?.value)
        assertNull(memo.runtimeS(stamp), "the second path replaced the first — the lane has one worker")
    }

    @Test
    fun `the two answers can never be held under stamps that disagree`() {
        val memo = AvSyncProbeMemo()
        memo.keepRuntimeS(stamp, 5_400.0)
        val rewritten = stamp.copy(mtime = stamp.mtime + 1)
        memo.keepMkvIds(rewritten, mapOf("0:a:0" to 2))
        assertNull(memo.runtimeS(rewritten), "the runtime belonged to the file as it was before")
        assertEquals(mapOf("0:a:0" to 2), memo.mkvIds(rewritten)?.value)
        memo.forget()
        assertNull(memo.mkvIds(rewritten), "and the claim run ends them both")
    }

    // ── what two 503s in a row actually cost ──

    @Test
    fun `a second busy attempt sends only measure — the two probes are paid once`() = runBlocking {
        val item = film("refused", "mkv")
        val firstFailure = service.check(item, item.path).exceptionOrNull()
        assertTrue(firstFailure is AvSyncService.AvSyncBusy, "a 503 is busy and not a refusal: $firstFailure")
        val sentOnce = measures.value
        assertTrue(sentOnce >= 1, "the first attempt reached /measure")
        val secondFailure = service.check(item, item.path).exceptionOrNull()
        assertTrue(secondFailure is AvSyncService.AvSyncBusy, "and so is the second: $secondFailure")

        assertEquals(1, runtimeProbes.value, "one ffprobe for the file, not one per attempt")
        assertEquals(1, idProbes.value, "one mkvmerge -J for the file, not one per attempt")
        // What the retry DOES still do. How many POSTs one busy answer costs is the shared client's
        // business and not this phase's (FR-194-7, DEB-914), so it is deliberately not a number here.
        assertTrue(measures.value > sentOnce, "the second attempt still sends /measure")
        assertEquals(1, healths.value, "and nothing else: the model is asked for once, FR-317-7")
    }

    @Test
    fun `a terminal attempt keeps nothing — the Checks card's retry probes again`() = runBlocking {
        // FR-317-23's ↻ exists to ask `mkvmerge` again about a file that has not changed, so the memo
        // must not outlive the claim run. An Animation skip is the cheapest terminal outcome to reach:
        // FR-317-8 writes it off after the runtime probe and never calls the sidecar.
        val item = film("skipped", "mkv", animation = true)
        repeat(2) { assertTrue(service.check(item, item.path).isSuccess) }
        assertEquals(2, runtimeProbes.value, "a finished row keeps nothing — the retry re-probes")
        assertEquals(0, idProbes.value, "a skip never reaches the id probe at all")
        assertEquals(0, measures.value, "and never the sidecar")
    }
}
