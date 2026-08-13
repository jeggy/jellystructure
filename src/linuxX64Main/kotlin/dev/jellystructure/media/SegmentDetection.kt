package dev.jellystructure.media

import dev.jellystructure.model.SegmentMarkers
import dev.jellystructure.model.Stinger

/**
 * Phase 150 (FR-SEG1-2/3) — the detection layer behind `detect_segments`: finds where a file's intro
 * and credits segments actually start, cheapest/most-certain signal first. `SegmentDetectionStep`
 * (the pipeline-step wiring) runs these in order and stops once a field is filled, unless the admin
 * forces a re-scan (`scope = "all"`).
 */

/** Which part of the episode/movie a matched chapter title represents. A chapter titled "Recap" near
 *  the start is intro-side; one titled "Credits" is credits-side — a single flat "any keyword matches"
 *  can't tell those apart (dev-review addendum §3), so every configured keyword carries its own kind. */
enum class ChapterSegmentKind { INTRO, CREDITS }

data class ChapterKeyword(val word: String, val kind: ChapterSegmentKind)

/** English + the two other UI languages this app ships (da/fo) — an open design decision (spec §9) to
 *  extend per-library; exposed as a plain, admin-editable list (`design/app/settings.html`'s chapter-
 *  keyword chips) rather than hardcoded, so this default is a starting point, not the final word. */
val DEFAULT_CHAPTER_KEYWORDS: List<ChapterKeyword> = listOf(
    ChapterKeyword("credits", ChapterSegmentKind.CREDITS),
    ChapterKeyword("end credits", ChapterSegmentKind.CREDITS),
    ChapterKeyword("rulletekster", ChapterSegmentKind.CREDITS),   // Danish
    ChapterKeyword("endamál", ChapterSegmentKind.CREDITS),        // Faroese
    ChapterKeyword("recap", ChapterSegmentKind.INTRO),
    ChapterKeyword("previously", ChapterSegmentKind.INTRO),
)

/** Phase 163 (dev-review addendum §2) — one chapter that matched a keyword, kept whether or not it won
 *  (the two winners aside, every other keyword-matching chapter used to be discarded the moment
 *  [SegmentDetection.fromChapters] picked its `firstOrNull`). */
data class ChapterEvidencePoint(val kind: ChapterSegmentKind, val startMs: Long, val endMs: Long?, val title: String?, val accepted: Boolean)

data class ChapterDetectionResult(val markers: SegmentMarkers, val evidence: List<ChapterEvidencePoint>)

object SegmentDetection {
    /**
     * FR-SEG1-2 — pattern-match chapter titles against [keywords]. Near-zero cost: the chapters are
     * already fetched by the caller (`SegmentDetectionStep`), this is pure string matching over data
     * already in memory.
     *
     * A credits match is rejected when it falls in the first minute of the file — a "Credits" chapter
     * essentially never opens the file, so a hit there is noise, not signal (dev-review addendum §3's
     * own example). An intro/recap match needs no equivalent guard: chapters are already in file order,
     * so the first matching one IS the earliest occurrence by construction.
     *
     * Returns null when nothing matched (the caller falls through to the ffmpeg heuristic).
     */
    fun fromChapters(chapters: List<ChapterMarker>, keywords: List<ChapterKeyword> = DEFAULT_CHAPTER_KEYWORDS): ChapterDetectionResult? {
        if (chapters.isEmpty()) return null

        fun matches(title: String?, kind: ChapterSegmentKind): Boolean {
            if (title.isNullOrBlank()) return false
            return keywords.any { it.kind == kind && title.contains(it.word, ignoreCase = true) }
        }

        val creditsChapter = chapters.firstOrNull { matches(it.title, ChapterSegmentKind.CREDITS) && it.startMs >= 60_000L }
        val introChapter = chapters.firstOrNull { matches(it.title, ChapterSegmentKind.INTRO) }
        if (creditsChapter == null && introChapter == null) return null

        // Phase 163 — every OTHER keyword-matching chapter (both kinds), tagged accepted only for the
        // two that actually won. Rejected credits matches inside the first minute are included too —
        // they're exactly the kind of near-miss an operator would want to see in the evidence lane.
        val evidence = chapters.mapNotNull { ch ->
            val kind = when {
                matches(ch.title, ChapterSegmentKind.CREDITS) -> ChapterSegmentKind.CREDITS
                matches(ch.title, ChapterSegmentKind.INTRO) -> ChapterSegmentKind.INTRO
                else -> return@mapNotNull null
            }
            ChapterEvidencePoint(kind, ch.startMs, ch.endMs, ch.title, accepted = ch === creditsChapter || ch === introChapter)
        }

        return ChapterDetectionResult(
            markers = SegmentMarkers(
                introStartMs = introChapter?.startMs,
                introEndMs = introChapter?.endMs,
                creditsStartMs = creditsChapter?.startMs,
                source = "chapter",
                confidence = null,  // an exact marker needs no confidence score (see SegmentMarkers doc)
            ),
            evidence = evidence,
        )
    }

