package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.RaviloAppContext
import java.io.File

private fun snapshotFile(userId: String): File =
    File(RaviloAppContext.get().filesDir, "home_snapshot_$userId.json")

actual object HomeSnapshotCache {
    actual fun load(userId: String): HomeSnapshot? {
        val file = snapshotFile(userId)
        if (!file.exists()) return null
        val snapshot = runCatching { homeSnapshotJson.decodeFromString<HomeSnapshot>(file.readText()) }
            .getOrNull() ?: return null
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        return snapshot.takeIf { isSnapshotFresh(it, now) }
    }

    actual fun save(userId: String, snapshot: HomeSnapshot) {
        val json = runCatching { homeSnapshotJson.encodeToString(snapshot) }.getOrNull() ?: return
        if (exceedsSnapshotSizeCap(json)) return
        val file = snapshotFile(userId)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        // Write-then-rename: an atomic overwrite, never a partially-written file left behind by a
        // crash mid-write.
        runCatching {
            tmp.writeText(json)
            tmp.renameTo(file)
        }
    }

    actual fun clear(userId: String) {
        snapshotFile(userId).delete()
    }
}
