package dev.jellystructure.db

import platform.posix.getpid
import platform.posix.remove
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Live bug found in production (2026-08-25): `PRAGMA wal_checkpoint(TRUNCATE)` returns a result row, but
 * the generated `MediaQueries.walCheckpoint()` ran it through SQLDelight's non-query exec path, which
 * touchlab-sqliter's native driver rejects for any statement returning a result set — thrown every time,
 * silently swallowed by the caller's `runCatching`. See `Database.kt`'s `walCheckpoint()` doc comment.
 * Exercises the real fix (a manual `executeQuery` against the raw driver) against a real, file-backed DB.
 */
class WalCheckpointTest {
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-walcheckpoint-${getpid()}.db"
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) {
            runCatching { remove("$dbPath$suffix") }
        }
    }

    @Test
    fun `walCheckpoint runs without throwing`() {
        val db = createDatabase(dbPath)
        // Write something first so there's a real WAL to checkpoint, not just an empty one.
        db.mediaQueries.upsert(
            id = "test-item", json = "{}", kind = "MOVIE", title = "Test", year = null,
            studio = null, network = null, issue_count = 0L, language_mix = 0L, scanned_at = 0L,
            tmdb_id = null, poster_path = null, episode_count = 0L, search_text = "test",
            last_examined_at = 0L, has_segments = 0L,
        )
        db.walCheckpoint()   // must not throw
    }
}
