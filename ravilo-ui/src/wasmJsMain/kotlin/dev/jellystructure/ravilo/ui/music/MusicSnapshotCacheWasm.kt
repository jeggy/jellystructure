@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.music

private fun jsGetMusicSnapshot(key: String): String? = js("localStorage.getItem(key)")
private fun jsSetMusicSnapshot(key: String, value: String): Unit = js("localStorage.setItem(key, value)")
private fun jsRemoveMusicSnapshot(key: String): Unit = js("localStorage.removeItem(key)")

actual object MusicSnapshotCache : MusicSnapshotStorage {
    actual override fun read(userId: String): String? = runCatching { jsGetMusicSnapshot("ravilo_music_snapshot_$userId") }.getOrNull()

    // A full `localStorage` throws on write: the previous copy stays, and the page never hears of it.
    actual override fun write(userId: String, json: String) {
        runCatching { jsSetMusicSnapshot("ravilo_music_snapshot_$userId", json) }
    }

    actual override fun delete(userId: String) {
        runCatching { jsRemoveMusicSnapshot("ravilo_music_snapshot_$userId") }
    }
}
