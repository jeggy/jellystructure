package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.RaviloAppContext
import java.io.File

private fun musicSnapshotFile(userId: String): File =
    File(RaviloAppContext.get().filesDir, "music_snapshot_$userId.json")

actual object MusicSnapshotCache : MusicSnapshotStorage {
    actual override fun read(userId: String): String? =
        runCatching { musicSnapshotFile(userId).takeIf { it.exists() }?.readText() }.getOrNull()

    actual override fun write(userId: String, json: String) {
        val file = musicSnapshotFile(userId)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        // Write-then-rename, as HomeSnapshotCache does: a crash mid-write never leaves a partial file.
        runCatching {
            tmp.writeText(json)
            tmp.renameTo(file)
        }
    }

    actual override fun delete(userId: String) {
        runCatching { musicSnapshotFile(userId).delete() }
    }
}
