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

    suspend fun detectSegments(
        item: MediaItem,
        store: MediaStore,
        extraChapterKeywords: List<String> = emptyList(),
        fingerprintService: FingerprintService? = null,
        detectFingerprint: Boolean = false,
        reportDetail: suspend (String?) -> Unit = {},
    ) {
        when (item.kind) {
            MediaKind.MOVIE -> {
                val updated = detectForPath(item.path, item.segments, extraChapterKeywords) ?: return
                store.updateOne(item.copy(segments = updated))
            }
            MediaKind.TV_SHOW -> {
                var changed = false
                val updatedEpisodes = item.episodes.map { ep ->
                    if (ep.partCount > 1) return@map ep
                    val updated = detectForPath(ep.path, ep.segments, extraChapterKeywords) ?: return@map ep
                    changed = true
                    ep.copy(segments = updated)
                }
                val afterChapterHeuristic = if (changed) item.copy(episodes = updatedEpisodes) else item
                if (changed) store.updateOne(afterChapterHeuristic)

                // FR-SEG1-4 — cross-episode audio fingerprinting (Skip Intro), gated on the
                // detect_fingerprint pipeline-step toggle: heavier (an fpcalc decode per episode) than
                // the chapter/heuristic tier above, so it's opt-in on top of detect_segments itself.
                if (detectFingerprint && fingerprintService != null) {
                    detectIntroFingerprints(afterChapterHeuristic, store, fingerprintService, reportDetail)
                }
            }
        }
    }

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
     * A season needs ≥2 `partCount == 1` episodes to have anything to compare (FR-SEG1-4's own
     * "series ≥2 episodes" scope) — a lone episode, or a season where everything already has an intro
     * from an earlier tier, is a silent no-op here, same as every other segment-detection tier. An
     * already-filled/`manuallyConfirmed` episode is never a write target but remains a valid
     * comparison partner for every other episode's own pairs (more data, better consensus).
     *
     * Progress reporting is deliberately two different scales: [reportDetail] (live, ephemeral, feeds
     * the Activity page's per-worker "Workers" card only) fires once per pair — honest about scale,
     * however many pairs a large season needs. [Logger.info] (persisted to the capped 10k-entry
     * activity-log ring buffer) stays at O(episodes) cardinality — once per episode fingerprinted,
     * once per episode's final consensus — so a big season's O(n²) pair count can't flood out
     * unrelated history from that shared, capped log.
     */
    private suspend fun detectIntroFingerprints(
        item: MediaItem,
        store: MediaStore,
        fingerprintService: FingerprintService,
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

        val bySeason = item.episodes.filter { it.partCount == 1 }.groupBy { it.seasonNumber }
        val seasonsToProcess = bySeason.values.filter { it.size >= 2 }
        if (seasonsToProcess.isEmpty()) return

        // Every unordered pair within a season, kept only when at least one side still needs a
        // result — a pair between two already-filled/manually-confirmed episodes would produce a
        // candidate nobody writes, so it's not worth correlating.
        val allPairs = seasonsToProcess.flatMap { episodes ->
            val sorted = episodes.sortedBy { it.episodeNumber ?: Int.MAX_VALUE }
            buildList {
                for (i in sorted.indices) for (j in i + 1 until sorted.size) {
                    if (eligible(sorted[i]) || eligible(sorted[j])) add(sorted[i] to sorted[j])
                }
            }
        }
        if (allPairs.isEmpty()) return

        val touchedEpisodes = allPairs.flatMap { listOf(it.first, it.second) }.distinctBy { key(it) }
        Logger.info(
            "detect_segments: fingerprinting '${item.title}' — ${seasonsToProcess.size} season(s), " +
                "${touchedEpisodes.size} episode(s), ${allPairs.size} pair(s) to correlate",
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
        for ((i, pair) in allPairs.withIndex()) {
            val (epA, epB) = pair
            reportDetail("correlating ${i + 1}/${allPairs.size} (${epLabel(epA)} × ${epLabel(epB)})")
            if (key(epA) in failedEpisodes || key(epB) in failedEpisodes) continue
            val fpA = fpCache[key(epA)] ?: continue
            val fpB = fpCache[key(epB)] ?: continue
            val match = SegmentDetection.findIntroMatch(fpA, fpB) ?: continue
            if (eligible(epA)) addCandidate(epA, SegmentDetection.IntroCandidate(match.aStartMs, match.aEndMs, match.confidence))
            if (eligible(epB)) addCandidate(epB, SegmentDetection.IntroCandidate(match.bStartMs, match.bEndMs, match.confidence))
        }

        // Phase C — reconcile each episode's candidates into one consensus answer and stage the write.
        for (episodes in seasonsToProcess) {
            for (ep in episodes) {
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
        }

        if (updates.isEmpty()) {
            Logger.info("detect_segments: '${item.title}' — fingerprinting found no new intro matches", "pipeline", item.id)
            return
        }
        val updatedEpisodes = item.episodes.map { ep -> updates[key(ep)]?.let { ep.copy(segments = it) } ?: ep }
        store.updateOne(item.copy(episodes = updatedEpisodes))
        Logger.info("detect_segments: '${item.title}' — fingerprinting done, ${updates.size} episode(s) updated", "pipeline", item.id)
    }
}
