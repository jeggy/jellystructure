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
    val revertable: Boolean = false,
    val beforeSnapshot: String = "",
)

class MediaHistory(private val db: JellystructureDb) {

    fun record(
        mediaId: String,
        action: String,
        detail: String,
        revertable: Boolean = false,
        beforeSnapshot: String = "",
    ) {
        db.mediaHistoryQueries.insert(
            media_id = mediaId,
            ts = epochSeconds(),
            action = action,
            detail = detail,
            revertable = if (revertable) 1L else 0L,
            before_snapshot = beforeSnapshot,
        )
        db.mediaHistoryQueries.trimToMax(HISTORY_CAP)
    }

    fun findById(entryId: Long): HistoryEntry? =
        db.mediaHistoryQueries.findById(entryId).executeAsOneOrNull()?.let { row ->
            HistoryEntry(
                id = row.id.toString(),
                mediaId = row.media_id,
                timestamp = row.ts,
                action = row.action,
                detail = row.detail,
                revertable = row.revertable != 0L,
                beforeSnapshot = row.before_snapshot,
            )
        }

    fun forItem(mediaId: String): List<HistoryEntry> =
        db.mediaHistoryQueries.forItem(mediaId).executeAsList().map { row ->
            HistoryEntry(
                id = row.id.toString(),
                mediaId = row.media_id,
                timestamp = row.ts,
                action = row.action,
                detail = row.detail,
                revertable = row.revertable != 0L,
                beforeSnapshot = row.before_snapshot,
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
                revertable = row.revertable != 0L,
                beforeSnapshot = row.before_snapshot,
            )
        }
}

@OptIn(ExperimentalForeignApi::class)
private fun epochSeconds(): Long = platform.posix.time(null)
