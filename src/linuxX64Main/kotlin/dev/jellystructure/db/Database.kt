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
    return JellystructureDb(NativeSqliteDriver(config))
}
