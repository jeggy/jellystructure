package dev.jellystructure.media

import dev.jellystructure.db.JellystructureDb
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.Serializable

private const val HISTORY_CAP = 2000L

@Serializable
data class HistoryEntry(
    val id: String,
    val mediaId: String,
    val timestamp: Long,
    val action: String,
    val detail: String,
)

class MediaHistory(private val db: JellystructureDb) {

    suspend fun record(mediaId: String, action: String, detail: String) {
        db.mediaHistoryQueries.insert(
            media_id = mediaId,
            ts = epochSeconds(),
            action = action,
            detail = detail,
        )
        db.mediaHistoryQueries.trimToMax(HISTORY_CAP)
    }

    fun forItem(mediaId: String): List<HistoryEntry> =
        db.mediaHistoryQueries.forItem(mediaId).executeAsList().map { row ->
            HistoryEntry(
                id = row.id.toString(),
                mediaId = row.media_id,
                timestamp = row.ts,
                action = row.action,
                detail = row.detail,
            )
        }

    fun recent(limit: Int = 8): List<HistoryEntry> =
        db.mediaHistoryQueries.recent(limit.toLong()).executeAsList().map { row ->
            HistoryEntry(
                id = row.id.toString(),
                mediaId = row.media_id,
                timestamp = row.ts,
                action = row.action,
                detail = row.detail,
            )
        }
}

@OptIn(ExperimentalForeignApi::class)
private fun epochSeconds(): Long = platform.posix.time(null)
