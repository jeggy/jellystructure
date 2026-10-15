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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 317 (FR-317-39, the 2026-10-15 amendment, DEB-1216) — a file the sidecar could not decode at any
 * window is written down, in the pattern FR-317-36 set for `vram`, instead of failing the job every hour.
 *
 * Every fixture is a hand-written response document: no file is decoded, no card is opened, and the
 * sidecar is a stand-in that answers by call number. What is under test is the one new branch in
 * `measureOnce`, the row it stores, and the branch beside it that must NOT move: a 422 on FR-317-17's
 * second triple leaves the first triple's reading standing (`getOrNull`) and stores no `undecodable` row.
 * Nothing here names a library file: the stand-in files are generated under the test's own directory.
 */
class AvSyncUndecodableTest {
    private val dir = "/tmp/jellystructure-test-avsync-undecodable-${getpid()}"

    /** What the stand-in sidecar answers `/measure` with, per call number (1-based); the last one repeats. */
    private val answers = AtomicReference<List<Pair<Int, String>>>(emptyList())
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
                val n = measures.incrementAndGet()
                val list = answers.value
                val (code, text) = list[minOf(n, list.size) - 1]
                call.respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(code))
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

    /** A 90-minute film on disk — only its size and mtime are ever read. */
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

    /** The sidecar's 422 as FR-317-40 writes it: ffmpeg's real line, not the de-duplication's. */
    private val undecodable =
        422 to """{"error":"[matroska,webm @ 0x55eca1297b40] File ended prematurely"}"""

    private fun refusedWindow(startS: String) =
        """{"start_s":$startS,"offset_frames":null,"conf":0.0,"frames":0,"faces":[],"audio_short":false,"audio_short_frames":0}"""

    /** Three windows of a file whose faces were never found: `unknown`, which asks for a second triple. */
    private val firstTriple = 200 to
        """{"windows":[${listOf("900.0", "2700.0", "4320.0").joinToString(",") { refusedWindow(it) }}],"others":[],"decode":"cpu","seconds":4.1}"""

    // ── a 422 stores a row ──

    @Test
    fun `a 422 stores an unknown row that is current under the sidecar's own model`() = runBlocking {
        answers.value = listOf(undecodable)
        val item = film("truncated")
        val result = service.check(item, item.path)

        val reading = result.getOrThrow()   // the job SUCCEEDS: that is the whole of FR-317-39
        assertEquals(AvSyncState.UNKNOWN, reading.state, "nothing was measured, so there is no verdict")
        assertEquals(AvSyncReason.UNDECODABLE, reading.reason)
        assertEquals("not measured — no picture could be decoded at any of the points measured", reading.why)
        assertFalse(reading.isFinding, "an undecodable file is not a finding — nothing is claimed about it")

        val row = assertNotNull(service.rowFor(item.path), "a file that cannot be decoded still gets a row")
        assertEquals(AvSyncReason.UNDECODABLE, row.reason)
        assertEquals("test-model", row.model)
        assertEquals("[]", row.windows)
        assertEquals("[]", row.others)
        assertNull(row.audio)
        assertEquals(AvSyncReason.UNDECODABLE, service.readingFor(item.path)?.reason)
        assertTrue(item.path in service.rowsCurrentByPath(), "no longer `missing`: the hourly pass stops queueing it")
        assertEquals(1, measures.value, "one request: the 422 is not retried inside the check")
    }

    @Test
    fun `the row is due again once the file changes`() = runBlocking {
        answers.value = listOf(undecodable)
        val item = film("redownload")
        service.check(item, item.path).getOrThrow()
        assertNotNull(service.readingFor(item.path), "current while size and mtime match")

        // A re-download or a remux: a different size, which is what makes `readingFor` stop honouring it.
        FileIo.writeText(Path(item.path), "a different file entirely — this one is longer than the one the row was stored for")
        assertNull(service.readingFor(item.path), "FR-317-5: a changed file is unchecked, so the next pass asks again")
    }

    @Test
    fun `undecodable is not a skip so a new pipeline version asks again`() = runBlocking {
        answers.value = listOf(undecodable)
        val item = film("notaskip")
        service.check(item, item.path).getOrThrow()

        assertFalse(AvSyncReason.UNDECODABLE in AvSyncService.SKIP_REASONS)
        assertTrue(item.path in service.rowsCurrentByPath())
        model.value = "test-model/3"
        service.health()
        assertFalse(item.path in service.rowsCurrentByPath(), "a new pipeline version makes an undecodable row due")
    }

    @Test
    fun `the reason reads unknown with its own sentence`() {
        val reading = AvSyncVerdict.of(emptyList(), AvSyncVerdict.DEFAULT_ZERO_FRAMES, 5400.0, AvSyncReason.UNDECODABLE)
        assertEquals(AvSyncState.UNKNOWN, reading.state)
        assertEquals(AvSyncReason.UNDECODABLE, reading.reason)
        assertEquals(AvSyncReason.label(AvSyncReason.UNDECODABLE), reading.why)
        assertEquals(0, reading.confidentWindows)
        assertFalse(reading.isFinding)
        assertFalse(AvSyncReason.label(AvSyncReason.UNDECODABLE) == "skipped")
    }

    // ── the branches beside it must not move ──

    @Test
    fun `a 422 on the second triple keeps the first triple and stores no undecodable row`() = runBlocking {
        answers.value = listOf(firstTriple, undecodable)
        val item = film("secondtriple")
        val reading = service.check(item, item.path).getOrThrow()

        assertEquals(2, measures.value, "no confident window, so the second triple was asked for")
        assertNotEquals(AvSyncReason.UNDECODABLE, reading.reason, "a reading that exists is never replaced")
        val row = assertNotNull(service.rowFor(item.path))
        assertNotEquals(AvSyncReason.UNDECODABLE, row.reason)
        assertTrue(row.windows.contains("900.0"), "the first triple's windows stand: ${row.windows}")
        assertEquals(3, Regex("\"start_s\"").findAll(row.windows).count())
    }

    @Test
    fun `a 404 is still the setup's fault and stores nothing`() = runBlocking {
        answers.value = listOf(404 to """{"error":"not visible"}""")
        val item = film("notvisible")
        val result = service.check(item, item.path)

        assertTrue(result.isFailure)
        assertNull(service.rowFor(item.path))
        assertTrue(result.exceptionOrNull()?.message?.contains("check its mounts") == true)
    }

    @Test
    fun `a 500 is still no row`() = runBlocking {
        answers.value = listOf(500 to """{"error":"decode stalled after 60s on window 1620.0s"}""")
        val item = film("stalled")
        val result = service.check(item, item.path)

        assertTrue(result.isFailure)
        assertNull(service.rowFor(item.path))
    }

    // ── the new window key survives storage (acceptance 34 reads it back) ──

    @Test
    fun `a no_picture window is stored with its flag and is never confident`() = runBlocking {
        val noPicture = """{"start_s":4320.0,"offset_frames":null,"conf":0.0,"frames":0,"faces":[],"no_picture":true,"audio_short":false,"audio_short_frames":0}"""
        fun good(startS: String) =
            """{"start_s":$startS,"offset_frames":-1,"conf":3.5,"frames":800,"faces":[{"at_s":${startS.toDouble() + 1},"frames":800,"offset_frames":-1,"conf":3.5}],"audio_short":false,"audio_short_frames":0}"""
        answers.value = listOf(
            200 to """{"windows":[${good("900.0")},${good("2700.0")},$noPicture],"others":[],"decode":"cpu","seconds":3.0}""",
        )
        val item = film("nopicture")
        val reading = service.check(item, item.path).getOrThrow()

        // Two confident windows that agree: a real verdict, which the refused window beside them never moves.
        assertEquals(AvSyncState.IN_SYNC, reading.state)
        assertEquals(1, measures.value)
        val stored = Json { ignoreUnknownKeys = true }.decodeFromString(
            ListSerializer(AvSyncWindow.serializer()), assertNotNull(service.rowFor(item.path)).windows,
        )
        assertEquals(listOf(false, false, true), stored.map { it.noPicture })
        assertFalse(AvSyncVerdict.isConfident(stored[2]))
        assertFalse(stored[2].audioShort, "its audio was never asked for")
    }
}
