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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 317 (FR-317-36, the 2026-10-13 twelfth amendment) — a file the measuring card cannot hold is
 * written down instead of failing the job every hour.
 *
 * Every fixture is a **hand-written response document**: no file is decoded, no card is opened, and the
 * sidecar is a stand-in that answers 500. What is under test is the one new branch in `measureOnce` and
 * the row it stores — and, just as much, the branch beside it that must NOT move: a 500 without
 * `error_kind` is the old sidecar and every other fault, and it behaves exactly as it did before this
 * amendment. DEB-979's census is the reason: 129 of 150 failures on the lane were
 * `OutOfMemoryError: CUDA out of memory`, 122 distinct files in 4 titles, and because none of them stored
 * a row they were all still `missing` — re-queued by the hourly scan to fail again, for ever.
 */
class AvSyncVramTest {
    private val dir = "/tmp/jellystructure-test-avsync-vram-${getpid()}"

    /** What the stand-in sidecar answers `/measure` with, and under which status — set per test. */
    private val answer = AtomicReference("")
    private val status = AtomicInt(500)
    private val model = AtomicReference("test-model")
    private val measures = AtomicInt(0)

    private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            get("/health") {
                call.respondText(
                    """{"ok":true,"gpu":"stand-in","vram_total_gb":8.0,"model":"${model.value}","busy":false}""",
                    ContentType.Application.Json,
                )
            }
            post("/stat") { call.respondText("""{"exists":true,"size":1}""", ContentType.Application.Json) }
            post("/measure") {
                measures.incrementAndGet()
                call.respondText(answer.value, ContentType.Application.Json, HttpStatusCode.fromValue(status.value))
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

    /** A 90-minute episode-length film on disk — only its size and mtime are ever read. */
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

    /** The sidecar's refusal as FR-317-35 writes one: the geometry, the window's frame count, the chunk
     *  that failed and the peak — the four things DEB-979's census had to reconstruct from a SQL join
     *  against the stored track JSON of four titles, because the body was `CUDA out of memory` alone. */
    private val refusal = """
        {"error":"out of VRAM in detect at the one-frame floor: decoded 640×490, a 1125-frame window, 225 frames to process, 29.4 MB estimated for one frame, budget 2.00 GB, peak 1904 MB allocated","error_kind":"vram"}
    """.trimIndent()

    // ── a 500 that says `vram` stores a row ──

    @Test
    fun `a vram refusal stores an unknown row under the sidecar's own model`() = runBlocking {
        answer.value = refusal
        status.value = 500
        val item = film("vram")
        val reading = service.check(item, item.path).getOrThrow()

        assertEquals(AvSyncState.UNKNOWN, reading.state, "nothing was measured, so there is no verdict")
        assertEquals(AvSyncReason.VRAM, reading.reason)
        assertEquals("not measured — a frame of this file did not fit in the measuring card's memory", reading.why)
        assertFalse(reading.isFinding, "an unmeasurable file is not a finding — nothing is claimed about it")

        // The row is what the requirement is about: the file is no longer `missing`, so the hourly scan
        // stops re-queueing it, and the Checks card has one honest sentence for it.
        val row = assertNotNull(service.rowFor(item.path), "a file that cannot be measured still gets a row")
        assertEquals(AvSyncReason.VRAM, row.reason)
        assertEquals("test-model", row.model, "stored under the sidecar's model, as every other row is")
        assertEquals("[]", row.windows, "there are no windows — that is the whole of what happened")
        assertEquals("[]", row.others)
        assertNull(row.audio, "no track was measured against anything")
        assertEquals(AvSyncState.UNKNOWN, service.readingFor(item.path)?.state)
        assertEquals(AvSyncReason.VRAM, service.readingFor(item.path)?.reason)
        assertEquals(1, measures.value, "one request: a refusal is not retried inside the check")
    }

    @Test
    fun `vram is not a skip so a model bump asks again`() = runBlocking {
        answer.value = refusal
        status.value = 500
        val item = film("notaskip")
        service.check(item, item.path).getOrThrow()

        // The membership itself, which is what FR-317-36 says in one line: `animation` and `too_short`
        // were DECIDED here and no pipeline version can change them; this file was not decided about.
        assertFalse(AvSyncReason.VRAM in AvSyncService.SKIP_REASONS)
        assertTrue(item.path in service.rowsCurrentByPath(), "current under the model that wrote it")

        // …and the consequence, through the real rule: the sidecar introduces itself with a new model and
        // the row stops counting as a result, so the enqueue pass offers the file to the lane again.
        model.value = "test-model/3"
        service.health()
        assertFalse(item.path in service.rowsCurrentByPath(), "a new pipeline version makes a vram row due")
    }

    // ── and every other 500 behaves exactly as it did before ──

    @Test
    fun `a 500 with no error_kind is the old sidecar and is unchanged`() = runBlocking {
        answer.value = """{"error":"OutOfMemoryError: CUDA out of memory"}"""
        status.value = 500
        val item = film("oldsidecar")
        val result = service.check(item, item.path)

        assertTrue(result.isFailure, "no key, no row — the lane fails the job as it always has")
        assertNull(service.rowFor(item.path))
        assertEquals("the sidecar answered 500: OutOfMemoryError: CUDA out of memory", result.exceptionOrNull()?.message)
    }

    @Test
    fun `an error_kind this backend has never heard of is not a vram row either`() = runBlocking {
        answer.value = """{"error":"the decoder caught fire","error_kind":"something_later"}"""
        status.value = 500
        val item = film("unknownkind")
        val result = service.check(item, item.path)

        assertTrue(result.isFailure, "only the kinds this backend knows change its behaviour")
        assertNull(service.rowFor(item.path))
        assertEquals("the sidecar answered 500: the decoder caught fire", result.exceptionOrNull()?.message)
    }

    // A 422 stored nothing until FR-317-39 (DEB-1216): it is now the `undecodable` row, and its tests
    // live in AvSyncUndecodableTest.

    // ── the verdict's own rule, with no sidecar at all ──

    @Test
    fun `the reason reads unknown with its own sentence`() {
        val reading = AvSyncVerdict.of(emptyList(), AvSyncVerdict.DEFAULT_ZERO_FRAMES, 5400.0, AvSyncReason.VRAM)
        assertEquals(AvSyncState.UNKNOWN, reading.state)
        assertEquals(AvSyncReason.VRAM, reading.reason)
        assertEquals(AvSyncReason.label(AvSyncReason.VRAM), reading.why)
        assertEquals(0, reading.confidentWindows)
        assertFalse(reading.isFinding)
        // Not the generic "skipped" line every unknown reason falls back to: a viewer reading the Checks
        // card is told what happened, which is the difference between this and no row at all.
        assertFalse(AvSyncReason.label(AvSyncReason.VRAM) == "skipped")
    }
}
