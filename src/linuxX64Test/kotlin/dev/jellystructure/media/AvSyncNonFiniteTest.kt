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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 317 (FR-317-30, the 2026-10-13 fifth amendment) — what the backend does with a measurement that
 * carries a number which is not a number.
 *
 * Every fixture here is a **hand-written response document**: no file is decoded and none is needed. The
 * two prod failures of 2026-10-13 (*"Unexpected special floating-point value at offset 1576"* and
 * *"… 1657"*) were not measurement failures — the decode ran and the GPU time was spent — they were the
 * last step of it, and a document with a `null` (or a bare `NaN`) in a known place reaches that step
 * without a card.
 *
 * Two halves, and both matter:
 *  - the sanitised document the fixed sidecar sends is **accepted**, stored, and does not count as a clean
 *    measurement;
 *  - a literal `NaN` from any other source is still **refused**. Accepting one would mean storing one, and
 *    a stored `NaN` compares false against every bar in FR-317-7 — the file would pass them all and be
 *    counted as measured and in step.
 */
class AvSyncNonFiniteTest {
    private val dir = "/tmp/jellystructure-test-avsync-nonfinite-${getpid()}"

    /** What the stand-in sidecar answers `/measure` with — set per test. */
    private val answer = AtomicReference("")
    private val measures = AtomicInt(0)

