package dev.jellystructure.arr

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.shared.tv.AcquisitionEpisodeRec
import dev.jellystructure.shared.tv.AcquisitionFlags
import dev.jellystructure.shared.tv.AcquisitionRecord
import dev.jellystructure.shared.tv.AcquisitionStatus
import dev.jellystructure.shared.tv.MediaKind

/**
 * Phase 56 — persistence for acquisition records (+ the per-episode child rows for series).
 * Maps the SQLDelight rows to/from the shared [AcquisitionRecord] DTO.
 */
class AcquisitionStore(private val db: JellystructureDb) {
    private val q get() = db.acquisitionQueries

    fun get(itemKey: String): AcquisitionRecord? = q.getByKey(itemKey).executeAsOneOrNull()?.let(::toRecord)

    fun getByTmdb(tmdbId: Int): AcquisitionRecord? = q.getByTmdb(tmdbId.toLong()).executeAsOneOrNull()?.let(::toRecord)

    fun getMany(keys: List<String>): List<AcquisitionRecord> =
        if (keys.isEmpty()) emptyList() else q.getByKeys(keys).executeAsList().map(::toRecord)

    fun all(): List<AcquisitionRecord> = q.all().executeAsList().map(::toRecord)

    /** Non-terminal records the reconciler must poll, with the raw *arr handles it needs. */
    fun active(): List<RawActive> = q.active().executeAsList().map { r ->
        RawActive(r.item_key, r.arr_kind, r.arr_id?.toInt(), r.tmdb_id?.toInt(), MediaKind.valueOf(r.media_kind))
    }

    /** The raw *arr handle for a record (not part of the public DTO). */
    fun handle(itemKey: String): RawActive? = q.getByKey(itemKey).executeAsOneOrNull()?.let { r ->
        RawActive(r.item_key, r.arr_kind, r.arr_id?.toInt(), r.tmdb_id?.toInt(), MediaKind.valueOf(r.media_kind))
    }

    /** Insert or update, preserving the original `requested_at` across reconciler updates. */
    fun save(rec: AcquisitionRecord, arrKind: String, arrId: Int?, now: Long) {
        val requestedAt = q.getByKey(rec.itemKey).executeAsOneOrNull()?.requested_at ?: now
        q.transaction {
            q.upsert(
                item_key = rec.itemKey,
                media_kind = rec.mediaKind.name,
                title = rec.title,
                tmdb_id = rec.tmdbId?.toLong(),
                arr_id = arrId?.toLong(),
                arr_kind = arrKind,
                item_id = rec.itemId,
                status = rec.status.name,
                progress = rec.progress.toLong(),
                queue_position = rec.queuePosition?.toLong(),
                flags = flagsToCsv(rec.flags),
                episodes_total = rec.episodesTotal.toLong(),
                episodes_done = rec.episodesDone.toLong(),
                first_available = if (rec.firstAvailable) 1L else 0L,
                reason = rec.reason,
                requested_by = rec.requestedBy,
                requested_at = requestedAt,
                updated_at = now,
            )
            q.deleteEpisodesForParent(rec.itemKey)
            for (e in rec.episodes) {
                q.upsertEpisode(rec.itemKey, e.season.toLong(), e.episode.toLong(), e.status.name, e.progress.toLong(), null)
            }
        }
    }

    fun delete(itemKey: String) = q.transaction {
        q.deleteEpisodesForParent(itemKey)
        q.deleteByKey(itemKey)
    }

    data class RawActive(
        val itemKey: String,
        val arrKind: String,
        val arrId: Int?,
        val tmdbId: Int?,
        val mediaKind: MediaKind,
    )

    private fun toRecord(r: dev.jellystructure.db.Acquisition): AcquisitionRecord {
        val eps = q.episodesForParent(r.item_key).executeAsList().map {
            AcquisitionEpisodeRec(it.season.toInt(), it.episode.toInt(), AcquisitionStatus.valueOf(it.status), it.progress.toInt())
        }
        return AcquisitionRecord(
            itemKey = r.item_key,
            mediaKind = MediaKind.valueOf(r.media_kind),
            status = AcquisitionStatus.valueOf(r.status),
            tmdbId = r.tmdb_id?.toInt(),
            title = r.title,
            progress = r.progress.toInt(),
            queuePosition = r.queue_position?.toInt(),
            flags = csvToFlags(r.flags),
            episodesTotal = r.episodes_total.toInt(),
            episodesDone = r.episodes_done.toInt(),
            firstAvailable = r.first_available != 0L,
            itemId = r.item_id,
            reason = r.reason,
            retryable = r.status == AcquisitionStatus.FAILED.name,
            requestedBy = r.requested_by,
            episodes = eps,
        )
    }

    private fun flagsToCsv(f: AcquisitionFlags): String =
        listOfNotNull(if (f.stalled) "stalled" else null, if (f.metadata) "metadata" else null).joinToString(",")

    private fun csvToFlags(s: String): AcquisitionFlags {
        val parts = s.split(",")
        return AcquisitionFlags(stalled = "stalled" in parts, metadata = "metadata" in parts)
    }
}
