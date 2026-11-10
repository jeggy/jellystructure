package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.desktop.DesktopPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A Jellyfin user id is hex, but a file name built from one must never escape the directory. */
private fun musicSnapshotFile(userId: String) =
    File(DesktopPaths.dataDir, "music_snapshot_${userId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

actual object MusicSnapshotCache : MusicSnapshotStorage {
    actual override fun read(userId: String): String? =
        runCatching { musicSnapshotFile(userId).takeIf { it.isFile }?.readText() }.getOrNull()

    actual override fun write(userId: String, json: String) {
        runCatching {
            val f = musicSnapshotFile(userId)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(json)
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    actual override fun delete(userId: String) {
        runCatching { musicSnapshotFile(userId).delete() }
    }
}
