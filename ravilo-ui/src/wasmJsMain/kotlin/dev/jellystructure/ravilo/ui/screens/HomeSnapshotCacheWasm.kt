@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.screens

private fun jsGetSnapshot(key: String): String? = js("localStorage.getItem(key)")
private fun jsSetSnapshot(key: String, value: String): Unit = js("localStorage.setItem(key, value)")
private fun jsRemoveSnapshot(key: String): Unit = js("localStorage.removeItem(key)")

actual object HomeSnapshotCache {
    actual fun load(userId: String): HomeSnapshot? {
        val raw = jsGetSnapshot("ravilo_home_snapshot_$userId") ?: return null
        val snapshot = runCatching { homeSnapshotJson.decodeFromString<HomeSnapshot>(raw) }.getOrNull() ?: return null
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        return snapshot.takeIf { isSnapshotFresh(it, now) }
    }

    actual fun save(userId: String, snapshot: HomeSnapshot) {
        val json = runCatching { homeSnapshotJson.encodeToString(snapshot) }.getOrNull() ?: return
        if (exceedsSnapshotSizeCap(json)) return
        jsSetSnapshot("ravilo_home_snapshot_$userId", json)
    }

    actual fun clear(userId: String) {
        jsRemoveSnapshot("ravilo_home_snapshot_$userId")
    }
}
