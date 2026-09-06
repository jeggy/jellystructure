package dev.jellystructure.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.SynchronousFlag
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Phase 198 (FR-198-1) — a no-op [co.touchlab.sqliter.interop.Logger]. sqliter's own `NoneLogger` is
 * `internal`, so this reimplements it: both flags off, every write dropped.
 */
private object SilentSqliteLogger : co.touchlab.sqliter.interop.Logger {
    override val vActive: Boolean = false
    override val eActive: Boolean = false
    override fun trace(message: String) = Unit
    override fun vWrite(message: String) = Unit
    override fun eWrite(message: String, exception: Throwable?) = Unit
}

// Set once by createDatabase() (called exactly once at startup) — see walCheckpoint()'s doc comment for
// why this is needed instead of going through the generated MediaQueries.walCheckpoint().
private lateinit var rawDriver: SqlDriver

fun createDatabase(dbFile: String): JellystructureDb {
    val parentDir = dbFile.substringBeforeLast('/', missingDelimiterValue = "")
    val filename = dbFile.substringAfterLast('/')
    if (parentDir.isNotEmpty()) {
        SystemFileSystem.createDirectories(Path(parentDir))
    }
    val config = DatabaseConfiguration(
        name = filename,
        version = JellystructureDb.Schema.version.toInt(),
        create = { conn ->
            wrapConnection(conn) { JellystructureDb.Schema.create(it) }
        },
        upgrade = { conn, old, new ->
            wrapConnection(conn) { driver ->
                val startVersion: Long
                if (old <= 1) {
                    // DBs at user_version 0 or 1 may already have revertable/before_snapshot from
                    // a prior patch applied outside SQLDelight. migrateInternal triggers 1.sqm for
                    // any oldVersion <= 1, so apply it defensively and skip to startVersion=2.
                    // runCatching absorbs "duplicate column" if the column already exists.
                    runCatching {
                        driver.execute(null, "ALTER TABLE media_history ADD COLUMN revertable INTEGER NOT NULL DEFAULT 0", 0)
                    }
                    runCatching {
                        driver.execute(null, "ALTER TABLE media_history ADD COLUMN before_snapshot TEXT NOT NULL DEFAULT ''", 0)
                    }
                    startVersion = 2L
                } else {
                    startVersion = old.toLong()
                }
                if (startVersion < new.toLong()) {
                    JellystructureDb.Schema.migrate(driver, startVersion, new.toLong())
                }
            }
        },
        extendedConfig = DatabaseConfiguration.Extended(
            basePath = parentDir.ifEmpty { null },
            synchronousFlag = SynchronousFlag.NORMAL,
        ),
        // Phase 198 (FR-198-1) — silence sqliter's own logger. Its default (WarningLogger) `println`s
        // the message and then `printStackTrace()`s the exception straight to stdout on every SQLite
        // error, including ones we deliberately provoke and catch. Two reasons that is wrong here:
        //
        //  1. It is redundant. Every call site that can hit a SQLite error already catches it and
        //     reports it through jellystructure's own Logger, with context the raw dump lacks (which
        //     item, which job, which route). The driver's copy is ~40 unattributed frames in the
        //     container log.
        //  2. In the test binary it breaks the build. Kotlin/Native reports test results over the
        //     TeamCity service-message protocol on that same stdout, and `|` is that protocol's escape
        //     character — the driver's first line is literally
        //     "executeNonQuery error | error code SQLITE_CONSTRAINT". It corrupts the stream, Gradle
        //     attributes output to no test case and dies with a NullPointerException in
        //     TestOutputStore$Writer.mark, and the half-written store then yields "Multiple entries
        //     with same key" / "Buffer underflow" on the next run. No test is failing when this
        //     happens: MediaJobDedupeTest is *supposed* to trip a UNIQUE constraint (Phase 164's
        //     FR-164-1 proof that the index, not Kotlin, enforces dedupe).
        loggingConfig = DatabaseConfiguration.Logging(logger = SilentSqliteLogger),
    )
    val driver = NativeSqliteDriver(config, maxReaderConnections = 4)
    rawDriver = driver
    val db = JellystructureDb(driver)
    // Security fix (2026-08-02 review, finding M7) — jellystructure.db holds every live admin session
    // token IN PLAINTEXT (directly replayable as a cookie — unlike API keys, which are hashed) and
    // every device's Jellyfin user token, and was created at the platform-default mode (confirmed
    // world-readable, 0644, on the live host during the audit). SQLite creates -wal/-shm sidecar files
    // alongside the main one in WAL mode; lock those down too, ignoring ones that don't exist yet.
    for (suffix in listOf("", "-wal", "-shm")) {
        runCatching { platform.posix.chmod("$dbFile$suffix", "384".toUInt()) }  // 0600 octal = 384 decimal
    }
    return db
}

/**
 * Live bug found in production logs (2026-08-25) — this has been broken since it was introduced (Phase
 * 90, commit `42e01511`), unrelated to any recent change. `PRAGMA wal_checkpoint(TRUNCATE)` returns a
 * result row (busy/log/checkpointed), but the generated `MediaQueries.walCheckpoint()` (from a plain,
 * non-`SELECT` `.sq` statement) runs it through SQLDelight's non-query `execute()`/`executeUpdateDelete`
 * path — which touchlab-sqliter's native driver explicitly rejects for any statement that returns a
 * result set, throwing "Queries can be performed using SQLiteDatabase query or rawQuery methods only"
 * every single time this ran (caught by the caller's own `runCatching`, logged as "non-fatal", so it
 * silently never actually checkpointed). Confirmed via the `sqlite3` CLI against a copy of the real DB:
 * the PRAGMA itself is fine (`PRAGMA wal_checkpoint(TRUNCATE);` → `0|0|0`); this SQLite build also has no
 * `pragma_wal_checkpoint()` table-valued function to route around it that way (`SELECT * FROM
 * pragma_wal_checkpoint(...)` → "no such table"). Fixed by dropping the broken generated query (removed
 * from `Media.sq`) and running the PRAGMA through the driver's real query path instead — a plain
 * `executeQuery` call, consuming (and discarding) the one result row, exactly what SQLDelight's own
 * generated query methods do internally for a real `SELECT`.
 */
fun JellystructureDb.walCheckpoint() {
    rawDriver.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)", { cursor ->
        while (cursor.next().value) { /* one row: busy, log, checkpointed — nothing to read */ }
        QueryResult.Value(Unit)
    }, 0)
}
