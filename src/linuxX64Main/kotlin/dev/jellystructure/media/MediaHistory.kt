package dev.jellystructure.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import platform.posix.time

@Serializable
data class HistoryEntry(
    val id: String,
    val mediaId: String,
    val timestamp: Long,
    val action: String,
    val detail: String,
)

class MediaHistory {
    private val mutex = Mutex()
    private val entries = ArrayDeque<HistoryEntry>()
    private var nextId = 1

    suspend fun record(mediaId: String, action: String, detail: String) = mutex.withLock {
        entries.addFirst(
            HistoryEntry(
                id = (nextId++).toString(),
                mediaId = mediaId,
                timestamp = epochSeconds(),
                action = action,
                detail = detail,
            )
        )
        if (entries.size > 2000) entries.removeLast()
    }

    fun forItem(mediaId: String): List<HistoryEntry> =
        entries.filter { it.mediaId == mediaId }

    fun recent(limit: Int = 8): List<HistoryEntry> =
        entries.take(limit)
}

@OptIn(ExperimentalForeignApi::class)
private fun epochSeconds(): Long = time(null)
