package dev.jellystructure.db

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration
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
            wrapConnection(conn) { JellystructureDb.Schema.migrate(it, old.toLong(), new.toLong()) }
        },
        extendedConfig = DatabaseConfiguration.Extended(basePath = parentDir.ifEmpty { null }),
    )
    val driver = NativeSqliteDriver(config)
    // Defensive: if the DB was created before user_version tracking was in place,
    // Schema.create() ran as a no-op (CREATE TABLE IF NOT EXISTS) and the ALTER TABLE
    // in 1.sqm was never applied. Probe for the v2 columns and add them if missing.
    runCatching { driver.execute(null, "SELECT revertable FROM media_history LIMIT 1", 0) }
        .onFailure {
            driver.execute(null, "ALTER TABLE media_history ADD COLUMN revertable INTEGER NOT NULL DEFAULT 0", 0)
            driver.execute(null, "ALTER TABLE media_history ADD COLUMN before_snapshot TEXT NOT NULL DEFAULT ''", 0)
        }
    return JellystructureDb(driver)
}
