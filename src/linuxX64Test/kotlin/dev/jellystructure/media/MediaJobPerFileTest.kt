package dev.jellystructure.media

import dev.jellystructure.db.createDatabase
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 261 (FR-261-1/2, dev review items 1–3, acceptance 2) — at the SQL layer, like [MediaJobEmptyQueueTest]:
 * the same file queued twice for the same check is one row; the claim reads one candidate per lane in
 * priority order (cadence work behind everything, an operator's Check now first) and skips a deferring row
 * only while holding; Check now raises a waiting cadence row; the Jobs view's flat lists leave per-file rows
 * to their group line, except a failure.
 */
class MediaJobPerFileTest {
    private lateinit var dbPath: String
    private lateinit var db: dev.jellystructure.db.JellystructureDb
    private val q get() = db.mediaJobQueries
    private val perFile = FileCheckSchedule.JOB_TYPES

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-perfile-${getpid()}.db"
        db = createDatabase(dbPath)
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun insert(id: String, type: String, createdAt: Long, priority: Long = 0, defer: Boolean = false, dedupe: String? = null, lane: String = "segments") =
        q.insert(id = id, type = type, media_id = "m-$id", label = id, params = "{}", state = "queued", enqueued_by = "pipeline",
            created_at = createdAt, file_count = 1, lane = lane, dedupe_key = dedupe, defer_while_playing = if (defer) 1 else 0, priority = priority)

    private fun next(lane: String = "segments", holding: Boolean = false) =
        (if (holding) q.nextQueuedNotDeferred(lane) else q.nextQueued(lane)).executeAsOneOrNull()?.id

    @Test
    fun `the same file queued twice for the same check is one row`() {
        val key = FileCheckSchedule.dedupeKey(FileCheckSchedule.VERIFY_JOB, "/m/a.mkv")
        insert("v1", FileCheckSchedule.VERIFY_JOB, 10, dedupe = key)
        runCatching { insert("v2", FileCheckSchedule.VERIFY_JOB, 11, dedupe = key) }
        // The other check on the same file is its own row.
        insert("l1", FileCheckSchedule.LENGTHS_JOB, 12, dedupe = FileCheckSchedule.dedupeKey(FileCheckSchedule.LENGTHS_JOB, "/m/a.mkv"))
        assertEquals(listOf("v1", "l1"), q.listQueuedByLane("segments").executeAsList().map { it.id })
    }

    @Test
    fun `the claim takes one candidate in priority order — then age — then insertion`() {
        insert("cadence-old", FileCheckSchedule.VERIFY_JOB, 1, priority = FileCheckSchedule.PRIORITY_CADENCE)
        insert("season", "segments_season", 50)
        insert("new-a", FileCheckSchedule.VERIFY_JOB, 60, priority = FileCheckSchedule.PRIORITY_NEW)
        insert("new-b", FileCheckSchedule.VERIFY_JOB, 60, priority = FileCheckSchedule.PRIORITY_NEW)   // same second: insertion order
        // Cadence work waits for everything else in the lane however old it is (dev review item 2).
        assertEquals("season", next())
        q.markRunning(70, "season"); q.markFinished("done", 71, null, 1, "season")
        assertEquals("new-a", next())
        q.markRunning(72, "new-a"); q.markFinished("done", 73, null, 1, "new-a")
        assertEquals("new-b", next())
        // An operator's Check now goes first, even when queued last.
        insert("operator", FileCheckSchedule.VERIFY_JOB, 99, priority = FileCheckSchedule.PRIORITY_OPERATOR)
        assertEquals("operator", next())
        assertNull(next("media"), "another lane is its own queue")
    }

    @Test
    fun `while holding for playback a deferring row yields to the next one that does not`() {
        insert("deferring", FileCheckSchedule.VERIFY_JOB, 1, priority = FileCheckSchedule.PRIORITY_NEW, defer = true)
        insert("operator-season", "segments_season", 5, defer = false)
        assertEquals("deferring", next())
        assertEquals("operator-season", next(holding = true))
        q.markRunning(6, "operator-season"); q.markFinished("done", 7, null, 1, "operator-season")
        assertNull(next(holding = true), "nothing that may run while a TV plays")
    }

    @Test
    fun `Check now raises a waiting cadence row instead of answering already queued`() {
        val key = FileCheckSchedule.dedupeKey(FileCheckSchedule.VERIFY_JOB, "/m/b.mkv")
        insert("cadence", FileCheckSchedule.VERIFY_JOB, 1, priority = FileCheckSchedule.PRIORITY_CADENCE, defer = true, dedupe = key)
        insert("season", "segments_season", 2)
        val raised = q.transactionWithResult { q.promoteQueued(FileCheckSchedule.PRIORITY_OPERATOR, key); q.changes().executeAsOne() }
        assertEquals(1L, raised)
        val row = q.findById("cadence").executeAsOne()
        assertEquals(FileCheckSchedule.PRIORITY_OPERATOR, row.priority)
        assertEquals(0L, row.defer_while_playing, "an operator's check never waits for playback")
        assertEquals("cadence", next(holding = true))
        // A second Check now changes nothing.
        assertEquals(0L, q.transactionWithResult { q.promoteQueued(FileCheckSchedule.PRIORITY_OPERATOR, key); q.changes().executeAsOne() })
    }

    @Test
    fun `the Jobs view lists per-file rows as a group — and a failed one in Recent`() {
        insert("season", "segments_season", 1)
        for (i in 1..5) insert("v$i", FileCheckSchedule.VERIFY_JOB, 10L + i)
        insert("l1", FileCheckSchedule.LENGTHS_JOB, 20)
        assertEquals(listOf("season"), q.listQueuedExcept(perFile).executeAsList().map { it.id })
        q.markRunning(30, "v1"); q.markFinished("done", 31, null, 1, "v1")
        q.markRunning(32, "v2"); q.markFinished("failed", 33, "ffmpeg could not read the file", 0, "v2")
        q.markRunning(34, "season"); q.markFinished("done", 35, null, 1, "season")
        assertEquals(listOf("season", "v2"), q.listRecentExcept(perFile, 20).executeAsList().map { it.id })
        val counts = q.groupCounts(perFile, 0).executeAsList().associate { (it.type to it.state) to it.n }
        assertEquals(3L, counts[FileCheckSchedule.VERIFY_JOB to "queued"])
        assertEquals(1L, counts[FileCheckSchedule.VERIFY_JOB to "done"])
        assertEquals(1L, counts[FileCheckSchedule.VERIFY_JOB to "failed"])
        assertEquals(1L, counts[FileCheckSchedule.LENGTHS_JOB to "queued"])
        assertEquals(listOf("v3", "v4", "v5"), q.listByTypeQueued(FileCheckSchedule.VERIFY_JOB, 10).executeAsList().map { it.id })
    }

    @Test
    fun `the subtitles health reads counts and the last failure without scanning Recent`() {
        insert("s1", "prewarm_subtitles", 1, defer = true, lane = "subtitles")
        assertEquals(0L, q.countQueuedNotDeferredByLane("subtitles").executeAsOne())
        q.markRunning(2, "s1"); q.markFinished("failed", 3, "Jellyfin timed out", 0, "s1")
        for (i in 1..60) { insert("v$i", FileCheckSchedule.VERIFY_JOB, 10L + i); q.markRunning(100L + i, "v$i"); q.markFinished("done", 200L + i, null, 1, "v$i") }
        assertEquals("Jellyfin timed out", q.lastFailure("subtitles").executeAsOneOrNull()?.error, "sixty newer verify rows do not hide it")
    }
}
