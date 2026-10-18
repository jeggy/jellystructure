package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.jobs.MediaJobParams
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.torrent.SeedingSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * DEB-1674 — FR-213-1: a bulk audio reorder occupies the `media` queue on its own code path; the shared
 * worker pool never claims one. Before this, the row sat `queued` while it waited in `acquireBulkSlot`, the
 * pool could take it (`bulkRunning` still false), and `runClaimed` failed it with "Unknown job type".
 * A restart and a Retry reached the same failure by other doors.
 */
class BulkReorderPoolTest {
    private val dir = "/tmp/jellystructure-test-bulk-pool-${getpid()}"
    private lateinit var db: JellystructureDb
    private lateinit var queue: MediaJobQueue
    private lateinit var scope: CoroutineScope
    private val q get() = db.mediaJobQueries

    @BeforeTest
    fun setUp() {
        SystemFileSystem.createDirectories(Path(dir))
        db = createDatabase("$dir/test.db")
        val configStore = ConfigStore("$dir/config.toml")
        scope = CoroutineScope(Dispatchers.Default)
        queue = MediaJobQueue(
            db, MediaStore(db, JsTagStore("$dir/tags.json"), configStore), WsBroadcaster(), JellyfinClient(), configStore, MediaHistory(db),
            SeedingGuard(SeedingSnapshot(configStore, QBittorrentClient())), null, scope,
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dev.jellystructure.db.closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private suspend fun registerBulk() = queue.registerBulkJob("series-1", "Bulk re-order", MediaJobParams(), 12)

    @Test
    fun `a registered bulk row is never returned by claimNext`() = runBlocking {
        val bulk = registerBulk()
        assertNull(queue.claimNext(), "the pool must not take a bulk row while it waits for its slot")
        assertEquals("queued", q.findById(bulk.id).executeAsOne().state)
        // The same lane's ordinary job behind it is still claimed — the bulk row does not block the lane's FIFO.
        val reorder = queue.enqueue("reorder", "m-1", "One reorder", MediaJobParams())
        assertEquals(reorder.id, queue.claimNext()?.id)
    }

    @Test
    fun `after a restart a queued or running bulk row is failed with the restart reason`() = runBlocking {
        val waiting = registerBulk()
        val running = registerBulk()
        q.markRunning(1L, running.id)
        val ordinary = queue.enqueue("reorder", "m-1", "One reorder", MediaJobParams())
        q.markRunning(1L, ordinary.id)

        queue.recoverAfterRestart()

        for (id in listOf(waiting.id, running.id)) {
            val row = q.findById(id).executeAsOne()
            assertEquals("failed", row.state)
            assertEquals("Interrupted by a restart — run the bulk reorder again", row.error)
            assertNotNull(row.finished_at)
        }
        assertEquals("queued", q.findById(ordinary.id).executeAsOne().state, "any other running row is still requeued")
        assertEquals(ordinary.id, queue.claimNext()?.id, "and the pool claims it — the failed bulk rows are not in its way")
    }

    @Test
    fun `retry of a bulk row returns null`() = runBlocking {
        val bulk = registerBulk()
        q.markFinished("failed", 2L, "boom", 0, bulk.id)
        assertNull(queue.retry(bulk.id))
        q.markFinished("cancelled", 2L, null, 0, bulk.id)
        assertNull(queue.retry(bulk.id))
        assertEquals(1, q.listRecent(20).executeAsList().count { it.type == "bulk_reorder" }, "no new row was enqueued")
    }
}