    /**
     * FR-SEG1-3 — the ffmpeg credits heuristic (`FfmpegRunner.detectCreditsStart`), for when chapter
     * matching found nothing. Works on movies and standalone episodes alike (no reference episode
     * needed). Returns null when nothing coincides in the scanned window — the caller leaves
     * `creditsStartMs` unset, so the player keeps today's end-of-file fallback.
     */
    suspend fun fromCreditsHeuristic(filePath: String, durationSec: Double): FfmpegRunner.CreditsHeuristicResult? =
        FfmpegRunner.detectCreditsStart(filePath, durationSec)

    /**
     * FR-SEG1-6 — TMDB's `duringcreditsstinger`/`aftercreditsstinger` keywords, matched by NAME (TMDB
     * keyword ids are never kept past the initial fetch — see `TmdbClient.getMovieKeywords`/
     * `getTvKeywords`, which already discard them). Only called from the TMDB re-pull path
     * (`Scanner.rescanMetadata`) with the SAME raw keyword-name list that's about to be flattened into
     * `tags` (`mergeRepullTags`) — never a second TMDB request. Dev-review addendum §1: this means a
     * freshly-scanned title has no stinger until its first re-pull runs, not immediately after a bare
     * scan; accepted as the ordering this reuses, rather than adding a second keyword fetch to the
     * (much more frequent) initial full-scan path.
     */
    fun stingerFromTmdbKeywords(tmdbKeywords: List<String>): Stinger? {
        val kind = when {
            tmdbKeywords.any { it.equals("aftercreditsstinger", ignoreCase = true) } -> "after"
            tmdbKeywords.any { it.equals("duringcreditsstinger", ignoreCase = true) } -> "during"
            else -> return null
        }
        return Stinger(atMs = null, kind = kind)  // exact timing is an admin/future-detector refinement
    }

    // ── FR-SEG1-4: cross-episode audio-fingerprint intro matching ──────────────────────────────────

    // Chromaprint's raw fingerprint has one 32-bit value per ~0.124s of audio, with a fixed ~2.64s
    // startup offset before the first value — empirically derived 2026-07-13 (linear regression, 9
    // window sizes 30s–3600s against a real library file, residuals <0.1s) and independently confirmed
    // against a SECOND file of a different codec/sample-rate producing byte-for-byte identical frame
    // counts for the same -length window — these are pure libchromaprint algorithm constants, not
    // source-dependent. `elapsed_seconds_at_frame[i] ≈ FRAME_OFFSET_SEC + i * FRAME_SEC`.
    private const val FRAME_SEC = 0.1238114
    private const val FRAME_OFFSET_SEC = 2.641479

    // Sliding-offset Hamming-distance correlation + longest-matching-run-with-gap-tolerance is the same
    // general technique the Intro Skipper Jellyfin plugin (GPL-3.0, matching this project's own LICENSE)
    // uses for Chromaprint-based intro detection. This is an independent implementation — Intro Skipper's
    // C# source wasn't available to reference in this session, so nothing below is a line-for-line port —
    // validated instead by empirical testing against real library files (see FRAME_SEC/FRAME_OFFSET_SEC
    // above, and the thresholds below, both derived/tuned this session rather than copied).
    private const val HAMMING_THRESHOLD = 6          // out of 32 bits (~19% bit-error tolerance/frame)
    private const val MAX_GAP_FRAMES = 2             // tolerate up to 2 consecutive non-matching frames in a run
    private const val MIN_RUN_FRAMES = 65            // ~8s — filters out short coincidental matches

    // Phase 159 (FR-159-1) — widened from the original ±120s now that the offset-histogram pre-pass
    // below (not full run-tracking) is what pays for the wider range, and the reported failure case
    // (Offboarding-style long, variable-length cold opens before the theme proper starts) needs more than
    // ±2 minutes of tolerable misalignment between two episodes' intro positions.
    private const val MAX_OFFSET_SEARCH_SEC = 300.0

    // Phase 159 (FR-159-1) — after the histogram pass narrows candidates by total correlation strength,
    // only the strongest few offsets get the expensive exact run-extraction pass.
    private const val HISTOGRAM_TOP_K = 5

    // Phase 159 (FR-159-2) — guardrails: a matched "intro" run longer than this, or starting later than
    // this, is far more likely to be a coincidental audio match (e.g. two similar ambient-score scenes)
    // than a real shared intro/theme. 6 minutes covers real-world long theme songs with margin; 20
    // minutes gives generous headroom past the Offboarding example (theme starts as late as ~5 minutes in).
    private const val MAX_INTRO_DURATION_SEC = 360.0
    private const val MAX_INTRO_START_SEC = 1200.0