    private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            get("/health") {
                call.respondText(
                    """{"ok":true,"gpu":"stand-in","vram_total_gb":8.0,"model":"test-model","busy":false}""",
                    ContentType.Application.Json,
                )
            }
            post("/stat") { call.respondText("""{"exists":true,"size":1}""", ContentType.Application.Json) }
            post("/measure") {
                measures.incrementAndGet()
                call.respondText(answer.value, ContentType.Application.Json)
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

    /** A 90-minute film with two audio tracks on disk — the second one makes the sidecar's `others` list
     *  non-empty, which is where one of the two non-finite values sat. Only its size and mtime are read. */
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
                Track(2, "0:a:1", TrackKind.AUDIO, "ac3", "dan", null, false, false),
            ),
        )
    }

    /**
     * The sidecar's answer as FR-317-30 has it write one: two windows that measured cleanly and would, on
     * their own, read `in sync`; one whose `conf` came back non-finite and is sent as `null` with the
     * `nonfinite` flag beside it; and an `others` entry whose correlation went the same way.
     *
     * [conf] is spliced in raw so the same fixture can carry `null` (the fixed sidecar) or `NaN` (an
     * unfixed one, or anything else that hands the backend a document).
     */
    private fun document(conf: String): String = """
        {"windows":[
          {"start_s":1080.0,"offset_frames":-1,"conf":3.5,"frames":800,
           "faces":[{"at_s":1081.2,"frames":800,"offset_frames":-1,"conf":3.5}]},
          {"start_s":2700.0,"offset_frames":2,"conf":$conf,"frames":640,"nonfinite":true,
           "faces":[{"at_s":2701.0,"frames":640,"offset_frames":2,"conf":$conf,"nonfinite":true}]},
          {"start_s":4320.0,"offset_frames":-1,"conf":4.0,"frames":900,"faces":[]}
        ],
        "others":[{"audio":"0:a:1","lag_frames":0,"corr":$conf,"nonfinite":true}],
        "decode":"nvdec","seconds":8.2}
    """.trimIndent()

    private fun storedWindows(path: String): List<AvSyncWindow> {
        val row = assertNotNull(service.rowFor(path), "the row must exist")
        return Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(AvSyncWindow.serializer()), row.windows)
    }

    // ── the sanitised document is accepted — and is not a clean measurement ──

    @Test
    fun `a null conf and a null corr are stored and the file does not count as measured`() = runBlocking {
        answer.value = document("null")
        val item = film("nonfinite")
        // The regression itself: before FR-317-30 this document carried `NaN` here and `check` came back
        // as a failed job — "Unexpected JSON token … Unexpected special floating-point value".
        val reading = service.check(item, item.path).getOrThrow()

        assertEquals(AvSyncState.UNKNOWN, reading.state, "a measurement with a number that is not a number has no verdict")
        assertEquals(AvSyncReason.NONFINITE, reading.reason)
        assertEquals("not measured — the measurement produced a number that is not a number", reading.why)
        assertFalse(reading.isFinding, "nothing is claimed about this file either way")

        // Stored, with its reason — so every later read of the row says the same thing, and the file is
        // not re-measured on every pass as if it had never been tried.
        val row = assertNotNull(service.rowFor(item.path))
        assertEquals(AvSyncReason.NONFINITE, row.reason)
        assertEquals("test-model", row.model)
        assertEquals(AvSyncState.UNKNOWN, service.readingFor(item.path)?.state)

        // The windows are kept as they came back: a coerced `null` is the 0.0 that means *no answer*, and
        // the flag is what tells it apart from a window that simply had no face track.
        val windows = storedWindows(item.path)
        assertEquals(3, windows.size)
        assertEquals(0.0, windows[1].conf)
        assertTrue(windows[1].nonFinite)
        assertTrue(windows[1].faces.single().nonFinite)
        assertEquals(0.0, windows[1].faces.single().conf)
        assertFalse(AvSyncVerdict.isConfident(windows[1]), "a non-finite window is evidence of nothing")
        // …and the two that measured cleanly are untouched, flag and all.
        assertEquals(3.5, windows[0].conf)
        assertFalse(windows[0].nonFinite)
        assertTrue(AvSyncVerdict.isConfident(windows[0]))
        assertTrue(AvSyncVerdict.isConfident(windows[2]))
        assertEquals(1, measures.value, "one pass: two confident windows that agree ask for no second triple")
    }

    @Test
    fun `a non-finite correlation is never read as a track in step`() {
        // FR-317-11's `others` half: `corr` is 0.0 after the coercion, which is below IN_STEP_CORR, so a
        // track whose correlation was not a number is never moved by a suggested command.
        val other = AvSyncOther(audio = "0:a:1", lagFrames = 0, corr = 0.0, nonFinite = true)
        assertFalse(AvSyncAdvice.inStep(AvSyncTrack("0:a:1", "Danish", null, other.lagFrames, other.corr)))
    }

    // ── a literal NaN is still refused, wherever it comes from ──

    @Test
    fun `a sidecar that still sends a bare NaN is refused rather than stored`() = runBlocking {
        answer.value = document("NaN")
        val item = film("stillbroken")
        val result = service.check(item, item.path)

        assertTrue(result.isFailure, "a document with a bare NaN in it is not JSON and is not taken")
        assertEquals(null, service.rowFor(item.path), "and nothing is stored from it")
        // The message is the parser's own, which is how the two prod rows of 2026-10-13 read.
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message.contains("floating-point", ignoreCase = true) || message.contains("JSON", ignoreCase = true), message)
    }

    @Test
    fun `the window parser itself refuses NaN and Infinity`() {
        val parser = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        // The same configuration the service decodes a `/measure` answer with: coercing a `null` to the
        // default is not the same as accepting a `NaN`, and this is the assertion that keeps it so.
        for (token in listOf("NaN", "Infinity", "-Infinity")) {
            val raw = """{"start_s":1080.0,"offset_frames":-1,"conf":$token,"frames":800}"""
            val failed = runCatching { parser.decodeFromString(AvSyncWindow.serializer(), raw) }.isFailure
            assertTrue(failed, "a window carrying $token must not parse")
        }
        // A `null` on the same field does parse — and reads as *no answer*.
        val coerced = parser.decodeFromString(
            AvSyncWindow.serializer(),
            """{"start_s":1080.0,"offset_frames":-1,"conf":null,"frames":800,"nonfinite":true}""",
        )
        assertEquals(0.0, coerced.conf)
        assertTrue(coerced.nonFinite)
        assertFalse(AvSyncVerdict.isConfident(coerced))
    }

    // ── the verdict's own rule (no sidecar at all) ──

    @Test
    fun `a file with nothing but non-finite windows says so instead of claiming no faces`() {
        val windows = listOf(
            AvSyncWindow(startS = 1080.0, offsetFrames = 2, conf = 0.0, frames = 640, nonFinite = true),
            AvSyncWindow(startS = 2700.0, offsetFrames = 2, conf = 0.0, frames = 700, nonFinite = true),
        )
        val reading = AvSyncVerdict.of(windows, AvSyncVerdict.DEFAULT_ZERO_FRAMES, 5400.0, null)
        assertEquals(AvSyncState.UNKNOWN, reading.state)
        // `no_faces` would be a lie: the model found a face and read it — what it read was not a number.
        assertEquals(AvSyncReason.NONFINITE, reading.reason)
        assertEquals(0, reading.confidentWindows)

        // A faceless window set is unchanged by any of this.
        val faceless = listOf(AvSyncWindow(startS = 1080.0, offsetFrames = null, conf = 0.0, frames = 0))
        assertEquals(AvSyncReason.NO_FACES, AvSyncVerdict.of(faceless, AvSyncVerdict.DEFAULT_ZERO_FRAMES, 5400.0, null).reason)
        assertFalse(AvSyncVerdict.hasNonFinite(faceless))
        assertTrue(AvSyncVerdict.hasNonFinite(windows))
    }
}
