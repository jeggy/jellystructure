package dev.jellystructure.media

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.io.FileIo
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingSnapshot
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-317-13 (DEB-2111) — the mount check proves the sidecar's mount with a file *this process can open*.
 *
 * Prod, 2026-10-22: the store still held a film whose folder had been replaced on disk, it was the first
 * video the check found under the film root, and the sidecar — correctly — answered `exists: false` for it.
 * That read as *the sidecar cannot see the root*, so `ready()` was false on every heartbeat for hours and
 * the lane claimed nothing. The stand-in sidecar here answers `/stat` from the real disk, exactly as the
 * real one does for a mounted path. Every file is generated under the test's own directory.
 */
class AvSyncMountSampleTest {
    private val dir = "/tmp/jellystructure-test-avsync-mount-${getpid()}"
    private val root = "$dir/lib"

    /** 0 = the stand-in sees the disk; 1 = it is a sidecar with the mount missing (everything absent). */
    private val blind = AtomicInt(0)

    private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            get("/health") {
                call.respondText("""{"ok":true,"gpu":"stand-in","vram_total_gb":8.0,"model":"test-model","busy":false}""", ContentType.Application.Json)
            }
            post("/stat") {
                val path = Regex("\"path\":\\s*\"([^\"]*)\"").find(call.receiveText())?.groupValues?.get(1).orEmpty()
                val seen = blind.value == 0 && SystemFileSystem.exists(Path(path))
                call.respondText("""{"exists":$seen,"size":1}""", ContentType.Application.Json)
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
        SystemFileSystem.createDirectories(Path(root))
        db = createDatabase("$dir/test.db")
        configStore = ConfigStore("$dir/config.toml")
        runBlocking {
            configStore.update(
                configStore.current.copy(
                    avsync = dev.jellystructure.config.AvSyncConfig(enabled = true, url = "http://127.0.0.1:$port", timeoutS = 30),
                    libraries = listOf(LibraryMapping(jellyfinId = "lib", name = "Film", collectionType = "movies", localPath = root)),
                ),
            )
        }
        store = MediaStore(db, JsTagStore("$dir/tags.json"), configStore)
        val integrity = FileIntegrityService(db, SeedingSnapshot(configStore, QBittorrentClient()))
        service = AvSyncService(db, integrity, configStore, store, null)
    }

    @AfterTest
    fun tearDown() {
        server.stop(0, 0)
        platform.posix.system("rm -rf '$dir'")
    }

    private fun item(name: String, onDisk: Boolean): MediaItem {
        val path = "$root/$name/$name.mp4"
        if (onDisk) {
            SystemFileSystem.createDirectories(Path("$root/$name"))
            FileIo.writeText(Path(path), "not a real film")
        }
        return MediaItem(
            id = name, title = "Film $name", year = 2020, kind = MediaKind.MOVIE, path = path, tmdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L,
            tracks = emptyList(),
        )
    }

    @Test
    fun `an item whose file is gone from the disk is not the mount sample`() = runBlocking {
        store.update(listOf(item("gone", onDisk = false), item("here", onDisk = true)))
        val sample = service.samplePaths(configStore.current).single()
        assertEquals(root, sample.first)
        assertTrue(sample.second.endsWith("/here/here.mp4"), sample.second)
        assertTrue(service.ready(configStore.current), service.lastHoldReason.orEmpty())
        assertNull(service.lastHoldReason)
        assertTrue(service.settings(configStore.current).reachable)
    }

    @Test
    fun `a sample that disappears after it was cached is replaced instead of read as an unmounted root`() = runBlocking {
        val a = item("a", onDisk = true)
        val b = item("b", onDisk = true)
        store.update(listOf(a, b))
        assertTrue(service.ready(configStore.current))
        val first = service.samplePaths(configStore.current).single().second
        platform.posix.system("rm -f '$first'")
        assertTrue(service.ready(configStore.current), "the next heartbeat must pick the other file: ${service.lastHoldReason}")
        assertNotNull(service.samplePaths(configStore.current).singleOrNull()?.takeIf { it.second != first })
        Unit
    }

    @Test
    fun `a root the sidecar really cannot see still holds the lane and names the root`() = runBlocking {
        store.update(listOf(item("here", onDisk = true)))
        blind.value = 1
        assertFalse(service.ready(configStore.current))
        assertEquals("the sidecar cannot see $root", service.lastHoldReason)
        blind.value = 0
        assertTrue(service.ready(configStore.current))
        assertNull(service.lastHoldReason)
    }

    @Test
    fun `the readiness log writes once per change — the first answer and each reason and the clearing`() = runBlocking {
        val lines = mutableListOf<String>()
        val log = LaneReadinessLog { lines += it }
        log.note("the sidecar cannot see /x")
        log.note("the sidecar cannot see /x")
        log.note("the sidecar cannot see /x")
        assertEquals(listOf("av sync lane: holding — the sidecar cannot see /x"), lines)
        log.note("the sidecar did not answer /health")
        log.note(null)
        log.note(null)
        log.note("the sidecar cannot see /x")
        assertEquals(
            listOf(
                "av sync lane: holding — the sidecar cannot see /x",
                "av sync lane: holding — the sidecar did not answer /health",
                "av sync lane: the sidecar is ready — claiming",
                "av sync lane: holding — the sidecar cannot see /x",
            ),
            lines,
        )
    }

    @Test
    fun `a lane that is held from boot logs its reason on the first answer`() = runBlocking {
        val lines = mutableListOf<String>()
        LaneReadinessLog { lines += it }.note(AvSyncService.LANE_OFF_REASON)
        assertEquals(listOf("av sync lane: holding — the check is switched off"), lines)
    }
}