    private fun popcount(x: Int): Int {
        var v = x
        var count = 0
        while (v != 0) { v = v and (v - 1); count++ }
        return count
    }

    private data class RunCandidate(val offset: Int, val start: Int, val end: Int)

    /** Result of [findIntroMatch]: the matched run's bounds in BOTH fingerprints' own coordinate
     *  frames — comparing episode A against a reference episode B tells you both episodes' intro
     *  bounds from one comparison, since the match position in each one's own array IS that episode's
     *  own local timing. */
    data class FingerprintIntroMatch(
        val aStartMs: Long, val aEndMs: Long,
        val bStartMs: Long, val bEndMs: Long,
        val confidence: Double,
    )

    /**
     * Finds the longest run where [a] and [b]'s fingerprints agree (within [HAMMING_THRESHOLD] bits/
     * frame, tolerating up to [MAX_GAP_FRAMES] consecutive misses) across every offset in
     * ±[MAX_OFFSET_SEARCH_SEC], and returns its bounds in both fingerprints' own timelines. Null when
     * the longest run found is shorter than [MIN_RUN_FRAMES] (or either input is empty) — the caller
     * leaves `introStartMs`/`introEndMs` unset, same graceful-fallback shape as every other tier.
     */
    fun findIntroMatch(a: List<Int>, b: List<Int>): FingerprintIntroMatch? {
        if (a.isEmpty() || b.isEmpty()) return null
        val maxOffsetFrames = (MAX_OFFSET_SEARCH_SEC / FRAME_SEC).toInt()

        fun frameMatches(offset: Int, i: Int): Boolean {
            val ai = if (offset >= 0) i + offset else i
            val bi = if (offset >= 0) i else i - offset
            return popcount(a[ai] xor b[bi]) <= HAMMING_THRESHOLD
        }

        // Phase 159 (FR-159-1) — pass 1: a cheap match-density histogram over every offset in the
        // (now much wider) search range. This replaces "whichever offset happens to contain the single
        // longest contiguous run" with "which offset has the strongest OVERALL correlation" — the old
        // approach could be fooled by a coincidentally long spurious run at the wrong offset
        // outcompeting the true (but shorter, or broken up by a quiet passage) intro alignment, exactly
        // the failure mode reported for shows with a long or variable-length intro/cold-open.
        val densityByOffset = HashMap<Int, Int>(2 * maxOffsetFrames + 1)
        for (offset in -maxOffsetFrames..maxOffsetFrames) {
            val n = if (offset >= 0) minOf(a.size - offset, b.size) else minOf(a.size, b.size + offset)
            if (n <= 0) continue
            var matches = 0
            for (i in 0 until n) if (frameMatches(offset, i)) matches++
            if (matches > 0) densityByOffset[offset] = matches
        }
        if (densityByOffset.isEmpty()) return null

        // Pass 2: exact gap-tolerant longest-run extraction, but only around the strongest histogram
        // peaks — cheap, since there are only a handful of these rather than the whole search range.
        val topOffsets = densityByOffset.entries.sortedByDescending { it.value }.take(HISTOGRAM_TOP_K).map { it.key }

        var best: RunCandidate? = null
        var bestDensity = 0.0
        for (offset in topOffsets) {
            val n = if (offset >= 0) minOf(a.size - offset, b.size) else minOf(a.size, b.size + offset)
            if (n <= 0) continue

            var i = 0
            while (i < n) {
                if (!frameMatches(offset, i)) { i++; continue }
                val start = i
                var last = i
                var gap = 0
                var runMatches = 0
                var j = i
                while (j < n) {
                    if (frameMatches(offset, j)) { last = j; gap = 0; runMatches++ } else { gap++; if (gap > MAX_GAP_FRAMES) break }
                    j++
                }
                val length = last - start + 1
                if (length >= MIN_RUN_FRAMES) {
                    val density = runMatches.toDouble() / length
                    val current = best
                    // Best-density run wins (tie-broken by length) — a shorter, near-perfect match (the
                    // real intro) now beats a longer but noisier spurious run at some other offset.
                    if (current == null || density > bestDensity || (density == bestDensity && length > (current.end - current.start + 1))) {
                        best = RunCandidate(offset, start, last)
                        bestDensity = density
                    }
                }
                i = j
            }
        }

        val candidate = best ?: return null

        // candidate.start/.end are LOGICAL loop positions, not directly either array's own index — the
        // same offset-dependent mapping `frameMatches()` used above (ai = i+offset / bi = i when
        // offset≥0, ai = i / bi = i-offset when offset<0) must convert them back to each array's real
        // coordinates.
        fun aIndex(i: Int) = if (candidate.offset >= 0) i + candidate.offset else i
        fun bIndex(i: Int) = if (candidate.offset >= 0) i else i - candidate.offset
        fun toMs(frame: Int) = ((FRAME_OFFSET_SEC + frame * FRAME_SEC) * 1000).toLong()

        val aStartMs = toMs(aIndex(candidate.start)); val aEndMs = toMs(aIndex(candidate.end))
        val bStartMs = toMs(bIndex(candidate.start)); val bEndMs = toMs(bIndex(candidate.end))

        // Phase 159 (FR-159-2) — position/duration guardrails: reject implausible matches outright
        // rather than writing a confidently-wrong marker.
        val durationSec = (aEndMs - aStartMs) / 1000.0
        if (durationSec > MAX_INTRO_DURATION_SEC) return null
        if (aStartMs / 1000.0 > MAX_INTRO_START_SEC || bStartMs / 1000.0 > MAX_INTRO_START_SEC) return null

        // Match density within the run scaled into the same [0.3, 0.9] presentation range the ffmpeg
        // heuristic uses, so both auto-detected sources read comparably in the admin scrubber.
        val confidence = (0.3 + bestDensity * 0.6).coerceIn(0.3, 0.9)

        return FingerprintIntroMatch(aStartMs = aStartMs, aEndMs = aEndMs, bStartMs = bStartMs, bEndMs = bEndMs, confidence = confidence)
    }

