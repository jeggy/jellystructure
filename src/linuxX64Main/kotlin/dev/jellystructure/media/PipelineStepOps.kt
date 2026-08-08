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
import dev.jellystructure.model.SegmentMarkers
import dev.jellystructure.nfo.NfoWriter
import dev.jellystructure.nowEpochSec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private suspend fun detectForPath(path: String, current: SegmentMarkers, extraChapterKeywords: List<String>): SegmentMarkers? {
        if (current.manuallyConfirmed) return null
        if (current.introStartMs != null || current.creditsStartMs != null) return null

        // Phase 150 dev-review addendum §2 / settings card: admin-added words extend (never replace)
        // the built-in list, classified as CREDITS — the settings card exposes one flat keyword list
        // with no per-word intro/credits kind selector, and extra words are overwhelmingly going to be
        // credits synonyms in other languages (recap/previously/next-time are already built in).
        val keywords = if (extraChapterKeywords.isEmpty()) DEFAULT_CHAPTER_KEYWORDS
        else DEFAULT_CHAPTER_KEYWORDS + extraChapterKeywords.filter { it.isNotBlank() }.map { ChapterKeyword(it, ChapterSegmentKind.CREDITS) }

        SegmentDetection.fromChapters(FfprobeRunner.chapters(path), keywords)?.let { hit ->
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

    /** Cheap tier only (chapter-title match + the ffmpeg credits heuristic) — extracted from
     *  [detectSegments] so the pipeline can run it as its own pass over every item at the pool's
     *  normal per-item granularity, ahead of the much heavier per-season fingerprint pass (see
     *  [detectIntroFingerprintsForSeason]). Returns the (possibly updated) item so a caller chaining
     *  straight into fingerprinting has the fresh episode-segments state without a re-fetch. */
    suspend fun detectChapterAndHeuristic(
        item: MediaItem,
        store: MediaStore,
        extraChapterKeywords: List<String> = emptyList(),
    ): MediaItem = when (item.kind) {
        MediaKind.MOVIE -> {
            val updated = detectForPath(item.path, item.segments, extraChapterKeywords)
            if (updated == null) item else item.copy(segments = updated).also { store.updateOne(it) }
        }
        MediaKind.TV_SHOW -> {
            var changed = false
            val updatedEpisodes = item.episodes.map { ep ->
                if (ep.partCount > 1) return@map ep
                val updated = detectForPath(ep.path, ep.segments, extraChapterKeywords) ?: return@map ep
                changed = true
                ep.copy(segments = updated)
            }
            if (!changed) item else item.copy(episodes = updatedEpisodes).also { store.updateOne(it) }
        }
    }

    /** Single-item convenience wrapper (chapter/heuristic tier, then — for a series — the fingerprint
     *  tier across every season) for callers that process one item at a time outside the bulk pipeline's
     *  worker pool: the segment rescan routes ([dev.jellystructure.server.routes.MediaRoutes]) and
     *  [RealtimeIngestService]. The bulk `detect_segments` pipeline step does NOT call this — it runs
     *  the two tiers as separate `runPipelineStepPool` passes at different work-item granularities (see
     *  `Main.kt`'s `detect_segments` case) so a series with many seasons spreads its fingerprint work
     *  across the worker pool instead of running every season sequentially in one worker slot. */
    suspend fun detectSegments(
        item: MediaItem,
        store: MediaStore,
        extraChapterKeywords: List<String> = emptyList(),
        fingerprintService: FingerprintService? = null,
        detectFingerprint: Boolean = false,
        reportDetail: suspend (String?) -> Unit = {},
    ) {
        val afterChapterHeuristic = detectChapterAndHeuristic(item, store, extraChapterKeywords)
        // FR-SEG1-4 — cross-episode audio fingerprinting (Skip Intro), gated on the detect_fingerprint
        // pipeline-step toggle: heavier (an fpcalc decode per episode) than the tier above.
        if (afterChapterHeuristic.kind == MediaKind.TV_SHOW && detectFingerprint && fingerprintService != null) {
            for (seasonEpisodes in eligibleSeasons(afterChapterHeuristic)) {
                detectIntroFingerprintsForSeason(afterChapterHeuristic, store, fingerprintService, seasonEpisodes, reportDetail)
                // Phase 159 (FR-159-3) — outro/credits counterpart, same per-season worker-pool shape.
                detectOutroFingerprintsForSeason(afterChapterHeuristic, store, fingerprintService, seasonEpisodes, reportDetail)
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

    // Guards the read-current-state → merge-this-season's-updates → write-back sequence for every
    // fingerprint-tier write, series-wide (not keyed per item): when a series' seasons are dispatched to
    // different workers (`Main.kt`'s bulk pipeline step), two seasons of the SAME series can finish and
    // want to write around the same time, each starting from its own snapshot of `MediaItem.episodes` —
    // without serializing the read-merge-write, whichever writes second would silently overwrite the
    // first's just-persisted season with its own (older) copy of every OTHER season's data. The critical
    // section is a single cheap DB row read + in-memory merge + write (no fpcalc/network calls inside
    // it), so one global lock across every series costs negligible contention for real correctness.
    private val segmentWriteMutex = Mutex()

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
     * result, only how it's scheduled. The final write re-reads the item fresh and merges in only this
     * season's episodes under [segmentWriteMutex], so concurrent sibling-season writes for the same
     * series can never clobber each other.
     *
     * An already-filled/`manuallyConfirmed` episode is never a write target but remains a valid
     * comparison partner for every other episode's own pairs (more data, better consensus).
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
        store: MediaStore,
        fingerprintService: FingerprintService,
        seasonEpisodes: List<Episode>,
        reportDetail: suspend (String?) -> Unit = {},
    ) {
        fun key(ep: Episode) = "${ep.filename}#${ep.episodeNumber}"
        val updates = mutableMapOf<String, SegmentMarkers>()
        fun segmentsFor(ep: Episode) = updates[key(ep)] ?: ep.segments
        fun eligible(ep: Episode) = segmentsFor(ep).let { !it.manuallyConfirmed && it.introStartMs == null }
        fun epLabel(ep: Episode): String {
            val s = ep.seasonNumber?.toString()?.padStart(2, '0') ?: "??"
            val e = ep.episodeNumber?.toString()?.padStart(2, '0') ?: "??"
            return "S${s}E$e"
        }

        if (seasonEpisodes.size < 2) return
        val sorted = seasonEpisodes.sortedBy { it.episodeNumber ?: Int.MAX_VALUE }

        // Every unordered pair within this season, kept only when at least one side still needs a
        // result — a pair between two already-filled/manually-confirmed episodes would produce a
        // candidate nobody writes, so it's not worth correlating.
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
            reportDetail("fingerprinting ${epLabel(ep)} (${i + 1}/${touchedEpisodes.size})")
            val fp = fingerprintService.getOrCompute(item.id, ep)
            fpCache[key(ep)] = fp
            if (fp == null) {
                failedEpisodes += key(ep)
                Logger.warn("detect_segments: '${item.title}' ${epLabel(ep)} — fpcalc failed, excluded from correlation", "pipeline", item.id)
            }
        }

        // Phase B — correlate every kept pair; each eligible side of a successful match contributes
        // one IntroCandidate towards that episode's eventual consensus.
        val candidates = mutableMapOf<String, MutableList<SegmentDetection.IntroCandidate>>()
        fun addCandidate(ep: Episode, c: SegmentDetection.IntroCandidate) {
            candidates.getOrPut(key(ep)) { mutableListOf() }.add(c)
        }
        for ((i, pair) in pairs.withIndex()) {
            val (epA, epB) = pair
            reportDetail("correlating ${i + 1}/${pairs.size} (${epLabel(epA)} × ${epLabel(epB)})")
            if (key(epA) in failedEpisodes || key(epB) in failedEpisodes) continue
            val fpA = fpCache[key(epA)] ?: continue
            val fpB = fpCache[key(epB)] ?: continue
            val match = SegmentDetection.findIntroMatch(fpA, fpB) ?: continue
            if (eligible(epA)) addCandidate(epA, SegmentDetection.IntroCandidate(match.aStartMs, match.aEndMs, match.confidence))
            if (eligible(epB)) addCandidate(epB, SegmentDetection.IntroCandidate(match.bStartMs, match.bEndMs, match.confidence))
        }

        // Phase C — reconcile each episode's candidates into one consensus answer and stage the write.
        for (ep in sorted) {
            if (!eligible(ep)) continue
            val epCandidates = candidates[key(ep)] ?: continue
            val consensus = SegmentDetection.aggregateIntroCandidates(epCandidates) ?: continue
            Logger.info(
                "detect_segments: '${item.title}' ${epLabel(ep)} — consensus from ${epCandidates.size} pairwise " +
                    "match(es), ${(consensus.endMs - consensus.startMs) / 1000}s intro (confidence ${(consensus.confidence * 100).toInt()}%)",
                "pipeline", item.id,
            )
            updates[key(ep)] = segmentsFor(ep).copy(
                introStartMs = consensus.startMs, introEndMs = consensus.endMs,
                source = "fingerprint", confidence = consensus.confidence,
            )
        }

        if (updates.isEmpty()) {
            Logger.info("detect_segments: '${item.title}' S$seasonLabel — fingerprinting found no new intro matches", "pipeline", item.id)
            return
        }
        segmentWriteMutex.withLock {
            val fresh = store.get(item.id) ?: return@withLock
            val mergedEpisodes = fresh.episodes.map { ep -> updates[key(ep)]?.let { ep.copy(segments = it) } ?: ep }
            store.updateOne(fresh.copy(episodes = mergedEpisodes))
        }
        Logger.info("detect_segments: '${item.title}' S$seasonLabel — fingerprinting done, ${updates.size} episode(s) updated", "pipeline", item.id)
    }

    /**
     * Phase 159 (FR-159-3) — cross-episode OUTRO/credits fingerprinting for a series' `creditsStartMs`,
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
        store: MediaStore,
        fingerprintService: FingerprintService,
        seasonEpisodes: List<Episode>,
        reportDetail: suspend (String?) -> Unit = {},
    ) {
        fun key(ep: Episode) = "${ep.filename}#${ep.episodeNumber}"
        val updates = mutableMapOf<String, SegmentMarkers>()
        fun segmentsFor(ep: Episode) = updates[key(ep)] ?: ep.segments
        fun eligible(ep: Episode) = segmentsFor(ep).let { !it.manuallyConfirmed && it.creditsStartMs == null }
        fun epLabel(ep: Episode): String {
            val s = ep.seasonNumber?.toString()?.padStart(2, '0') ?: "??"
            val e = ep.episodeNumber?.toString()?.padStart(2, '0') ?: "??"
            return "S${s}E$e"
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
            reportDetail("outro-fingerprinting ${epLabel(ep)} (${i + 1}/${touchedEpisodes.size})")
            val duration = FfprobeRunner.duration(ep.path)
            val fp = duration?.let { fingerprintService.getOrComputeOutro(item.id, ep, it) }
            fpCache[key(ep)] = fp
            if (fp == null) {
                failedEpisodes += key(ep)
                Logger.warn("detect_segments: '${item.title}' ${epLabel(ep)} — outro fpcalc failed, excluded from correlation", "pipeline", item.id)
            }
        }

        // Phase B — correlate every kept pair, shifting each side's match position by its own tail
        // window's absolute file offset before it becomes a candidate.
        val candidates = mutableMapOf<String, MutableList<SegmentDetection.IntroCandidate>>()
        fun addCandidate(ep: Episode, c: SegmentDetection.IntroCandidate) {
            candidates.getOrPut(key(ep)) { mutableListOf() }.add(c)
        }
        for ((i, pair) in pairs.withIndex()) {
            val (epA, epB) = pair
            reportDetail("correlating outro ${i + 1}/${pairs.size} (${epLabel(epA)} × ${epLabel(epB)})")
            if (key(epA) in failedEpisodes || key(epB) in failedEpisodes) continue
            val fpA = fpCache[key(epA)] ?: continue
            val fpB = fpCache[key(epB)] ?: continue
            val match = SegmentDetection.findIntroMatch(fpA.frames, fpB.frames) ?: continue
            if (eligible(epA)) addCandidate(epA, SegmentDetection.IntroCandidate(match.aStartMs + fpA.windowStartMs, match.aEndMs + fpA.windowStartMs, match.confidence))
            if (eligible(epB)) addCandidate(epB, SegmentDetection.IntroCandidate(match.bStartMs + fpB.windowStartMs, match.bEndMs + fpB.windowStartMs, match.confidence))
        }

        // Phase C — reconcile each episode's candidates; only creditsStartMs is a real field on
        // SegmentMarkers (no creditsEndMs — the player's existing end-of-file fallback covers the tail),
        // so the consensus's startMs is all that's written.
        for (ep in sorted) {
            if (!eligible(ep)) continue
            val epCandidates = candidates[key(ep)] ?: continue
            val consensus = SegmentDetection.aggregateIntroCandidates(epCandidates) ?: continue
            Logger.info(
                "detect_segments: '${item.title}' ${epLabel(ep)} — outro consensus from ${epCandidates.size} pairwise " +
                    "match(es) (confidence ${(consensus.confidence * 100).toInt()}%)",
                "pipeline", item.id,
            )
            updates[key(ep)] = segmentsFor(ep).copy(
                creditsStartMs = consensus.startMs,
                source = "fingerprint", confidence = consensus.confidence,
            )
        }

        if (updates.isEmpty()) {
            Logger.info("detect_segments: '${item.title}' S$seasonLabel — outro fingerprinting found no new credits matches", "pipeline", item.id)
            return
        }
        segmentWriteMutex.withLock {
            val fresh = store.get(item.id) ?: return@withLock
            val mergedEpisodes = fresh.episodes.map { ep -> updates[key(ep)]?.let { ep.copy(segments = it) } ?: ep }
            store.updateOne(fresh.copy(episodes = mergedEpisodes))
        }
        Logger.info("detect_segments: '${item.title}' S$seasonLabel — outro fingerprinting done, ${updates.size} episode(s) updated", "pipeline", item.id)
    }
}
