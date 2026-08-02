package dev.jellystructure.db

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.SynchronousFlag
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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
    )
    val db = JellystructureDb(NativeSqliteDriver(config, maxReaderConnections = 4))
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

fun JellystructureDb.walCheckpoint() {
    mediaQueries.walCheckpoint()
}
