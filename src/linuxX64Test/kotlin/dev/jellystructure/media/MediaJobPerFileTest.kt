package dev.jellystructure.media

import dev.jellystructure.db.createDatabase
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /** Phase 213's 2026-10-13 amendment (FR-213-8, as restated the same day) — the one state FR-213-7 left
     *  unreportable: a lane whose running row never finishes read exactly like a lane that was working, and
     *  on 2026-10-13 that cost four hours (DEB-897). The running-row age alone could not see it, because
     *  `markRunning` re-stamps `started_at` on every claim; the last-completion age can, and this is the
     *  regression test for the shape of that deadlock. */
    @Test
    fun `a lane cycling one row in place reports a rising completion age while its running age stays near zero`() {
        fun runningAt(now: Long): Map<String, Int> = q.oldestRunningStartedAtByLane().executeAsList()
            .mapNotNull { r -> r.started_at?.let { r.lane to (now - it).toInt() } }.toMap()
        fun completedAt(now: Long): Map<String, Int> = q.lastCompletionByLane().executeAsList()
            .mapNotNull { r -> r.finished_at?.let { r.lane to (now - it).toInt() } }.toMap()

        insert("a0", FileCheckSchedule.AVSYNC_JOB, 1, lane = FileCheckSchedule.AVSYNC_LANE)
        insert("a1", FileCheckSchedule.AVSYNC_JOB, 2, lane = FileCheckSchedule.AVSYNC_LANE)
        insert("a2", FileCheckSchedule.AVSYNC_JOB, 3, lane = FileCheckSchedule.AVSYNC_LANE)
        // 02:39 of the incident: the last row this lane ever finished.
        q.markRunning(100, "a0"); q.markFinished("done", 110, null, 1, "a0")

        // Then one row, claimed and re-queued in place every ~21 s, for four hours.
        var claimedAt = 120L
        while (claimedAt < 120 + 4 * 3600) {
            q.markRunning(claimedAt, "a1")
            q.requeueOne("a1")
            claimedAt += 21
        }
        q.markRunning(claimedAt, "a1")
        val now = claimedAt + 20

        assertEquals(20, runningAt(now)["avsync"], "the running age reads the CURRENT claim — 20 s, not four hours")
        assertEquals((now - 110).toInt(), completedAt(now)["avsync"], "and the completion age reads the deadlock: ${now - 110} s")
        assertTrue(completedAt(now).getValue("avsync") > 4 * 3600, "over four hours since this lane finished anything")
        // The other half of the one-request reading: the backlog that could not be claimed meanwhile.
        val counts = q.countByLaneState().executeAsList().associate { (it.lane to it.state) to it.n }
        assertEquals(1L, counts["avsync" to "queued"], "a2 never got claimed")
        assertEquals(1L, counts["avsync" to "running"])
    }

    /** FR-213-8 restated — a lane that has completed something recently reports a small age; one that has
     *  completed nothing reports none rather than an epoch-sized number; and an operator emptying a lane is
     *  not a completion, or a wedged lane would read as freshly productive the moment someone cleared it. */
    @Test
    fun `a lane that has finished nothing reports no completion age and emptying is not a completion`() {
        fun completedAt(now: Long): Map<String, Int> = q.lastCompletionByLane().executeAsList()
            .mapNotNull { r -> r.finished_at?.let { r.lane to (now - it).toInt() } }.toMap()

        insert("v1", FileCheckSchedule.VERIFY_JOB, 1)
        insert("s1", "prewarm_subtitles", 2, lane = "subtitles")
        assertEquals(emptyMap<String, Int>(), completedAt(100), "nothing has finished anywhere yet")

        q.markRunning(100, "v1"); q.markFinished("done", 105, null, 1, "v1")
        assertEquals(mapOf("segments" to 5), completedAt(110))
        assertNull(completedAt(110)["subtitles"], "a queued row is not a completion")

        // Emptying the subtitles lane: a cancelled row carrying `error = 'emptied'`, plus the marker row
        // the Recent list shows. Neither is work done, so neither starts this lane's clock.
        q.markFinished("cancelled", 200, "emptied", 0, "s1")
        q.insertRecord(
            id = "rec", type = "queue_emptied", media_id = "", label = "emptied 1 waiting job", params = "{}",
            enqueued_by = "admin", created_at = 200, started_at = 200, finished_at = 200, file_count = 1,
            lane = "subtitles", speed = null,
        )
        assertNull(completedAt(210)["subtitles"], "emptying a lane is an operator's action, not a completion")
        assertEquals(mapOf("segments" to 105), completedAt(210), "and it leaves the other lanes alone")
    }

    /** FR-213-8 — additive on the wire: the keys FR-213-7 put there are byte-for-byte what they were, and
     *  the new ones are `null`/`0` where there is nothing to say. `/api/health` is read by more than the
     *  admin, so this asserts the whole string rather than the presence of a key. */
    @Test
    fun `the job_queues block keeps its existing shape and adds the ages and the avsync count`() {
        val idle = JobQueueHealth(
            configuredWorkers = 2, activeWorkers = 2, mediaQueued = 0, segmentsQueued = 7,
            subtitlesQueued = 1, subtitlesDeferredByPlayback = true, subtitlesLastFailure = "Jellyfin timed out",
        ).toJson()
        assertEquals(
            """{"configured_workers":2,"active_workers":2,"media_queued":0,"segments_queued":7,"subtitles_queued":1,""" +
                """"subtitles_deferred_by_playback":true,"subtitles_last_failure":"Jellyfin timed out","avsync_queued":0,""" +
                """"media_completed_ago_s":null,"segments_completed_ago_s":null,"subtitles_completed_ago_s":null,"avsync_completed_ago_s":null,""" +
                """"media_running_for_s":null,"segments_running_for_s":null,"subtitles_running_for_s":null,"avsync_running_for_s":null}""",
            idle,
        )
        // The incident, as `/api/health` would have answered it: 4 079 queued, nothing completed for
        // 4 h 34 min, and a running row that claims to be 20 seconds old.
        val wedged = JobQueueHealth(
            configuredWorkers = 2, activeWorkers = 2, mediaQueued = 0, segmentsQueued = 0,
            subtitlesQueued = 0, subtitlesDeferredByPlayback = false, subtitlesLastFailure = null,
            avsyncQueued = 4079, avsyncCompletedAgoS = 16_440, segmentsCompletedAgoS = 61, avsyncRunningForS = 20,
        ).toJson()
        assertEquals(
            """{"configured_workers":2,"active_workers":2,"media_queued":0,"segments_queued":0,"subtitles_queued":0,""" +
                """"subtitles_deferred_by_playback":false,"subtitles_last_failure":null,"avsync_queued":4079,""" +
                """"media_completed_ago_s":null,"segments_completed_ago_s":61,"subtitles_completed_ago_s":null,"avsync_completed_ago_s":16440,""" +
                """"media_running_for_s":null,"segments_running_for_s":null,"subtitles_running_for_s":null,"avsync_running_for_s":20}""",
            wedged,
        )
    }
}
