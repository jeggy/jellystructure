package dev.jellystructure.nfo

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.AppConfig
import dev.jellystructure.model.MediaItem
import kotlinx.serialization.Serializable

/** Phase 115 — the three real states of the DB → NFO → Jellyfin sync chain, replacing a blunt
 *  DB-vs-Jellyfin field compare that couldn't tell "haven't written yet" from "haven't synced yet"
 *  from real external drift. Shared by the detail-page banner endpoint and the pipeline's detect_drift
 *  step so both report exactly the same thing. */
enum class DriftState { CONVERGED, NFO_STALE, JELLYFIN_BEHIND, EXTERNAL_DRIFT }

@Serializable
data class DriftField(val field: String, val inJellyfin: String, val inDb: String)

@Serializable
data class DriftResult(
    val state: String,          // DriftState.name, lowercase — see DriftState
    val message: String,
    val fields: List<DriftField> = emptyList(),
)

object DriftEvaluator {
    suspend fun evaluate(item: MediaItem, jellyfinClient: JellyfinClient, cfg: AppConfig): DriftResult {
        // State 1 — NFO stale: the XML we'd generate right now doesn't match what we last wrote, so
        // there's nothing to compare against Jellyfin yet — this is the write-through model working as
        // designed (an edit not yet saved to disk), not drift.
        val wouldBeHash = NfoWriter.contentHash(item, cfg.apiKeys.jellyfinUrl, cfg.metadata.ageRatingCascade)
        if (wouldBeHash != item.nfoHash) {
            return DriftResult(DriftState.NFO_STALE.name.lowercase(), "Your edits aren't in the NFO yet.")
        }

        // State 2 — Jellyfin behind: the NFO is current, but no sync has happened since it was written
        // (or the write just happened and a sync hasn't caught up).
        if ((item.jfSyncedAt ?: 0L) < (item.nfoWrittenAt ?: 0L)) {
            return DriftResult(DriftState.JELLYFIN_BEHIND.name.lowercase(), "Jellyfin hasn't re-read the NFO yet.")
        }

        val jid = item.jellyfinId
        if (jid.isNullOrBlank() || cfg.apiKeys.jellyfinUrl.isBlank() || cfg.apiKeys.jellyfinToken.isBlank()) {
            return DriftResult(DriftState.CONVERGED.name.lowercase(), "")
        }
        val jItem = jellyfinClient.getItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, jid)
            ?: return DriftResult(DriftState.CONVERGED.name.lowercase(), "")

        // State 3 — external drift: NFO is current, a sync has happened, and live Jellyfin still
        // disagrees — someone edited it in Jellyfin (or another tool touched the NFO afterward).
        // Normalized compare: trimmed strings so whitespace never trips a false positive.
        val fields = buildList {
            val jfTitle = jItem.name.trim()
            val dbTitle = item.title.trim()
            if (jfTitle != dbTitle) add(DriftField("title", jfTitle, dbTitle))
            val jfYear = jItem.year?.toString() ?: ""
            val dbYear = item.year?.toString() ?: ""
            if (jfYear != dbYear) add(DriftField("year", jfYear, dbYear))
            val jfTmdb = jItem.providerIds?.tmdb?.trim() ?: ""
            val dbTmdb = item.tmdbId?.toString() ?: ""
            if (jfTmdb != dbTmdb) add(DriftField("tmdbId", jfTmdb, dbTmdb))
        }
        return if (fields.isEmpty()) DriftResult(DriftState.CONVERGED.name.lowercase(), "")
        else DriftResult(
            DriftState.EXTERNAL_DRIFT.name.lowercase(),
            "Jellyfin's metadata no longer matches the NFO Jellystructure wrote.",
            fields,
        )
    }
}
