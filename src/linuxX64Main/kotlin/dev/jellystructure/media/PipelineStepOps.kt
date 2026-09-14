package dev.jellystructure.media

import dev.jellystructure.arr.ArrRescanService
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.AppConfig
import dev.jellystructure.imdb.ImdbClient
import dev.jellystructure.log.Logger
import dev.jellystructure.model.Episode
import dev.jellystructure.model.ImdbRating
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
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

    /**
     * `pull_tmdb` — re-pull TMDB metadata and store it.
     *
     * Phase 183 (FR-183-5) — wraps the whole pull in a [dev.jellystructure.tmdb.TmdbExhaustionTracker]:
     * every TMDB call this item's scan makes funnels through `TmdbClient`'s one `httpGet`, so a rate-limit
     * exhaustion anywhere inside `rescanMetadata` (even one swallowed by an inner `runCatching{}` three
     * calls deep) is still visible here. When it fires, this item is marked dirty (Phase 181's own
     * persistent "needs work" set) so the next `RunTarget.Library` cycle retries it, instead of the gap
     * being silently treated as "TMDB genuinely has nothing" and settling forever.
     */
    suspend fun pullTmdb(item: MediaItem, scanner: Scanner, store: MediaStore, dirtyItemStore: DirtyItemStore) {
        val tracker = dev.jellystructure.tmdb.TmdbExhaustionTracker()
        val result = kotlinx.coroutines.withContext(tracker) { scanner.rescanMetadata(item) }
        result?.let { store.addOrUpdate(it) }
        val jellyfinId = item.jellyfinId
        if (tracker.hitCount > 0 && jellyfinId != null) {
            Logger.warn("pull_tmdb: '${item.id}' hit ${tracker.hitCount} TMDB rate-limit exhaustion(s) — marking dirty for retry", "tmdb")
            dirtyItemStore.markDirty(jellyfinId, "tmdb_rate_limited", store.nowMs())
        }
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

    /** Phase 207 (FR-207-3) — three outcomes where the old code only ever reported a count, which is
     *  exactly how a 400-on-every-call bug read as "nothing needed warming" for the step's entire
     *  lifetime. [Warmed] with `count == 0` still means the lookup succeeded and genuinely found no text
     *  subtitle streams — the case that must stay silent. [LookupFailed] means Jellyfin could not answer
     *  at all, which must not collapse into the same "0 warmed" summary line. */
    sealed interface PrewarmOutcome {
        data class Warmed(val count: Int) : PrewarmOutcome
        data object Skipped : PrewarmOutcome
        data object LookupFailed : PrewarmOutcome
    }

    /**
     * `prewarm_subtitles` (Phase 179, FR-179-1) — hit Jellyfin's `.../Subtitles/{index}/0/Stream.vtt`
     * extraction endpoint for every embedded text-subtitle stream ahead of any real playback, so its own
     * ffmpeg extraction cache is warm by the time a client asks (R183 measured a 4m37s cold extraction on
     * a 26 GB file; today it can additionally lose the race against a concurrent transcode reading the
     * same source file and hit a client-side HTTP timeout — see the phase's Root cause §4). Every text
     * stream, not just ones likely to transcode: which files transcode now depends on the *playing
     * device's* decode ceiling (Phase 177), not just the file, so there's no reliable narrower filter —
     * matches `detect_segments`/`fetch_artwork`'s existing "touch everything in the working set" pattern.
     * `isExternal` streams are skipped: a sidecar `.srt` is served as-is, never ffmpeg-extracted, so
     * there is nothing to warm. Silently a no-op with no Jellyfin connection configured (mirrors
     * `sync_jellyfin`'s own guard).
     *
     * Phase 207 (FR-207-1/2) — movies use [JellyfinClient.getItemMediaStreams]'s now-corrected `Ids=`
     * shape (one call). A TV_SHOW uses [JellyfinClient.getSeriesEpisodesMediaStreams] — **one call for
     * the whole series**, not one per episode (285 calls/14s for a single series, observed before this
     * fix) — and reads Jellyfin's own episode list directly rather than joining against
     * `item.episodes`, so an episode this scan hasn't backfilled a `jellyfinId` for is still warmed.
     *
     * Phase 210 (FR-210-2/FR-210-4) — [onStreamWarmed] fires the instant an individual stream is
     * *confirmed* warmed (a true return from [JellyfinClient.warmSubtitleExtraction]), not once at the
     * end of a normally-returning loop over an attempted count. Two reasons: an ordinary per-stream HTTP
     * failure must not be counted as a success (FR-210-2), and if the caller's own per-item deadline
     * cancels this call partway through a `TV_SHOW`'s episode list, whatever streams already succeeded
     * before that point must still be credited — the Jellyfin-side cache write already happened, and
     * losing that count to "0 warmed" would misreport real progress as none (FR-210-4).
     */
    suspend fun prewarmSubtitles(
        item: MediaItem,
        jellyfinClient: JellyfinClient,
        cfg: AppConfig,
        onStreamWarmed: () -> Unit = {},
    ): PrewarmOutcome {
        val base = cfg.apiKeys.jellyfinUrl
        val token = cfg.apiKeys.jellyfinToken
        if (base.isBlank() || token.isBlank()) return PrewarmOutcome.Skipped

        suspend fun warmedCountOf(streams: List<dev.jellystructure.auth.JellyfinMediaStream>, jellyfinId: String): Int {
            val textSubs = streams.filter {
                it.type.equals("Subtitle", ignoreCase = true) &&
                    !it.isExternal &&
                    (it.isTextSubtitleStream || dev.jellystructure.tv.isTextSubCodec(it.codec))
            }
            var confirmed = 0
            for (s in textSubs) {
                if (jellyfinClient.warmSubtitleExtraction(base, token, jellyfinId, s.index)) {
                    confirmed++
                    onStreamWarmed()
                }
            }
            return confirmed
        }

        return when (item.kind) {
            MediaKind.MOVIE -> {
                val id = item.jellyfinId?.takeIf { it.isNotBlank() } ?: return PrewarmOutcome.Skipped
                val detail = jellyfinClient.getItemMediaStreams(base, token, id)
                    ?: return PrewarmOutcome.LookupFailed
                PrewarmOutcome.Warmed(warmedCountOf(detail.mediaStreams, id))
            }
            MediaKind.TV_SHOW -> {
                val seriesId = item.jellyfinId?.takeIf { it.isNotBlank() } ?: return PrewarmOutcome.Skipped
                val episodes = jellyfinClient.getSeriesEpisodesMediaStreams(base, token, seriesId)
                    ?: return PrewarmOutcome.LookupFailed
                PrewarmOutcome.Warmed(episodes.sumOf { ep -> warmedCountOf(ep.mediaStreams, ep.id) })
            }
            MediaKind.MUSIC_VIDEO -> PrewarmOutcome.Skipped
        }
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
        } else if (current.kind == dev.jellystructure.model.MediaKind.TV_SHOW) {
            // Phase 153 — jellystructure owns Jellyfin's metadata; an episode it can confidently number
            // from its own filename parse but never got a jellyfinId for (Phase 152's Triage signal)
            // never had an NFO written at all on the routine scheduled run, since includeEpisodes is
            // false here. Repair just those files, without turning on a full per-episode rewrite for
            // the whole series every cycle. Write the WHOLE file's episode group (every partIndex
            // sibling), not just the unresolved episode, so a multi-episode file's combined NFO stays
            // complete even when only one of its contained episodes failed to join.
            // Phase 153 correction (FR-SCAN2-6): the original `jellyfinId == null` condition never fired —
            // every affected episode DOES have a jellyfinId; what's missing is Jellyfin's own IndexNumber
            // for it (jellyfinIndexMissing). Keep the null-id case too: it's the other way an episode can
            // be unreachable, and repairing it is the same write.
            val filesNeedingRepair = current.episodes
                .filter { it.episodeNumber != null && (it.jellyfinId == null || it.jellyfinIndexMissing) }
                .mapTo(mutableSetOf()) { it.path }
            if (filesNeedingRepair.isNotEmpty()) {
                NfoWriter.writeEpisodeNfos(current.episodes.filter { it.path in filesNeedingRepair }, current.cast)
                // Bump nfoWrittenAt even though the series-level tvshow.nfo content may be unchanged —
                // otherwise sync_jellyfin's staleness gate never notices this repair and never refreshes
                // Jellyfin, leaving the new episode NFO on disk but never read.
                store.updateOne((store.get(current.id) ?: current).copy(nfoWrittenAt = nowEpochSec()))
            }
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
        // Phase 153 (FR-SCAN2-7) — a series-level refresh is not assumed to re-resolve a child episode's
        // numbering, so refresh each unnumbered episode's OWN item. This is the exact call verified live
        // to turn IndexNumber=null into the right number (and to restore the series to Jellyfin's NextUp)
        // once write_nfo has put a <season>/<episode> NFO next to the file.
        for (ep in item.episodes.filter { it.jellyfinIndexMissing && it.episodeNumber != null }) {
            val epId = ep.jellyfinId ?: continue
            jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, epId, full = true)
            Logger.info("Repaired unnumbered episode in Jellyfin: ${item.title} S${ep.seasonNumber}E${ep.episodeNumber}", "nfo")
        }
        if (ok) store.updateOne(item.copy(jfSyncedAt = nowEpochSec()))
        return ok
    }

    /** `rescan_arr` — nudge Radarr/Sonarr to rescan the item's folder. */
    fun rescanArr(item: MediaItem, arrRescan: ArrRescanService) = arrRescan.nudge(item)

    enum class DriftOutcome { CONVERGED, NFO_STALE, JELLYFIN_BEHIND, EXTERNAL_DRIFT }

    /** `detect_drift` (Phase 115 FR F) — per-item state evaluation, extracted (Phase 175) out of
     *  `executePipeline`'s inline `when` block so the bulk pipeline loop and a realtime single-item run
     *  share one implementation. When [autoReassert] and the item is JELLYFIN_BEHIND, silently
     *  re-asserts NFO → Jellyfin (write NFO if needed, then a full refresh) instead of just reporting it. */
    suspend fun detectDrift(item: MediaItem, store: MediaStore, jellyfinClient: JellyfinClient, cfg: AppConfig, autoReassert: Boolean): DriftOutcome {
        val current = store.get(item.id) ?: item
        val result = dev.jellystructure.nfo.DriftEvaluator.evaluate(current, jellyfinClient, cfg)
        return when (result.state) {
            dev.jellystructure.nfo.DriftState.NFO_STALE.name.lowercase() -> DriftOutcome.NFO_STALE
            dev.jellystructure.nfo.DriftState.JELLYFIN_BEHIND.name.lowercase() -> {
                if (autoReassert) {
                    // Phase 193 (open question 1) — this used to build both updateOne calls from the same
                    // stale `current`, so the jfSyncedAt stamp below silently reverted the nfoWrittenAt/
                    // nfoHash the rewrite just recorded (the second copy() carried the PRE-rewrite values
                    // forward over them). Thread the rewritten item through instead of re-deriving from
                    // `current` twice. Never fired in production (auto_reassert=false) but latent either way.
                    var reasserted = current
                    runCatching { NfoWriter.writeTracked(current, cfg.apiKeys.jellyfinUrl, cfg.metadata.ageRatingCascade).getOrThrow() }
                        .onSuccess { r ->
                            reasserted = current.copy(nfoWrittenAt = r.writtenAt, nfoHash = r.hash)
                            store.updateOne(reasserted)
                        }
                    reasserted.jellyfinId?.let { jid ->
                        val ok = runCatching { jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, jid, full = true) }.getOrDefault(false)
                        if (ok) store.updateOne(reasserted.copy(jfSyncedAt = nowEpochSec()))
                    }
                }
                DriftOutcome.JELLYFIN_BEHIND
            }
            dev.jellystructure.nfo.DriftState.EXTERNAL_DRIFT.name.lowercase() -> DriftOutcome.EXTERNAL_DRIFT
            else -> DriftOutcome.CONVERGED
        }
    }

    /**
     * `detect_segments` (Phase 150, FR-SEG1-2/3/5; rewritten Phase 163 for the per-kind `media_segment`
     * table) — chapter-title match, falling back to the ffmpeg credits heuristic, for one movie or
     * episode path. Never writes over a LOCKED row (phase-151's guarantee, extended to segments —
     * dev-review addendum §3/§D — locking is now per KIND, not per title). [force] tells a routine
     * scheduled pass (false) from an explicit re-detect (true): false skips a kind that already has an
     * unlocked value (same cost-avoidance the old blob model always had — re-running the ffmpeg
     * heuristic for every item on every scheduled scan would reintroduce the CPU-starvation class of
     * incident this project has already hit twice from unthrottled per-item ffmpeg calls); true
     * re-derives even over an existing unlocked value, never a locked one.
     *
     * Known scope limitation, unchanged from phase 150: a multi-episode file's episodes (`partCount >
     * 1`) are skipped entirely by the caller ([detectChapterAndHeuristic]) — see its own doc.
     *
     * Records the raw detection evidence alongside every write (dev-review addendum §2's "full raw
     * evidence" decision) — every keyword-matching chapter (not just the two winners), and every
     * black-frame/silence interval the heuristic scanned (not just the accepted pair) — so the trim
     * view's evidence lane can show *why*, not just the final number.
     */
    private suspend fun detectForPath(
        itemId: String, episodeKey: String, episodeNumber: Int, path: String,
        segmentStore: MediaSegmentStore, extraChapterKeywords: List<String>, force: Boolean,
    ): Boolean {
        val existingIntro = segmentStore.getSegment(itemId, episodeKey, episodeNumber, SegmentKind.INTRO)
        val existingCredits = segmentStore.getSegment(itemId, episodeKey, episodeNumber, SegmentKind.CREDITS)
        val introWritable = existingIntro?.locked != true && (force || existingIntro == null)
        val creditsWritable = existingCredits?.locked != true && (force || existingCredits == null)
        if (!introWritable && !creditsWritable) return false

        // Phase 150 dev-review addendum §2 / settings card: admin-added words extend (never replace)
        // the built-in list, classified as CREDITS — the settings card exposes one flat keyword list
        // with no per-word intro/credits kind selector, and extra words are overwhelmingly going to be
        // credits synonyms in other languages (recap/previously/next-time are already built in).
        val keywords = if (extraChapterKeywords.isEmpty()) DEFAULT_CHAPTER_KEYWORDS
        else DEFAULT_CHAPTER_KEYWORDS + extraChapterKeywords.filter { it.isNotBlank() }.map { ChapterKeyword(it, ChapterSegmentKind.CREDITS) }

        // Phase 170 (§2) — a force re-detect must never let a lower-precedence source clobber a
        // higher-precedence existing row (see SegmentSource.precedence's doc comment).
        val introOverwriteOk = SegmentSource.precedence(SegmentSource.CHAPTER) >= SegmentSource.precedence(existingIntro?.source)
        val creditsChapterOverwriteOk = SegmentSource.precedence(SegmentSource.CHAPTER) >= SegmentSource.precedence(existingCredits?.source)
        val creditsHeuristicOverwriteOk = SegmentSource.precedence(SegmentSource.HEURISTIC) >= SegmentSource.precedence(existingCredits?.source)

        var wrote = false
        val chapterHit = SegmentDetection.fromChapters(FfprobeRunner.chapters(path), keywords)
        if (chapterHit != null) {
            if (introWritable && introOverwriteOk && chapterHit.markers.introStartMs != null) {
                segmentStore.clearEvidenceForKind(itemId, episodeKey, episodeNumber, SegmentKind.INTRO)
                for (ev in chapterHit.evidence) if (ev.kind == ChapterSegmentKind.INTRO) {
                    segmentStore.recordEvidence(itemId, episodeKey, episodeNumber, SegmentKind.INTRO, EvidenceType.CHAPTER_CANDIDATE, ev.startMs, ev.endMs, chapterEvidenceDetail(ev.title), ev.accepted)
                }
                segmentStore.upsertSegment(itemId, episodeKey, episodeNumber, SegmentKind.INTRO, chapterHit.markers.introStartMs, chapterHit.markers.introEndMs, SegmentSource.CHAPTER, null)
                wrote = true
            }
            if (creditsWritable && creditsChapterOverwriteOk && chapterHit.markers.creditsStartMs != null) {
                segmentStore.clearEvidenceForKind(itemId, episodeKey, episodeNumber, SegmentKind.CREDITS)
                for (ev in chapterHit.evidence) if (ev.kind == ChapterSegmentKind.CREDITS) {
                    segmentStore.recordEvidence(itemId, episodeKey, episodeNumber, SegmentKind.CREDITS, EvidenceType.CHAPTER_CANDIDATE, ev.startMs, ev.endMs, chapterEvidenceDetail(ev.title), ev.accepted)
                }
                segmentStore.upsertSegment(itemId, episodeKey, episodeNumber, SegmentKind.CREDITS, chapterHit.markers.creditsStartMs, null, SegmentSource.CHAPTER, null)
                wrote = true
            }
            // A chapter match (of EITHER kind) means the file has real chapter data — matches the old
            // behavior of stopping here rather than also running the (much more expensive) ffmpeg
            // heuristic on top.
            return wrote
        }

        if (!creditsWritable || !creditsHeuristicOverwriteOk) return false
        val duration = FfprobeRunner.duration(path) ?: return false
        val hit = SegmentDetection.fromCreditsHeuristic(path, duration) ?: return false
        segmentStore.clearEvidenceForKind(itemId, episodeKey, episodeNumber, SegmentKind.CREDITS)
        for (ev in hit.evidence) {
            val type = if (ev.type == "black_frame") EvidenceType.BLACK_FRAME else EvidenceType.SILENCE
            segmentStore.recordEvidence(itemId, episodeKey, episodeNumber, SegmentKind.CREDITS, type, ev.startMs, ev.endMs, null, ev.accepted)
        }
        segmentStore.upsertSegment(itemId, episodeKey, episodeNumber, SegmentKind.CREDITS, hit.startMs, null, SegmentSource.HEURISTIC, hit.confidence)
        return true
    }

    private fun chapterEvidenceDetail(title: String?): String? =
        title?.let { """{"title":${kotlinx.serialization.json.JsonPrimitive(it)}}""" }

    /** Cheap tier only (chapter-title match + the ffmpeg credits heuristic) — extracted from
     *  [detectSegments] so the pipeline can run it as its own pass over every item at the pool's
     *  normal per-item granularity, ahead of the much heavier per-season fingerprint pass (see
     *  [detectIntroFingerprintsForSeason]). No longer takes/returns a [MediaStore]/[MediaItem] to
     *  persist — [detectForPath] writes each kind's row straight to [MediaSegmentStore], so there's
     *  nothing left to merge back into the item blob. */
    suspend fun detectChapterAndHeuristic(
        item: MediaItem,
        segmentStore: MediaSegmentStore,
        extraChapterKeywords: List<String> = emptyList(),
        force: Boolean = false,
        // Phase 164 (FR-164-5) — the segments job queue's only cooperative-cancel hook: checked between
        // episodes (there is no mid-episode cancel point — one detectForPath call is seconds long at
        // most). onEpisodeDone feeds the job row's files_done/file_count so "episode 7 of 12" is real.
        // Both default to no-ops so every pre-164 call site (the bulk pipeline step, the segment REST
        // routes, RealtimeIngestService) is unaffected.
        isCancelled: () -> Boolean = { false },
        // Phase 170 (§3) — the segments lane never recorded anything to a title's own History tab, so
        // an auto-written marker was invisible there even though the ephemeral Activity/pipeline log
        // mentions it in passing. One roll-up entry per run (not per episode — a season's worth of
        // per-episode rows would flood a 2000-row-capped, revertable-edit-oriented log), only when
        // something was actually written. Defaults to null so no history is unavailable to a caller.
        mediaHistory: MediaHistory? = null,
        onEpisodeDone: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        when (item.kind) {
            MediaKind.MOVIE -> {
                val wrote = detectForPath(item.id, "", 0, item.path, segmentStore, extraChapterKeywords, force)
                onEpisodeDone(1, 1)
                if (wrote) mediaHistory?.record(item.id, "detect_segments", "chapter/heuristic detection wrote a marker")
            }
            MediaKind.TV_SHOW -> {
                val eligible = item.episodes.filter { it.partCount == 1 }
                var done = 0
                var wroteCount = 0
                for (ep in eligible) {
                    if (isCancelled()) break
                    if (detectForPath(item.id, ep.filename, ep.episodeNumber ?: 0, ep.path, segmentStore, extraChapterKeywords, force)) wroteCount++
                    done++
                    onEpisodeDone(done, eligible.size)
                }
                if (wroteCount > 0) mediaHistory?.record(item.id, "detect_segments", "chapter/heuristic detection wrote $wroteCount of ${eligible.size} episode(s)")
            }
            // Phase 168 (FR-168-6): never enqueued for detection — no-op if ever reached directly.
            MediaKind.MUSIC_VIDEO -> {}
        }
    }

    /** Single-item convenience wrapper (chapter/heuristic tier, then — for a series — the fingerprint
     *  tier across every season) for callers that process one item at a time outside the bulk pipeline's
     *  worker pool: the segment REST routes ([dev.jellystructure.server.routes.SegmentRoutes]) and
     *  [RealtimeIngestService]. The bulk `detect_segments` pipeline step does NOT call this — it runs
     *  the two tiers as separate `runPipelineStepPool` passes at different work-item granularities (see
     *  `Main.kt`'s `detect_segments` case) so a series with many seasons spreads its fingerprint work
     *  across the worker pool instead of running every season sequentially in one worker slot. */
    suspend fun detectSegments(
        item: MediaItem,
        segmentStore: MediaSegmentStore,
        extraChapterKeywords: List<String> = emptyList(),
        fingerprintService: FingerprintService? = null,
        detectFingerprint: Boolean = false,
        force: Boolean = false,
        reportDetail: suspend (String?) -> Unit = {},
    ) {
        detectChapterAndHeuristic(item, segmentStore, extraChapterKeywords, force)
        // FR-SEG1-4 — cross-episode audio fingerprinting (Skip Intro), gated on the detect_fingerprint
        // pipeline-step toggle: heavier (an fpcalc decode per episode) than the tier above.
        if (item.kind == MediaKind.TV_SHOW && detectFingerprint && fingerprintService != null) {
            for (seasonEpisodes in eligibleSeasons(item)) {
                detectIntroFingerprintsForSeason(item, segmentStore, fingerprintService, seasonEpisodes, force, reportDetail)
                // Phase 159 (FR-159-3) — outro/credits counterpart, same per-season worker-pool shape.
                detectOutroFingerprintsForSeason(item, segmentStore, fingerprintService, seasonEpisodes, force, reportDetail)
            }
        }
    }

    /** A series' `partCount == 1` episodes grouped by season, seasons with fewer than 2 such episodes
     *  dropped (FR-SEG1-4's own "series ≥2 episodes" scope — nothing to correlate otherwise). Shared by
     *  the single-item [detectSegments] wrapper and `Main.kt`'s bulk pipeline step, which builds its
     *  per-season work-item list from this same function so both paths always agree on what counts as
     *  a processable season. */
    fun eligibleSeasons(item: MediaItem): List<List<Episode>> =
        item.episodes.filter { it.partCount == 1 }.groupBy { it.seasonNumber }.values.filter { it.size >= 2 }

    /** One pairwise fingerprint candidate plus which OTHER episode it came from — Phase 163's evidence
     *  capture needs the paired episode's identity, which the old blob-merge design discarded the
     *  moment it collapsed a pair's match into a bare (startMs, endMs, confidence) candidate. Both
     *  episodes are already known at the [SegmentDetection.findIntroMatch] call site below; this just
     *  stops throwing that away. */
    private data class PairedCandidate(val candidate: SegmentDetection.IntroCandidate, val pairedEpisodeLabel: String)

    private fun episodeLabel(ep: Episode): String {
        val s = ep.seasonNumber?.toString()?.padStart(2, '0') ?: "??"
        val e = ep.episodeNumber?.toString()?.padStart(2, '0') ?: "??"
        return "S${s}E$e"
    }

    private fun pairedEpisodeDetail(label: String) = """{"pairedEpisode":${kotlinx.serialization.json.JsonPrimitive(label)}}"""

    /**
     * FR-SEG1-4, amended 2026-07-14 — cross-episode audio fingerprinting for a series' Skip Intro
     * bounds, via a **season-wide consensus** rather than one fixed reference episode. The original
     * design compared every episode against ONE reference (the season's lowest-numbered episode) —
     * cheaper (O(n) comparisons), but confirmed broken on real data: a season's premiere commonly has
     * an atypical intro cut (extended cold open, bonus footage, a different edit), so when it's the
     * reference, the WHOLE season fails to correlate even though the rest of the season's episodes
     * correlate cleanly with each other. Now every eligible episode is compared against every OTHER
     * episode in its season (not just one), and each episode's final bounds come from reconciling
     * every successful pairwise match it took part in via [SegmentDetection.aggregateIntroCandidates]
     * (cluster-by-proximity + majority-cluster median) — no single episode can take the rest of its
     * season down with it, and the result no longer rests on one comparison's luck.
     *
     * This is a deliberate speed-for-correctness tradeoff (explicit product direction: this runs as a
     * background pipeline step and processing time is not a priority) — comparison count grows from
     * O(n) to (bounded) O(n²) per season. Crucially this does NOT add any `fpcalc`/[ProcessGate] load:
     * each episode's fingerprint is still computed/cached exactly once ([FingerprintService.getOrCompute]
     * is memoized per-run below on top of its own on-disk cache); only the count of cheap, in-process,
     * no-I/O [SegmentDetection.findIntroMatch] calls increases.
     *
     * Amended again the same day — **scoped to one season, not a whole series** — so `Main.kt`'s bulk
     * pipeline step can dispatch each season of a series as its own work item across the worker pool
     * (a 10-season show now occupies up to 10 worker slots concurrently instead of monopolizing one for
     * however long all ten take sequentially). [seasonEpisodes] is exactly one season's `partCount == 1`
     * episodes (≥2 of them — see [eligibleSeasons]); pairwise correlation never crosses a season
     * boundary regardless of caller, so scoping the work item this way changes nothing about the
     * result, only how it's scheduled.
     *
     * Phase 163 — the old design staged every season's writes into an in-memory map and merged them
     * into a freshly re-read `MediaItem` under a global mutex, because two sibling seasons of the same
     * series writing their own copies of the *same shared JSON blob* around the same time could
     * silently clobber each other. With one real row per (item, episode, kind) in `media_segment`, that
     * whole class of conflict is gone — each episode's row has its own primary key, so two sibling
     * seasons' writes never touch the same row and SQLite's own per-row write handles the rest. Each
     * episode's consensus is written directly as soon as it's computed, no staging/mutex needed.
     *
     * A non-writable (locked, or already filled with `force=false`) episode is never a write target but
     * remains a valid comparison partner for every other episode's own pairs (more data, better
     * consensus).
     *
     * Progress reporting is deliberately two different scales: [reportDetail] (live, ephemeral, feeds
     * the Activity page's per-worker "Workers" card only) fires once per pair — honest about scale,
     * however many pairs a large season needs. [Logger.info] (persisted to the capped 10k-entry
     * activity-log ring buffer) stays at O(episodes) cardinality — once per episode fingerprinted,
     * once per episode's final consensus — so a big season's O(n²) pair count can't flood out
     * unrelated history from that shared, capped log.
     */
    suspend fun detectIntroFingerprintsForSeason(
        item: MediaItem,
        segmentStore: MediaSegmentStore,
        fingerprintService: FingerprintService,
        seasonEpisodes: List<Episode>,
        force: Boolean = false,
        reportDetail: suspend (String?) -> Unit = {},
        // Phase 164 (FR-164-5) — checked between episodes in Phase A below, the one part of this
        // function with real I/O (an fpcalc decode); Phase B/C are fast in-memory work not worth
        // interrupting mid-way. Defaults to a no-op so every pre-164 call site is unaffected.
        isCancelled: () -> Boolean = { false },
        // Phase 170 (§3) — one roll-up History entry per season run, only when something was written —
        // see detectChapterAndHeuristic's doc comment for why this is per-run, not per-episode.
        mediaHistory: MediaHistory? = null,
    ) {
        fun key(ep: Episode) = "${ep.filename}#${ep.episodeNumber}"
        fun epNum(ep: Episode) = ep.episodeNumber ?: 0
        fun eligible(ep: Episode): Boolean {
            val existing = segmentStore.getSegment(item.id, ep.filename, epNum(ep), SegmentKind.INTRO)
            // Phase 170 (§2) — never let a force re-detect's fingerprint consensus clobber a higher-
            // precedence existing row (e.g. an exact chapter-title match) — see SegmentSource.precedence.
            val overwriteOk = SegmentSource.precedence(SegmentSource.FINGERPRINT) >= SegmentSource.precedence(existing?.source)
            return existing?.locked != true && overwriteOk && (force || existing == null)
        }

        if (seasonEpisodes.size < 2) return
        val sorted = seasonEpisodes.sortedBy { it.episodeNumber ?: Int.MAX_VALUE }

        // Every unordered pair within this season, kept only when at least one side still needs a
        // result — a pair between two non-writable episodes would produce a candidate nobody writes,
        // so it's not worth correlating.
        val pairs = buildList {
            for (i in sorted.indices) for (j in i + 1 until sorted.size) {
                if (eligible(sorted[i]) || eligible(sorted[j])) add(sorted[i] to sorted[j])
            }
        }
        if (pairs.isEmpty()) return

        val touchedEpisodes = pairs.flatMap { listOf(it.first, it.second) }.distinctBy { key(it) }
        val seasonLabel = sorted.first().seasonNumber?.toString()?.padStart(2, '0') ?: "??"
        Logger.info(
            "detect_segments: fingerprinting '${item.title}' S$seasonLabel — ${touchedEpisodes.size} episode(s), ${pairs.size} pair(s) to correlate",
            "pipeline", item.id,
        )

        // Phase A — warm the fingerprint cache: one fpcalc call per distinct episode, ever (same
        // total as the old single-reference design in the worst case). One warning per FAILED
        // episode, not per pair it would have participated in.
        val fpCache = mutableMapOf<String, List<Int>?>()
        val failedEpisodes = mutableSetOf<String>()
        for ((i, ep) in touchedEpisodes.withIndex()) {
            if (isCancelled()) return
            reportDetail("fingerprinting ${episodeLabel(ep)} (${i + 1}/${touchedEpisodes.size})")
            val fp = fingerprintService.getOrCompute(item.id, ep)
            fpCache[key(ep)] = fp
            if (fp == null) {
                failedEpisodes += key(ep)
                Logger.warn("detect_segments: '${item.title}' ${episodeLabel(ep)} — fpcalc failed, excluded from correlation", "pipeline", item.id)
            }
        }

        // Phase B — correlate every kept pair; each eligible side of a successful match contributes
        // one candidate (with its paired episode's identity, for evidence) towards that episode's
        // eventual consensus.
        val candidates = mutableMapOf<String, MutableList<PairedCandidate>>()
        fun addCandidate(ep: Episode, pairedLabel: String, c: SegmentDetection.IntroCandidate) {
            candidates.getOrPut(key(ep)) { mutableListOf() }.add(PairedCandidate(c, pairedLabel))
        }
        for ((i, pair) in pairs.withIndex()) {
            val (epA, epB) = pair
            reportDetail("correlating ${i + 1}/${pairs.size} (${episodeLabel(epA)} × ${episodeLabel(epB)})")
            if (key(epA) in failedEpisodes || key(epB) in failedEpisodes) continue
            val fpA = fpCache[key(epA)] ?: continue
            val fpB = fpCache[key(epB)] ?: continue
            val match = SegmentDetection.findIntroMatch(fpA, fpB) ?: continue
            if (eligible(epA)) addCandidate(epA, episodeLabel(epB), SegmentDetection.IntroCandidate(match.aStartMs, match.aEndMs, match.confidence))
            if (eligible(epB)) addCandidate(epB, episodeLabel(epA), SegmentDetection.IntroCandidate(match.bStartMs, match.bEndMs, match.confidence))
        }

        // Phase C — reconcile each episode's candidates into one consensus answer and write it
        // directly (see this function's doc — no staging map, no mutex, needed anymore).
        var wroteCount = 0
        for (ep in sorted) {
            if (!eligible(ep)) continue
            val epCandidates = candidates[key(ep)] ?: continue
            val consensus = SegmentDetection.aggregateIntroCandidates(epCandidates.map { it.candidate }) ?: continue
            Logger.info(
                "detect_segments: '${item.title}' ${episodeLabel(ep)} — consensus from ${epCandidates.size} pairwise " +
                    "match(es), ${(consensus.endMs - consensus.startMs) / 1000}s intro (confidence ${(consensus.confidence * 100).toInt()}%)",
                "pipeline", item.id,
            )
            segmentStore.clearEvidenceForKind(item.id, ep.filename, epNum(ep), SegmentKind.INTRO)
            for (pc in epCandidates) {
                val accepted = kotlin.math.abs(pc.candidate.startMs - consensus.startMs) <= SegmentDetection.CLUSTER_TOLERANCE_MS
                segmentStore.recordEvidence(
                    item.id, ep.filename, epNum(ep), SegmentKind.INTRO, EvidenceType.FINGERPRINT_MATCH,
                    pc.candidate.startMs, pc.candidate.endMs, pairedEpisodeDetail(pc.pairedEpisodeLabel), accepted,
                )
            }
            segmentStore.upsertSegment(item.id, ep.filename, epNum(ep), SegmentKind.INTRO, consensus.startMs, consensus.endMs, SegmentSource.FINGERPRINT, consensus.confidence)
            wroteCount++
        }

        if (wroteCount == 0) {
            Logger.info("detect_segments: '${item.title}' S$seasonLabel — fingerprinting found no new intro matches", "pipeline", item.id)
        } else {
            mediaHistory?.record(item.id, "detect_segments", "fingerprint detection wrote $wroteCount intro marker(s) in S$seasonLabel")
            Logger.info("detect_segments: '${item.title}' S$seasonLabel — fingerprinting done, $wroteCount episode(s) updated", "pipeline", item.id)
        }
    }

    /**
     * Phase 159 (FR-159-3) — cross-episode OUTRO/credits fingerprinting for a series' credits marker,
     * mirroring [detectIntroFingerprintsForSeason]'s structure and season-wide-consensus reasoning
     * exactly (same pairwise-correlation-then-[SegmentDetection.aggregateIntroCandidates] shape — that
     * function is generic over "a cluster of (startMs, endMs, confidence) candidates," it doesn't care
     * whether they represent an intro or an outro). The one real difference from the intro pass: an
     * outro fingerprint is taken from the TAIL of the file
     * ([FingerprintService.getOrComputeOutro]'s `windowStartMs`), so a raw match position from
     * [SegmentDetection.findIntroMatch] must be shifted by that episode's own window offset before it's
     * a real absolute file timestamp — the intro pass never needs this since its window starts at file
     * position 0.
     */
    suspend fun detectOutroFingerprintsForSeason(
        item: MediaItem,
        segmentStore: MediaSegmentStore,
        fingerprintService: FingerprintService,
        seasonEpisodes: List<Episode>,
        force: Boolean = false,
        reportDetail: suspend (String?) -> Unit = {},
        // Phase 164 (FR-164-5) — same shape as detectIntroFingerprintsForSeason's own parameter; see
        // that function's doc.
        isCancelled: () -> Boolean = { false },
        // Phase 170 (§3) — same one-roll-up-per-run shape as detectIntroFingerprintsForSeason's own.
        mediaHistory: MediaHistory? = null,
    ) {
        fun key(ep: Episode) = "${ep.filename}#${ep.episodeNumber}"
        fun epNum(ep: Episode) = ep.episodeNumber ?: 0
        fun eligible(ep: Episode): Boolean {
            val existing = segmentStore.getSegment(item.id, ep.filename, epNum(ep), SegmentKind.CREDITS)
            // Phase 170 (§2) — same precedence guard as detectIntroFingerprintsForSeason's eligible().
            val overwriteOk = SegmentSource.precedence(SegmentSource.FINGERPRINT) >= SegmentSource.precedence(existing?.source)
            return existing?.locked != true && overwriteOk && (force || existing == null)
        }

        if (seasonEpisodes.size < 2) return
        val sorted = seasonEpisodes.sortedBy { it.episodeNumber ?: Int.MAX_VALUE }

        val pairs = buildList {
            for (i in sorted.indices) for (j in i + 1 until sorted.size) {
                if (eligible(sorted[i]) || eligible(sorted[j])) add(sorted[i] to sorted[j])
            }
        }
        if (pairs.isEmpty()) return

        val touchedEpisodes = pairs.flatMap { listOf(it.first, it.second) }.distinctBy { key(it) }
        val seasonLabel = sorted.first().seasonNumber?.toString()?.padStart(2, '0') ?: "??"
        Logger.info(
            "detect_segments: outro-fingerprinting '${item.title}' S$seasonLabel — ${touchedEpisodes.size} episode(s), ${pairs.size} pair(s) to correlate",
            "pipeline", item.id,
        )

        // Phase A — warm the tail-fingerprint cache. Needs each episode's duration (to know the tail
        // window's absolute file offset), fetched once per episode alongside the fingerprint itself.
        val fpCache = mutableMapOf<String, FingerprintService.TailFingerprint?>()
        val failedEpisodes = mutableSetOf<String>()
        for ((i, ep) in touchedEpisodes.withIndex()) {
            if (isCancelled()) return
            reportDetail("outro-fingerprinting ${episodeLabel(ep)} (${i + 1}/${touchedEpisodes.size})")
            val duration = FfprobeRunner.duration(ep.path)
            val fp = duration?.let { fingerprintService.getOrComputeOutro(item.id, ep, it) }
            fpCache[key(ep)] = fp
            if (fp == null) {
                failedEpisodes += key(ep)
                Logger.warn("detect_segments: '${item.title}' ${episodeLabel(ep)} — outro fpcalc failed, excluded from correlation", "pipeline", item.id)
            }
        }

        // Phase B — correlate every kept pair, shifting each side's match position by its own tail
        // window's absolute file offset before it becomes a candidate.
        val candidates = mutableMapOf<String, MutableList<PairedCandidate>>()
        fun addCandidate(ep: Episode, pairedLabel: String, c: SegmentDetection.IntroCandidate) {
            candidates.getOrPut(key(ep)) { mutableListOf() }.add(PairedCandidate(c, pairedLabel))
        }
        for ((i, pair) in pairs.withIndex()) {
            val (epA, epB) = pair
            reportDetail("correlating outro ${i + 1}/${pairs.size} (${episodeLabel(epA)} × ${episodeLabel(epB)})")
            if (key(epA) in failedEpisodes || key(epB) in failedEpisodes) continue
            val fpA = fpCache[key(epA)] ?: continue
            val fpB = fpCache[key(epB)] ?: continue
            val match = SegmentDetection.findIntroMatch(fpA.frames, fpB.frames) ?: continue
            if (eligible(epA)) addCandidate(epA, episodeLabel(epB), SegmentDetection.IntroCandidate(match.aStartMs + fpA.windowStartMs, match.aEndMs + fpA.windowStartMs, match.confidence))
            if (eligible(epB)) addCandidate(epB, episodeLabel(epA), SegmentDetection.IntroCandidate(match.bStartMs + fpB.windowStartMs, match.bEndMs + fpB.windowStartMs, match.confidence))
        }

        // Phase C — reconcile each episode's candidates and write directly. Only a start time is a real
        // field for credits (no end — the player's existing end-of-file fallback covers the tail), so
        // the consensus's endMs is discarded, matching the old behavior exactly.
        var wroteCount = 0
        for (ep in sorted) {
            if (!eligible(ep)) continue
            val epCandidates = candidates[key(ep)] ?: continue
            val consensus = SegmentDetection.aggregateIntroCandidates(epCandidates.map { it.candidate }) ?: continue
            Logger.info(
                "detect_segments: '${item.title}' ${episodeLabel(ep)} — outro consensus from ${epCandidates.size} pairwise " +
                    "match(es) (confidence ${(consensus.confidence * 100).toInt()}%)",
                "pipeline", item.id,
            )
            segmentStore.clearEvidenceForKind(item.id, ep.filename, epNum(ep), SegmentKind.CREDITS)
            for (pc in epCandidates) {
                val accepted = kotlin.math.abs(pc.candidate.startMs - consensus.startMs) <= SegmentDetection.CLUSTER_TOLERANCE_MS
                segmentStore.recordEvidence(
                    item.id, ep.filename, epNum(ep), SegmentKind.CREDITS, EvidenceType.FINGERPRINT_MATCH,
                    pc.candidate.startMs, pc.candidate.endMs, pairedEpisodeDetail(pc.pairedEpisodeLabel), accepted,
                )
            }
            segmentStore.upsertSegment(item.id, ep.filename, epNum(ep), SegmentKind.CREDITS, consensus.startMs, null, SegmentSource.FINGERPRINT, consensus.confidence)
            wroteCount++
        }

        if (wroteCount == 0) {
            Logger.info("detect_segments: '${item.title}' S$seasonLabel — outro fingerprinting found no new credits matches", "pipeline", item.id)
        } else {
            mediaHistory?.record(item.id, "detect_segments", "fingerprint detection wrote $wroteCount credits marker(s) in S$seasonLabel")
            Logger.info("detect_segments: '${item.title}' S$seasonLabel — outro fingerprinting done, $wroteCount episode(s) updated", "pipeline", item.id)
        }
    }
}