    // ── FR-SEG1-4 amendment (2026-07-14) — season-wide consensus ───────────────────────────────────

    // How close two candidates' starts need to be (in ms) to count as the same intro window. Matches
    // are already frame-quantized (~124ms) by findIntroMatch; this only needs to absorb small
    // offset-search jitter between different pairings of the same episode, not distinguish a
    // genuinely different intro.
    // internal (not private): Phase 163's evidence capture reconstructs winning-cluster membership in
    // PipelineStepOps using this same tolerance, rather than changing aggregateIntroCandidates's return
    // shape just to carry membership out explicitly.
    internal const val CLUSTER_TOLERANCE_MS = 5_000L

    /** One episode's intro-bounds guess from a single successful pairwise comparison — that
     *  episode's own `aStartMs/aEndMs` or `bStartMs/bEndMs` (whichever side it was) from a
     *  [FingerprintIntroMatch], plus that match's confidence. */
    data class IntroCandidate(val startMs: Long, val endMs: Long, val confidence: Double)

    /**
     * Amendment (2026-07-14) — replaces "trust the single reference-episode comparison" with a
     * season-wide consensus: an episode compared against every other episode in its season
     * contributes one [IntroCandidate] per successful pairing, and this reconciles them into one
     * final answer. A single fixed reference episode (e.g. a premiere with an atypical intro cut)
     * can no longer take down detection for a whole season — confirmed via real fingerprint data
     * where the premiere failed to match ANY sibling while siblings matched each other cleanly.
     *
     * Buckets candidates by proximity ([CLUSTER_TOLERANCE_MS]) rather than taking a plain median:
     * a genuine mid-season format change would otherwise average across two real, distinct
     * clusters into a meaningless midpoint. The largest cluster (ties broken by summed confidence)
     * wins, and its member candidates' median start/end is the final answer — median rather than
     * mean so a single further outlier inside the winning cluster can't skew the boundary.
     *
     * Confidence reflects *agreement*, not just per-pair correlation strength: full agreement
     * (every candidate landed in the winning cluster) preserves the cluster's mean per-pair
     * confidence unchanged; a contested result (e.g. a near-even split) is pulled toward the 0.3
     * floor, honestly signaling low certainty rather than reporting one lucky pair's own score.
     */
    fun aggregateIntroCandidates(candidates: List<IntroCandidate>): IntroCandidate? {
        if (candidates.isEmpty()) return null
        val sorted = candidates.sortedBy { it.startMs }
        val clusters = mutableListOf<MutableList<IntroCandidate>>()
        for (c in sorted) {
            val last = clusters.lastOrNull()
            if (last != null && kotlin.math.abs(c.startMs - last.last().startMs) <= CLUSTER_TOLERANCE_MS) {
                last.add(c)
            } else {
                clusters.add(mutableListOf(c))
            }
        }
        val winner = clusters.maxWithOrNull(compareBy({ it.size }, { it.sumOf(IntroCandidate::confidence) }))
            ?: return null

        fun median(values: List<Long>): Long {
            val s = values.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }

        val meanConfidence = winner.map { it.confidence }.average()
        val agreement = winner.size.toDouble() / candidates.size
        val finalConfidence = (0.3 + (meanConfidence - 0.3) * agreement).coerceIn(0.3, 0.9)
        return IntroCandidate(
            startMs = median(winner.map { it.startMs }),
            endMs = median(winner.map { it.endMs }),
            confidence = finalConfidence,
        )
    }
}
