package dev.jellystructure.media

import dev.jellystructure.db.createDatabase
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 260 (FR-260-2, dev review item 1) — at the SQL layer, like [MediaJobDedupeTest]: emptying a queue
 * cancels only what is WAITING in that queue and nothing else; the conditional claim loses the race
 * against an emptying (acceptance 3); the emptied rows leave Recent and free their dedupe key; and the
 * Recent record's sentence is what the spec says.
 */
class MediaJobEmptyQueueTest {
    private lateinit var dbPath: String
    private lateinit var db: dev.jellystructure.db.JellystructureDb
    private val q get() = db.mediaJobQueries

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-emptyqueue-${getpid()}.db"
        db = createDatabase(dbPath)
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    // `changes()` must read the same connection as the write: one transaction each, as the queue does.
    private fun claim(id: String, at: Long = 4_000L): Long = q.transactionWithResult { q.markRunning(at, id); q.changes().executeAsOne() }
    private fun empty(lane: String): Long = q.transactionWithResult { q.emptyLane(finished_at = 3_000L, lane = lane); q.changes().executeAsOne() }

    private fun insert(id: String, lane: String, state: String, dedupe: String? = null, type: String = "segments_season") {
        q.insert(id = id, type = type, media_id = "m-$id", label = "Title $id", params = "{}", state = state, enqueued_by = "admin",
            created_at = 1_000L + id.hashCode() % 100, file_count = 1, lane = lane, dedupe_key = dedupe)
        if (state == "running") q.markRunning(2_000L, id)
    }

    @Test
    fun `emptying one lane cancels its waiting rows only`() {
        insert("run-seg", "segments", "running", "seg:1")
        insert("q-seg-1", "segments", "queued", "seg:2"); insert("q-seg-2", "segments", "queued", "seg:3")
        insert("q-media", "media", "queued", null, type = "reorder"); insert("q-sub", "subtitles", "queued", "sub:1", type = "prewarm_subtitles")
        assertEquals(2L, empty("segments"))
        assertEquals("running", q.findById("run-seg").executeAsOne().state, "the running job is not read, touched or signalled")
        assertEquals(listOf("cancelled", "cancelled"), listOf("q-seg-1", "q-seg-2").map { q.findById(it).executeAsOne().state })
        assertEquals("emptied", q.findById("q-seg-1").executeAsOne().error)
        assertEquals(listOf("queued", "queued"), listOf("q-media", "q-sub").map { q.findById(it).executeAsOne().state }, "other queues untouched")
        // FR-260-2 — the dedupe key is free again: the same work can be queued later.
        assertNull(q.findByDedupeKeyActive("seg:2").executeAsOneOrNull())
        insert("q-seg-1b", "segments", "queued", "seg:2")
    }

    @Test
    fun `a claim that lost the race against an emptying changes nothing`() {
        insert("q-1", "segments", "queued", "seg:1")
        // The worker read the row as queued; the admin emptied the queue before its write landed.
        empty("segments")
        assertEquals(0L, claim("q-1"), "the conditional claim must not flip a cancelled row back to running")
        assertEquals("cancelled", q.findById("q-1").executeAsOne().state)
        // And a genuinely queued row claims exactly once.
        insert("q-2", "segments", "queued", "seg:2")
        assertEquals(1L, claim("q-2"))
        assertEquals(0L, claim("q-2", 4_001L), "a second claim of the same row is a no-op")
    }

    @Test
    fun `emptied rows leave Recent and the one record per queue takes their place`() {
        insert("q-1", "segments", "queued", "seg:1"); insert("q-2", "segments", "queued", "seg:2")
        insert("old-done", "segments", "queued", "seg:9"); q.markRunning(1L, "old-done"); q.markFinished("done", 2L, null, 1, "old-done")
        empty("segments")
        q.insertRecord(id = "rec-1", type = MediaJobQueue.QUEUE_EMPTIED_TYPE, media_id = "", label = emptiedLabel("segments", 2), params = "{}",
            enqueued_by = "jeggy", created_at = 3_000L, started_at = 3_000L, finished_at = 3_000L, file_count = 2, lane = "segments", speed = null)
        val recent = q.listRecent(20).executeAsList()
        assertEquals(listOf("rec-1", "old-done"), recent.map { it.id }, "the two emptied rows are not listed one by one")
        assertEquals("Emptied the segments queue · 2 waiting intro & credits detections removed", recent.first().label)
    }

    @Test
    fun `the record's sentence counts and names what was removed`() {
        assertEquals("Emptied the segments queue · 1 waiting intro & credits detection removed", emptiedLabel("segments", 1))
        assertEquals("Emptied the subtitles queue · 41 waiting subtitle pre-warms removed", emptiedLabel("subtitles", 41))
        assertEquals("Emptied the media queue · 3 waiting jobs removed", emptiedLabel("media", 3))
    }
}
