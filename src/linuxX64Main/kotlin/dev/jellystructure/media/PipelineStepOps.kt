package dev.jellystructure.media

import dev.jellystructure.arr.ArrRescanService
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.AppConfig
import dev.jellystructure.imdb.ImdbClient
import dev.jellystructure.model.ImdbRating
import dev.jellystructure.model.MediaItem
import dev.jellystructure.nfo.NfoWriter
import dev.jellystructure.nowEpochSec

/**
 * Phase 145 — the per-item core of each pipeline step, extracted so the **event path**
 * ([RealtimeIngestService], one triggered item) runs the **same** work as the scheduled
 * [dev.jellystructure.executePipeline] (over a working set) with no drift. The scheduled run keeps its
 * own scope-filtering, worker pools, counters and progress reporting; it now delegates the actual
 * per-item work here. The realtime run calls these directly under its own activity, so it never touches
 * the global (DB-backed, single-row) [ScanTracker] — a realtime ingest and a scheduled scan can run at
 * once. See specs/requirements/phase-145-event-driven-full-pipeline.md.
 */
object PipelineStepOps {

    /** `pull_tmdb` — re-pull TMDB metadata and store it. */
    suspend fun pullTmdb(item: MediaItem, scanner: Scanner, store: MediaStore) {
        scanner.rescanMetadata(item)?.let { store.addOrUpdate(it) }
    }

    /** `fetch_artwork` — download any missing poster/backdrop/logo/stills (reads the freshest copy).
     *  Bug fix: `fetch()` may just have created episode stills on disk (TMDB or a screengrab fallback —
     *  either is a real, valid still); `Episode.hasStill` is a persisted snapshot (Phase 121), so it must
     *  be re-stamped + saved here, or triage/Library/Dashboard keep reporting these episodes "missing"
     *  indefinitely (`stampHasStill` no-ops for movies). */
    suspend fun fetchArtwork(item: MediaItem, store: MediaStore, artwork: ArtworkDownloader) {
        val current = store.get(item.id) ?: item
        artwork.fetch(current)
        store.updateOne(artwork.stampHasStill(current))
    }

    /** `sync_imdb_ratings` — fetch + store the IMDb rating for an item that has an imdbId. Returns true
     *  when a rating was written. (The scheduled run throttles between items itself; this doesn't.) */
    suspend fun syncImdb(item: MediaItem, store: MediaStore, imdbClient: ImdbClient?): Boolean {
        val imdbId = item.imdbId ?: return false
        val fetched = imdbClient?.getRating(imdbId) ?: return false
        store.updateOne(item.copy(imdbRating = ImdbRating(fetched.aggregateRating, fetched.voteCount, nowEpochSec())))
        return true
    }

    enum class NfoResult { WRITTEN, UNCHANGED, FOREIGN_SKIPPED }

    /** `write_nfo` — content-hash-aware series NFO write (Phase 115). Rewrites when our own content
     *  changed; a foreign/hand-edited on-disk NFO is only replaced when [allowForeign].
     *  [includeEpisodes]: for a TV_SHOW, also (re)write each episode NFO — matches the write-through
     *  `pushToJellyfin` path, so an event-ingested series' new episodes get their NFOs. The scheduled
     *  bulk run leaves this false (series-only), preserving its existing behaviour. */
    suspend fun writeNfo(item: MediaItem, store: MediaStore, serverUrl: String, ageRatingCascade: List<String>, allowForeign: Boolean, includeEpisodes: Boolean = false): NfoResult {
        val current = store.get(item.id) ?: item
        val wouldBeHash = NfoWriter.contentHash(current, serverUrl, ageRatingCascade)
        val result = when {
            wouldBeHash == current.nfoHash -> NfoResult.UNCHANGED
            else -> {
                val onDiskHash = NfoWriter.onDiskHash(current)
                val isForeign = onDiskHash != null && onDiskHash != current.nfoHash
                if (isForeign && !allowForeign) NfoResult.FOREIGN_SKIPPED
                else {
                    val r = NfoWriter.writeTracked(current, serverUrl, ageRatingCascade).getOrThrow()
                    store.updateOne(current.copy(nfoWrittenAt = r.writtenAt, nfoHash = r.hash))
                    NfoResult.WRITTEN
                }
            }
        }
        if (includeEpisodes && current.kind == dev.jellystructure.model.MediaKind.TV_SHOW) {
            for (ep in current.episodes) runCatching { NfoWriter.writeEpisode(ep, current.cast) }
        }
        return result
    }

    /** `sync_jellyfin` — full Jellyfin refresh when this item has an unsynced NFO change. Returns true
     *  when a refresh was performed. */
    suspend fun syncJellyfin(item: MediaItem, store: MediaStore, jellyfinClient: JellyfinClient, cfg: AppConfig): Boolean {
        if ((item.nfoWrittenAt ?: 0L) <= (item.jfSyncedAt ?: 0L)) return false
        if (cfg.apiKeys.jellyfinUrl.isBlank() || cfg.apiKeys.jellyfinToken.isBlank()) return false
        val jid = item.jellyfinId ?: return false
        val ok = jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, jid, full = true)
        if (ok) store.updateOne(item.copy(jfSyncedAt = nowEpochSec()))
        return ok
    }

    /** `rescan_arr` — nudge Radarr/Sonarr to rescan the item's folder. */
    fun rescanArr(item: MediaItem, arrRescan: ArrRescanService) = arrRescan.nudge(item)
}
