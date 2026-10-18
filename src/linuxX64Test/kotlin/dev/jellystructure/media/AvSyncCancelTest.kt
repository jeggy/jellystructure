package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.io.FileIo
import dev.jellystructure.jobs.MediaJobParams
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingGuard
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-1673 — cancelling a running `avsync` row restores FR-164-5 and FR-317-8: the lane has no temp output,
 * so the cancel is cooperative and touches nothing of the media lane. Before this, `cancel()` took the
 * media branch for it: it set the media lane's `cancelRunning` (which the NEXT reorder, remove or repair
 * tripped over, possibly after the file was already renamed into place) and ran `pkill -f` on the title's
 * remux temp path.
 *
 * The sidecar is a stand-in on `127.0.0.1` whose `/measure` holds until the test lets it go, which is what
 * "a request in flight" is.
 */
class AvSyncCancelTest {
    private val dir = "/tmp/jellystructure-test-avsync-cancel-${getpid()}"
    private val measures = AtomicInt(0)
    private val release = AtomicInt(0)

    private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            get("/health") {
                call.respondText("""{"ok":true,"gpu":"stand-in","vram_total_gb":8.0,"model":"test-model","busy":false}""", ContentType.Application.Json)
            }
            post("/stat") { call.respondText("""{"exists":true,"size":1}""", ContentType.Application.Json) }
            post("/measure") {
                measures.incrementAndGet()
                while (release.value == 0) delay(20)
                // Three confident windows on one offset: a real finding, so a stored row would be visible.
                call.respondText(
                    """{"windows":[{"start_s":1080.0,"offset_frames":4,"conf":3.0,"frames":800,"faces":[]},""" +
                        """{"start_s":2700.0,"offset_frames":4,"conf":3.0,"frames":800,"faces":[]},""" +
                        """{"start_s":4320.0,"offset_frames":4,"conf":3.0,"frames":800,"faces":[]}],"others":[],"decode":"cpu","seconds":8.2}""",
                    ContentType.Application.Json,
                )
            }
        }
    }.start(wait = false)
    private val port = runBlocking { server.engine.resolvedConnectors().first().port }

    private lateinit var db: JellystructureDb
    private lateinit var store: MediaStore
    private lateinit var service: AvSyncService
    private lateinit var queue: MediaJobQueue
    private lateinit var scope: CoroutineScope
    private val shell = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        SystemFileSystem.createDirectories(Path(dir))
        db = createDatabase("$dir/test.db")
        val configStore = ConfigStore("$dir/config.toml")
        runBlocking {
            configStore.update(configStore.current.copy(avsync = dev.jellystructure.config.AvSyncConfig(enabled = true, url = "http://127.0.0.1:$port", timeoutS = 30)))
        }
        store = MediaStore(db, JsTagStore("$dir/tags.json"), configStore)
        val integrity = FileIntegrityService(db, SeedingSnapshot(configStore, QBittorrentClient()))
        service = AvSyncService(db, integrity, configStore, store, null)
        scope = CoroutineScope(Dispatchers.Default)
        queue = MediaJobQueue(
            db, store, WsBroadcaster(), JellyfinClient(), configStore, MediaHistory(db),
            SeedingGuard(SeedingSnapshot(configStore, QBittorrentClient())), null, scope, fileIntegrity = integrity, avSync = service,
        )
        queue.shellFireAndForget = { shell += it }
    }

    @AfterTest
    fun tearDown() {
        release.value = 1
        scope.cancel()
        server.stop(0, 0)
        platform.posix.system("rm -rf '$dir'")
    }

    private fun film(name: String = "film"): MediaItem {
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

    private suspend fun awaitState(id: String, state: String, timeoutMs: Long = 20_000) {
        var waited = 0L
        while (db.mediaJobQueries.findById(id).executeAsOneOrNull()?.state != state) {
            check(waited < timeoutMs) { "row $id never reached $state: ${db.mediaJobQueries.findById(id).executeAsOneOrNull()?.state}" }
            delay(50); waited += 50
        }
    }

    @Test
    fun `cancelling a running avsync row neither flags the media lane nor kills anything`() = runBlocking {
        val item = film()
        store.addOrUpdate(item)
        queue.enqueueFileCheck(FileCheckSchedule.AVSYNC_JOB, FileUnit(item, item.path, null), FileCheckSchedule.PRIORITY_OPERATOR, defer = false)
        val id = db.mediaJobQueries.listQueued().executeAsList().single().id
        db.mediaJobQueries.markRunning(1L, id)

        assertTrue(queue.cancel(id))

        assertFalse(queue.mediaCancelPending(), "the media lane's cancel flag must stay down — the next reorder would read it")
        assertEquals(emptyList(), shell, "no pkill (or any shell) for a lane with no temp output")
    }

    @Test
    fun `a running media row still takes the media lane's cancel`() = runBlocking {
        val item = film("media-film")
        store.addOrUpdate(item)
        val snap = queue.enqueue("reorder", item.id, "reorder", MediaJobParams(kind = "audio", order = listOf("0:a:0")))
        db.mediaJobQueries.markRunning(1L, snap.id)

        assertTrue(queue.cancel(snap.id))

        assertTrue(queue.mediaCancelPending(), "the allow-list still routes `media` to its own branch")
        assertEquals(1, shell.size, "the remux temp path is pkilled: $shell")
        assertTrue(shell.single().startsWith("pkill -f "), shell.single())
    }

    @Test
    fun `an avsync row cancelled mid-measurement ends cancelled and stores no reading`() = runBlocking {
        val item = film()
        store.addOrUpdate(item)
        queue.enqueueFileCheck(FileCheckSchedule.AVSYNC_JOB, FileUnit(item, item.path, null), FileCheckSchedule.PRIORITY_OPERATOR, defer = false)
        val id = db.mediaJobQueries.listQueued().executeAsList().single().id

        queue.start()
        awaitState(id, "running")
        var waited = 0L
        while (measures.value == 0) { check(waited < 20_000) { "the sidecar was never asked" }; delay(50); waited += 50 }

        assertTrue(queue.cancel(id))
        release.value = 1                       // the request in flight is allowed to finish

        awaitState(id, "cancelled")
        assertNull(service.rowFor(item.path), "the answer that came back after the cancel must not be written")
        assertFalse(queue.mediaCancelPending())
        assertEquals(emptyList(), shell)
    }
}
