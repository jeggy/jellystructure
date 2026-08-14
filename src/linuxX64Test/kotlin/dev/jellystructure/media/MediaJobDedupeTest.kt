package dev.jellystructure.media

import dev.jellystructure.db.createDatabase
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Phase 164 (FR-164-1) — proves the `media_job_dedupe_active` partial unique index (`33.sqm`) is what
 * actually enforces "the same item is never queued/running twice," not a check-then-insert in Kotlin
 * (which two concurrent enqueues — a pipeline run and an operator clicking "detect again" — could both
 * pass). Exercises the SQL layer directly against a real (scratch, file-backed) database rather than
 * going through [MediaJobQueue]'s coroutine machinery, which needs a live appScope/broadcaster/etc. this
 * test has no use for.
 */
class MediaJobDedupeTest {
    private lateinit var dbPath: String
    private lateinit var db: dev.jellystructure.db.JellystructureDb

    @BeforeTest
    fun setUp() {
        // getpid() keeps this collision-free across parallel test processes; the test also cleans up
        // its own file afterward, so a stale one from a killed run is the only way this ever leaks.
        dbPath = "/tmp/jellystructure-test-mediajob-${getpid()}.db"
        db = createDatabase(dbPath)
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) {
            runCatching { platform.posix.remove("$dbPath$suffix") }
        }
    }

    private fun insertQueued(id: String, dedupeKey: String) {
        db.mediaJobQueries.insert(
            id = id, type = "segments_season", media_id = "some-series", label = "Some Series S01",
            params = "{}", state = "queued", enqueued_by = "admin", created_at = dev.jellystructure.nowEpochSec(),
            file_count = 8, lane = "segments", dedupe_key = dedupeKey,
        )
    }

    @Test
    fun secondActiveEnqueueUnderTheSameDedupeKeyIsRejected() {
        insertQueued("mj-1", "seg:season:some-series:1")
        assertFailsWith<Throwable> {
            insertQueued("mj-2", "seg:season:some-series:1")
        }
        // The first row is untouched and still the only active one under this key.
        val active = db.mediaJobQueries.findByDedupeKeyActive("seg:season:some-series:1").executeAsOneOrNull()
        assertNotNull(active)
        kotlin.test.assertEquals("mj-1", active.id)
    }

    @Test
    fun aDifferentDedupeKeyIsUnaffected() {
        insertQueued("mj-1", "seg:season:some-series:1")
        // Must not throw — a different season is a different unit of work.
        insertQueued("mj-2", "seg:season:some-series:2")
        kotlin.test.assertEquals(2, db.mediaJobQueries.listQueuedByLane("segments").executeAsList().size)
    }

    @Test
    fun onceTheFirstReachesATerminalStateTheKeyIsEnqueueableAgain() {
        insertQueued("mj-1", "seg:season:some-series:1")
        db.mediaJobQueries.markFinished("done", dev.jellystructure.nowEpochSec(), null, 8, "mj-1")

        // No longer 'queued'/'running' — the partial index no longer applies to it, so the SAME key can
        // be reused by a fresh row (e.g. a later pipeline run re-detecting the same season).
        insertQueued("mj-2", "seg:season:some-series:1")
        val active = db.mediaJobQueries.findByDedupeKeyActive("seg:season:some-series:1").executeAsOneOrNull()
        assertNotNull(active)
        kotlin.test.assertEquals("mj-2", active.id)
    }

    @Test
    fun rowsWithNoDedupeKeyNeverCollide() {
        // The media lane's rows all have dedupe_key = NULL — SQLite's UNIQUE index treats every NULL as
        // distinct, so two media-lane jobs for the exact same file must never be blocked by this index.
        db.mediaJobQueries.insert(
            id = "mj-a", type = "reorder", media_id = "movie-1", label = "A Movie",
            params = "{}", state = "queued", enqueued_by = "admin", created_at = dev.jellystructure.nowEpochSec(),
            file_count = 1, lane = "media", dedupe_key = null,
        )
        db.mediaJobQueries.insert(
            id = "mj-b", type = "reorder", media_id = "movie-1", label = "A Movie",
            params = "{}", state = "queued", enqueued_by = "admin", created_at = dev.jellystructure.nowEpochSec(),
            file_count = 1, lane = "media", dedupe_key = null,
        )
        kotlin.test.assertEquals(2, db.mediaJobQueries.listQueuedByLane("media").executeAsList().size)
    }
}
