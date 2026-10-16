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

/**
 * DEB-836 (FR-317-5) — a file that was measured and could not be read stores NO reason.
 *
 * `reason` is set only when nothing was measured. The lane used to write `no_faces` for *any* reading
 * with fewer than two confident windows, so a file in which faces were found and never read confidently
 * said *no faces to measure* on the Checks card. Every window here is a hand-written response document;
 * no file is decoded and no card is touched.
 */
class AvSyncUnreadableReasonTest {
    private val dir = "/tmp/jellystructure-test-avsync-unreadable-${getpid()}"

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

    /** The DEB-810 window shape DEB-836 was measured on: `offset_frames` set, conf 1.03, 120 frames, and a
     *  62-frame face track — a face was found and read, and not read clearly enough (CONF_MIN 2.0,
     *  FRAMES_MIN 250). */
    private fun weak(startS: Double): String =
        """{"start_s":$startS,"offset_frames":3,"conf":1.03,"frames":120,
            "faces":[{"at_s":${startS + 1},"frames":62,"offset_frames":3,"conf":1.03}],
            "audio_short":false,"audio_short_frames":0}"""

    /** A window with no face track, as `measure.py` writes it. */
    private fun faceless(startS: Double): String =
        """{"start_s":$startS,"offset_frames":null,"conf":0.0,"frames":0,"faces":[],
            "audio_short":false,"audio_short_frames":0}"""

    private fun document(vararg windows: String): String =
        """{"windows":[${windows.joinToString(",")}],"others":[],"decode":"nvdec","seconds":8.2}"""

    private val readClearly = "no window read clearly enough to measure"

    // ── through the lane ──

    @Test
    fun `a file measured and read weakly stores no reason and says no window read clearly enough`() = runBlocking {
        answer.value = document(*(1..6).map { weak(it * 600.0) }.toTypedArray())
        val item = film("weak")
        val reading = service.check(item, item.path).getOrThrow()

        assertEquals(AvSyncState.UNKNOWN, reading.state)
        assertNull(reading.reason, "windows with a face track were measured, so there is no reason (FR-317-5)")
        assertEquals(readClearly, reading.why)
        assertFalse(reading.isFinding)

        val row = assertNotNull(service.rowFor(item.path))
        assertNull(row.reason, "and the row does not store one")
        // The sentence survives the round trip: this is what the Checks card reads on every later view.
        val later = assertNotNull(service.readingFor(item.path))
        assertNull(later.reason)
        assertEquals(readClearly, later.why)
        assertEquals(readClearly, service.findingSummary(row))
    }

    @Test
    fun `a file with no face track in any window still stores no_faces`() = runBlocking {
        answer.value = document(*(1..3).map { faceless(it * 900.0) }.toTypedArray())
        val item = film("faceless")
        val reading = service.check(item, item.path).getOrThrow()

        assertEquals(AvSyncState.UNKNOWN, reading.state)
        assertEquals(AvSyncReason.NO_FACES, reading.reason)
        assertEquals(AvSyncReason.NO_FACES, assertNotNull(service.rowFor(item.path)).reason)
        assertEquals(AvSyncReason.label(AvSyncReason.NO_FACES), service.readingFor(item.path)?.why)
    }

    // ── rows already stored: the read path stops trusting a stored no_faces ──

    @Test
    fun `a no_faces row stored beside windows with a face track reads as measured-and-unreadable`() = runBlocking {
        // What the lane wrote before DEB-836, for the shape DEB-815 measured.
        val item = film("legacy")
        answer.value = document(*(1..6).map { weak(it * 600.0) }.toTypedArray())
        service.check(item, item.path).getOrThrow()
        val fresh = assertNotNull(service.rowFor(item.path))
        db.fileAvSyncQueries.put(
            fresh.path, fresh.size, fresh.mtime, fresh.checked_at, fresh.model, fresh.audio,
            AvSyncReason.NO_FACES, fresh.windows, fresh.others, fresh.mkv_audio_ids,
        )
        assertEquals(AvSyncReason.NO_FACES, assertNotNull(service.rowFor(item.path)).reason)

        val reading = assertNotNull(service.readingFor(item.path))
        assertEquals(AvSyncState.UNKNOWN, reading.state)
        assertNull(reading.reason)
        assertEquals(readClearly, reading.why)
        assertEquals(readClearly, service.findingSummary(assertNotNull(service.rowFor(item.path))))
    }

    // ── the rule itself, on windows built by hand ──

    private fun w(startS: Double, offset: Int?, conf: Double, frames: Int, faces: List<AvSyncFace> = emptyList()) =
        AvSyncWindow(startS = startS, offsetFrames = offset, conf = conf, frames = frames, faces = faces)

    @Test
    fun `weak windows with a face track have no reason — faceless ones have no_faces — none have none`() {
        val zero = AvSyncVerdict.DEFAULT_ZERO_FRAMES
        val track = listOf(AvSyncFace(atS = 601.0, frames = 62, offsetFrames = 3, conf = 1.03))
        val weak = (1..6).map { w(it * 600.0, 3, 1.03, 120, track) }
        val r = AvSyncVerdict.of(weak, zero, 5400.0, null)
        assertEquals(AvSyncState.UNKNOWN, r.state)
        assertNull(r.reason)
        assertEquals(readClearly, r.why)

        // A face track in only ONE window of many is still a file that was measured.
        val mixed = listOf(w(600.0, null, 0.0, 0), w(1200.0, 2, 0.4, 80, track), w(1800.0, null, 0.0, 0))
        assertNull(AvSyncVerdict.of(mixed, zero, 5400.0, null).reason)

        val none = listOf(w(600.0, null, 0.0, 0), w(1200.0, null, 0.0, 0))
        assertEquals(AvSyncReason.NO_FACES, AvSyncVerdict.of(none, zero, 5400.0, null).reason)

        // Nothing came back at all: nothing was measured and nothing is claimed about faces.
        val empty = AvSyncVerdict.of(emptyList(), zero, 5400.0, null)
        assertEquals(AvSyncState.UNKNOWN, empty.state)
        assertNull(empty.reason)
    }

    @Test
    fun `a stored no_faces is re-derived beside windows and stands when there are none`() {
        val zero = AvSyncVerdict.DEFAULT_ZERO_FRAMES
        val track = listOf(AvSyncFace(atS = 601.0, frames = 62, offsetFrames = 3, conf = 1.03))
        val weak = (1..6).map { w(it * 600.0, 3, 1.03, 120, track) }
        val legacy = AvSyncVerdict.of(weak, zero, 5400.0, AvSyncReason.NO_FACES)
        assertNull(legacy.reason, "a face track was read, so the stored word was wrong")
        assertEquals(readClearly, legacy.why)

        val none = listOf(w(600.0, null, 0.0, 0))
        assertEquals(AvSyncReason.NO_FACES, AvSyncVerdict.of(none, zero, 5400.0, AvSyncReason.NO_FACES).reason)
        // Windows that failed to decode are an empty list: the stored word is all there is to go on.
        val undecoded = AvSyncVerdict.of(emptyList(), zero, 5400.0, AvSyncReason.NO_FACES)
        assertEquals(AvSyncReason.NO_FACES, undecoded.reason)
        assertEquals(AvSyncReason.label(AvSyncReason.NO_FACES), undecoded.why)
        // A skip is never re-derived, whatever the windows say.
        assertEquals(AvSyncReason.ANIMATION, AvSyncVerdict.of(weak, zero, 5400.0, AvSyncReason.ANIMATION).reason)
    }
}
