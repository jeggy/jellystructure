package dev.jellystructure.media

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.io.FileIo
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.log.Logger
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 317 (FR-317-32, the 2026-10-13 seventh amendment) — what the backend does with a window the
 * sidecar refused because its audio did not reach its picture.
 *
 * Every fixture here is a **hand-written response document**: no file is decoded, no card is touched and
 * no library file is used or needed. The refusal is expressed entirely in lengths the sidecar had already
 * measured, so the backend's whole half of FR-317-32 is reachable from a string.
 *
 * The one rule worth stating twice, because getting it wrong would be invisible: a refused window is
 * *there is nothing to measure here*, exactly as a window with no face track is — **not** *the measurement
 * failed*. So it never overrides a verdict. A file whose damage is local gets a real answer out of its
 * healthy windows; only a file whose damage is everywhere reads `unknown`.
 */
class AvSyncShortAudioTest {
    private val dir = "/tmp/jellystructure-test-avsync-short-${getpid()}"

    private val answer = AtomicReference("")
    private val model = AtomicReference("syncnet_v2+s3fd@907c0b5/2")
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
                call.respondText(answer.value, ContentType.Application.Json)
            }
        }
    }.start(wait = false)
    private val port = runBlocking { server.engine.resolvedConnectors().first().port }

    private lateinit var db: JellystructureDb
    private lateinit var configStore: ConfigStore
    private lateinit var store: MediaStore
    private lateinit var service: AvSyncService
    private lateinit var activity: ActivityLog

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
        // FR-317-32's audit trail is a WARN on the `integrity` channel, so the log is part of the subject
        // here rather than scenery: the whole point of the line is that a surviving verdict still leaves
        // a trace of the refusal, and nothing else in the row does.
        activity = ActivityLog("$dir/activity.json", WsBroadcaster(), CoroutineScope(Dispatchers.Default))
        Logger.activityLog = activity
    }

    @AfterTest
    fun tearDown() {
        Logger.activityLog = null
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

    /** One refused window as `measure.py` writes it: reported exactly as a window with no face track is,
     *  with the two additive fields beside it. 225 is the arithmetic for a window that decoded 36 s of its
     *  45 s — 1 125 video frames, 894 audio features, less the 6-frame construction lag. */
    private fun refused(startS: Double, shortFrames: Int = 225): String =
        """{"start_s":$startS,"offset_frames":null,"conf":0.0,"frames":0,"faces":[],
            "audio_short":true,"audio_short_frames":$shortFrames}"""

    /** A window that measured, with the shortfall a healthy one carries: 0, or a ±1. */
    private fun measured(startS: Double, offset: Int, shortFrames: Int = 0): String =
        """{"start_s":$startS,"offset_frames":$offset,"conf":3.5,"frames":800,
            "faces":[{"at_s":${startS + 1},"frames":800,"offset_frames":$offset,"conf":3.5}],
            "audio_short":false,"audio_short_frames":$shortFrames}"""

    private fun document(vararg windows: String): String =
        """{"windows":[${windows.joinToString(",")}],"others":[],"decode":"nvdec","seconds":8.2}"""

    private fun storedWindows(path: String): List<AvSyncWindow> {
        val row = assertNotNull(service.rowFor(path), "the row must exist")
        return Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(AvSyncWindow.serializer()), row.windows)
    }

    private suspend fun integrityWarnings(): List<String> =
        activity.list(1, 200, "integrity", "WARN").entries.map { it.message }

    // ── a refused window never overrides what the healthy windows measured ──

    @Test
    fun `one refused window beside two confident ones keeps the offset and stores no reason`() = runBlocking {
        answer.value = document(measured(1080.0, 4), refused(2700.0), measured(4320.0, 4))
        val item = film("local-damage")
        val reading = service.check(item, item.path).getOrThrow()

        // The verdict the two healthy windows measured, unchanged by the refusal beside them. Against the
        // library's default zero of −1 an offset of +4 frames is +5 relative, past EARLY_FRAMES.
        assertEquals(AvSyncState.OFFSET, reading.state)
        assertEquals(200L, reading.offsetMs)
        assertEquals(2, reading.confidentWindows)
        assertTrue(reading.isFinding, "a file whose damage is local still gets its real answer")
        assertNull(reading.reason, "short_audio must never be the reason while a verdict stands")

        val row = assertNotNull(service.rowFor(item.path))
        assertNull(row.reason, "and nothing is stored as the reason either")
        assertEquals(AvSyncState.OFFSET, service.readingFor(item.path)?.state)

        // The refusal is kept as evidence in the window it happened to, and the refused window is never
        // confident — it is the only reason it does not vote.
        val windows = storedWindows(item.path)
        assertEquals(3, windows.size)
        assertTrue(windows[1].audioShort)
        assertEquals(225, windows[1].audioShortFrames)
        assertEquals(0, windows[1].frames)
        assertNull(windows[1].offsetFrames)
        assertFalse(AvSyncVerdict.isConfident(windows[1]))
        assertFalse(windows[1].nonFinite, "a refused window is not a non-finite one")
        assertFalse(windows[0].audioShort)
        assertEquals(0, windows[0].audioShortFrames)
        assertTrue(AvSyncVerdict.isConfident(windows[0]))
        assertEquals(1, measures.value, "two confident windows that agree ask for no second triple")

        // ...and the one line that is the whole audit trail, logged although the verdict survived.
        val warnings = integrityWarnings()
        val line = assertNotNull(
            warnings.firstOrNull { it.contains("part of the audio did not decode") },
            "the refusal must leave a line on the integrity channel whatever the verdict: $warnings",
        )
        assertTrue(line.contains(item.path), line)
        assertTrue(line.contains("225 frames short"), line)
    }

    @Test
    fun `the worst window is the one named and never the first`() = runBlocking {
        answer.value = document(refused(1080.0, 40), refused(2700.0, 229), refused(4320.0, 7))
        val item = film("worst")
        service.check(item, item.path).getOrThrow()
        val line = assertNotNull(integrityWarnings().firstOrNull { it.contains("part of the audio did not decode") })
        assertTrue(line.contains("229 frames short"), line)
        assertEquals(229, AvSyncVerdict.worstAudioShortFrames(storedWindows(item.path)))
    }

    // ── a file whose damage is everywhere ──

    @Test
    fun `a file whose every window is refused says short_audio and not no_faces`() = runBlocking {
        answer.value = document(refused(1080.0), refused(2700.0), refused(4320.0))
        val item = film("everywhere")
        val reading = service.check(item, item.path).getOrThrow()

        assertEquals(AvSyncState.UNKNOWN, reading.state)
        // `no_faces` would be a lie about a window the model was never shown: it was refused before any
        // detection ran. It is the same lie DEB-836 filed about an unreadable file.
        assertEquals(AvSyncReason.SHORT_AUDIO, reading.reason)
        assertEquals(
            "not measured — part of the audio did not decode, so there were no words under the lips",
            reading.why,
        )
        assertFalse(reading.isFinding, "nothing is claimed about this file either way")

        val row = assertNotNull(service.rowFor(item.path))
        assertEquals(AvSyncReason.SHORT_AUDIO, row.reason)
        assertEquals(AvSyncReason.SHORT_AUDIO, service.readingFor(item.path)?.reason)
        // Every triple was asked for, which is exactly what should happen: more places in the file is how a file
        // with local damage gets its answer, and none of them gave one (FR-317-42: up to five triples).
        assertEquals(AvSyncVerdict.PASSES_MAX, measures.value)
    }

    @Test
    fun `a non-finite number stays the headline where both appear`() = runBlocking {
        // After this change a non-finite number is by definition NOT explained by short audio — the
        // refusal happens before any number is computed — so it is the one that is news.
        answer.value = document(
            refused(1080.0),
            """{"start_s":2700.0,"offset_frames":2,"conf":null,"frames":640,"nonfinite":true,
                "faces":[],"audio_short":false,"audio_short_frames":0}""",
        )
        val item = film("both")
        val reading = service.check(item, item.path).getOrThrow()
        assertEquals(AvSyncState.UNKNOWN, reading.state)
        assertEquals(AvSyncReason.NONFINITE, reading.reason)
        assertEquals(AvSyncReason.NONFINITE, assertNotNull(service.rowFor(item.path)).reason)
        // Both warnings are on the channel: the two facts are independent and each is worth a line.
        val warnings = integrityWarnings()
        assertTrue(warnings.any { it.contains("a non-finite number") }, warnings.toString())
        assertTrue(warnings.any { it.contains("part of the audio did not decode") }, warnings.toString())
    }

    // ── the reason is not a skip ──

    @Test
    fun `short_audio is not a skip so a pipeline bump measures the file again`() = runBlocking {
        assertFalse(
            AvSyncReason.SHORT_AUDIO in AvSyncService.SKIP_REASONS,
            "nothing was decided about this file: a new pipeline version must measure it again",
        )
        answer.value = document(refused(1080.0), refused(2700.0), refused(4320.0))
        val item = film("due-again")
        service.check(item, item.path).getOrThrow()
        assertEquals(AvSyncReason.SHORT_AUDIO, assertNotNull(service.rowFor(item.path)).reason)
        assertTrue(service.rowsCurrentByPath().containsKey(item.path), "current under the model that wrote it")

        // The bump itself: the sidecar introduces itself with a new pipeline version, and the row is due.
        model.value = "syncnet_v2+s3fd@907c0b5/3"
        service.health()
        assertFalse(
            service.rowsCurrentByPath().containsKey(item.path),
            "a short_audio row is due again at a new pipeline version — a skip would have stood for ever",
        )
    }

    // ── the verdict's own rule, with no sidecar at all ──

    @Test
    fun `the rule itself on windows built by hand`() {
        val refusedOnly = listOf(
            AvSyncWindow(startS = 1080.0, offsetFrames = null, conf = 0.0, frames = 0, audioShort = true, audioShortFrames = 225),
            AvSyncWindow(startS = 2700.0, offsetFrames = null, conf = 0.0, frames = 0, audioShort = true, audioShortFrames = 40),
        )
        val r = AvSyncVerdict.of(refusedOnly, AvSyncVerdict.DEFAULT_ZERO_FRAMES, 5400.0, null)
        assertEquals(AvSyncState.UNKNOWN, r.state)
        assertEquals(AvSyncReason.SHORT_AUDIO, r.reason)
        assertTrue(AvSyncVerdict.hasShortAudio(refusedOnly))
        assertEquals(225, AvSyncVerdict.worstAudioShortFrames(refusedOnly))

        // A faceless window set is unchanged by any of this: it still reads `no_faces`, which is the truth
        // about a window the model WAS shown and found nothing in.
        val faceless = listOf(AvSyncWindow(startS = 1080.0, offsetFrames = null, conf = 0.0, frames = 0))
        assertEquals(AvSyncReason.NO_FACES, AvSyncVerdict.of(faceless, AvSyncVerdict.DEFAULT_ZERO_FRAMES, 5400.0, null).reason)
        assertFalse(AvSyncVerdict.hasShortAudio(faceless))
        assertNull(AvSyncVerdict.worstAudioShortFrames(faceless))

        // And a row stored before the fields existed reads exactly as it did: false, and 0 frames short.
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(
            AvSyncWindow.serializer(),
            """{"start_s":1080.0,"offset_frames":-1,"conf":3.5,"frames":800}""",
        )
        assertFalse(old.audioShort)
        assertEquals(0, old.audioShortFrames)
        assertTrue(AvSyncVerdict.isConfident(old))
    }
}
