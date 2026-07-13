package dev.jellystructure.media

import dev.jellystructure.arr.ArrRescanService
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.AppConfig
import dev.jellystructure.imdb.ImdbClient
import dev.jellystructure.model.ImdbRating
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.SegmentMarkers
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
            // Bug fix: writeEpisodeNfos groups by shared file first — a multi-episode file's contained
            // episodes get ONE combined NFO instead of each overwriting the last (see its doc comment).
            NfoWriter.writeEpisodeNfos(current.episodes, current.cast)
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

    /**
     * `detect_segments` (Phase 150, FR-SEG1-2/3/5) — chapter-title match, falling back to the ffmpeg
     * credits heuristic, for a movie or (per-episode) a series that doesn't already have segment data.
     * Never touches a `manuallyConfirmed` record, and skips a field that's already filled — the
     * resolution precedence is manual > chapter > numeric confidence (dev-review addendum §4), so once
     * something is set, only an explicit re-scan (which the admin scrubber's Re-scan button drives by
     * clearing the fields first) re-runs detection for it.
     *
     * Known scope limitation for this pass: a multi-episode file's episodes (`partCount > 1`) are
     * skipped entirely. Credits only make sense at the very end of the shared FILE (after the last
     * contained episode), and an intro/recap chapter only at its very start (before the first) — properly
     * windowing detection per-position within one shared file is real extra work deferred to a later
     * iteration; these files are the minority of a library, and skipping them leaves their episodes with
     * no segment data, exactly like every episode today.
     */
    private suspend fun detectForPath(path: String, current: SegmentMarkers): SegmentMarkers? {
        if (current.manuallyConfirmed) return null
        if (current.introStartMs != null || current.creditsStartMs != null) return null

        SegmentDetection.fromChapters(FfprobeRunner.chapters(path))?.let { hit ->
            return current.copy(
                introStartMs = hit.introStartMs,
                introEndMs = hit.introEndMs,
                creditsStartMs = hit.creditsStartMs,
                source = hit.source,
                confidence = hit.confidence,
            )
        }

        val duration = FfprobeRunner.duration(path) ?: return null
        val hit = SegmentDetection.fromCreditsHeuristic(path, duration) ?: return null
        return current.copy(creditsStartMs = hit.creditsStartMs, source = hit.source, confidence = hit.confidence)
    }

    suspend fun detectSegments(item: MediaItem, store: MediaStore) {
        when (item.kind) {
            MediaKind.MOVIE -> {
                val updated = detectForPath(item.path, item.segments) ?: return
                store.updateOne(item.copy(segments = updated))
            }
            MediaKind.TV_SHOW -> {
                var changed = false
                val updatedEpisodes = item.episodes.map { ep ->
                    if (ep.partCount > 1) return@map ep
                    val updated = detectForPath(ep.path, ep.segments) ?: return@map ep
                    changed = true
                    ep.copy(segments = updated)
                }
                if (changed) store.updateOne(item.copy(episodes = updatedEpisodes))
            }
        }
    }
}
