package dev.jellystructure.torrent

import dev.jellystructure.io.FileIo
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phase 178 §FR-178-3 — "the pre-change mode is persisted, not held in memory, and the restore is
 * attempted on startup". A tiny JSON-file store (the [dev.jellystructure.tv.LiveTvStore] pattern — a
 * small, non-catalog blob, atomic tmp-then-rename writes) rather than a DB row: presence of the file
 * itself IS "we changed qBittorrent's speed-limits mode and haven't restored it yet"; absence means
 * either we never changed it, or we already restored it cleanly.
 */
@Serializable
private data class ThrottleState(val priorMode: Int)

class PlaybackThrottleStore(private val filePath: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private var state: ThrottleState? = null

    fun load() {
        val path = Path(filePath)
        if (!SystemFileSystem.exists(path)) return
        state = runCatching { json.decodeFromString<ThrottleState>(FileIo.readText(path)) }.getOrNull()
    }

    /** True iff WE currently have qBittorrent in a mode other than what it was before we touched it. */
    val weChangedIt: Boolean get() = state != null

    /** The mode to restore to, or null if [weChangedIt] is false. */
    val priorMode: Int? get() = state?.priorMode

    fun markChanged(priorMode: Int) {
        state = ThrottleState(priorMode)
        persist()
    }

    fun clear() {
        if (state == null) return
        state = null
        runCatching { SystemFileSystem.delete(Path(filePath)) }
    }

    private fun persist() {
        val tmp = Path("$filePath.tmp")
        val target = Path(filePath)
        FileIo.writeText(tmp, json.encodeToString(state))
        SystemFileSystem.atomicMove(tmp, target)
    }
}
