package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingCheckResult
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.torrent.SeedingSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2051 (FR-6, FR-7, FR-9) — one job on the media lane, through the worker pool, and the state it leaves on its row. */
class ContainerConvertJobTest {
    private val dir = "/tmp/jellystructure-test-convert-job-${getpid()}"
    private val folder = "$dir/Film (2020)"
    private val avi = "$folder/Film (2020).avi"
    private lateinit var db: JellystructureDb
    private lateinit var store: MediaStore
    private lateinit var queue: MediaJobQueue
    private lateinit var scope: CoroutineScope

    @BeforeTest fun setUp() {
        SystemFileSystem.createDirectories(Path(folder))
        db = createDatabase("$dir/test.db")
        val configStore = ConfigStore("$dir/config.toml")
        scope = CoroutineScope(Dispatchers.Default)
        store = MediaStore(db, JsTagStore("$dir/tags.json"), configStore)
        queue = MediaJobQueue(
            db, store, WsBroadcaster(), JellyfinClient(), configStore, MediaHistory(db),
            SeedingGuard(SeedingSnapshot(configStore, QBittorrentClient())), null, scope,
        )
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        dev.jellystructure.db.closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private fun film(): MediaItem {
        assertEquals(0, platform.posix.system("ffmpeg -v error -y -f lavfi -i testsrc=size=64x48:rate=10:duration=1 -f lavfi -i sine=duration=1 -c:v mpeg4 -c:a mp3 '$avi'"))
        val item = MediaItem(
            id = "film-2020", title = "Film", year = 2020, kind = MediaKind.MOVIE, path = avi, tmdbId = null,
            originalLanguage = "en", posterPath = null, overview = null, issueCount = 1, scannedAt = 0L,
            tracks = runBlocking { FfprobeRunner.probe(avi) },
        )
        runBlocking { store.addOrUpdate(item) }
        return item
    }

    /** What production does on success, in miniature: the stored path follows the file. */
    private inner class Ports(var jellyfinFound: Boolean = true, var radarrTaken: Boolean? = true, var seeding: SeedingCheckResult = SeedingCheckResult.Allowed) : ConversionPorts {
        override suspend fun seeding(path: String) = seeding
        override suspend fun jellyfinPath(item: MediaItem): String? = null
        override fun freeBytes(dir: String): Long? = Long.MAX_VALUE
        override suspend fun moveIdentity(item: MediaItem, oldPath: String, newPath: String): ConversionIdentityResult {
            store.updateOne(store.resolve(item.id)!!.copy(path = newPath))
            return ConversionIdentityResult(jellyfinFound, radarrTaken, emptyList())
        }
    }

    private fun waitForRow(id: String, seconds: Int = 90): String {
        repeat(seconds * 2) {
            val state = db.mediaJobQueries.findById(id).executeAsOne().state
            if (state != "queued" && state != "running") return state
            runBlocking { delay(500) }
        }
        error("job $id did not finish")
    }

    private val faroese = listOf(ConversionRequested("0:a:0", "fao"))

    @Test fun `a verified conversion finishes the row at step three and the file's latest conversion says so`() {
        val item = film()
        queue.containerConverter = ContainerConverter(Ports(jellyfinFound = false, radarrTaken = false))
        queue.start()
        val job = runBlocking { queue.enqueueContainerConversion(item.id, item.title, faroese) }
        assertEquals(ContainerConversion.JOB_TYPE, job.type)
        assertEquals("done", waitForRow(job.id))

        val latest = assertNotNull(queue.latestConversion(item.id))
        assertEquals(ContainerConversion.VERIFIED, latest.state)
        assertEquals(job.id, latest.jobId)
        assertTrue(latest.jellyfinPending && latest.radarrNotTaken, "what FR-8 could not finish is carried to the admin")
        val stored = runBlocking { store.resolve(item.id)!! }
        assertEquals("$folder/Film (2020).mkv", stored.path)
        assertEquals("fao", stored.tracks.first { it.specifier == "0:a:0" }.language, "the page reads the tag the conversion wrote")
        assertEquals(0, stored.issueCount)
    }

    @Test fun `a block found by the job is its own state and leaves the original alone`() {
        val item = film()
        queue.containerConverter = ContainerConverter(Ports(seeding = SeedingCheckResult.Blocked("Some Torrent")))
        queue.start()
        val job = runBlocking { queue.enqueueContainerConversion(item.id, item.title, faroese) }
        assertEquals("failed", waitForRow(job.id))

        val latest = assertNotNull(queue.latestConversion(item.id))
        assertEquals(ContainerConversion.BLOCKED to ContainerConversion.SEEDED, latest.state to latest.reason)
        assertTrue(SystemFileSystem.exists(Path(avi)))
        assertFalse(SystemFileSystem.exists(Path("$folder/Film (2020).mkv")))
    }

    @Test fun `a second press while one is queued returns that job and a restart closes a running one as a failure`() {
        val item = film()
        queue.containerConverter = ContainerConverter(Ports())
        val first = runBlocking { queue.enqueueContainerConversion(item.id, item.title, faroese) }
        val again = runBlocking { queue.enqueueContainerConversion(item.id, item.title, faroese) }
        assertEquals(first.id, again.id)
        assertEquals(1, db.mediaJobQueries.listQueued().executeAsList().count { it.type == ContainerConversion.JOB_TYPE })

        db.mediaJobQueries.markRunning(1L, first.id)
        queue.recoverAfterRestart()
        val row = db.mediaJobQueries.findById(first.id).executeAsOne()
        assertEquals("failed", row.state)
        assertEquals(MediaJobQueue.CONVERT_RESTART_ERROR, row.error)
        assertEquals(ContainerConversion.FAILED_TOOL, queue.latestConversion(item.id)?.state)
        assertNull(db.mediaJobQueries.activeConvertForMedia(item.id).executeAsOneOrNull())
    }
}
