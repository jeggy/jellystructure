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
    fun fromChapters(chapters: List<ChapterMarker>, keywords: List<ChapterKeyword> = DEFAULT_CHAPTER_KEYWORDS): SegmentMarkers? {
        if (chapters.isEmpty()) return null

        fun matches(title: String?, kind: ChapterSegmentKind): Boolean {
            if (title.isNullOrBlank()) return false
            return keywords.any { it.kind == kind && title.contains(it.word, ignoreCase = true) }
        }

        val creditsChapter = chapters.firstOrNull { matches(it.title, ChapterSegmentKind.CREDITS) && it.startMs >= 60_000L }
        val introChapter = chapters.firstOrNull { matches(it.title, ChapterSegmentKind.INTRO) }
        if (creditsChapter == null && introChapter == null) return null

        return SegmentMarkers(
            introStartMs = introChapter?.startMs,
            introEndMs = introChapter?.endMs,
            creditsStartMs = creditsChapter?.startMs,
            source = "chapter",
            confidence = null,  // an exact marker needs no confidence score (see SegmentMarkers doc)
        )
    }

    /**
     * FR-SEG1-3 — the ffmpeg credits heuristic (`FfmpegRunner.detectCreditsStart`), for when chapter
     * matching found nothing. Works on movies and standalone episodes alike (no reference episode
     * needed). Returns null when nothing coincides in the scanned window — the caller leaves
     * `creditsStartMs` unset, so the player keeps today's end-of-file fallback.
     */
    suspend fun fromCreditsHeuristic(filePath: String, durationSec: Double): SegmentMarkers? {
        val hit = FfmpegRunner.detectCreditsStart(filePath, durationSec) ?: return null
        return SegmentMarkers(
            creditsStartMs = hit.startMs,
            source = "heuristic",
            confidence = hit.confidence,
        )
    }

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
    private const val MAX_OFFSET_SEARCH_SEC = 120.0  // search ±2 minutes of misalignment between episodes

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

        var best: RunCandidate? = null
        for (offset in -maxOffsetFrames..maxOffsetFrames) {
            val n = if (offset >= 0) minOf(a.size - offset, b.size) else minOf(a.size, b.size + offset)
            if (n <= 0) continue

            fun matches(i: Int): Boolean {
                val ai = if (offset >= 0) i + offset else i
                val bi = if (offset >= 0) i else i - offset
                return popcount(a[ai] xor b[bi]) <= HAMMING_THRESHOLD
            }

            var i = 0
            while (i < n) {
                if (!matches(i)) { i++; continue }
                val start = i
                var last = i
                var gap = 0
                var j = i
                while (j < n) {
                    if (matches(j)) { last = j; gap = 0 } else { gap++; if (gap > MAX_GAP_FRAMES) break }
                    j++
                }
                val current = best
                if (current == null || (last - start) > (current.end - current.start)) {
                    best = RunCandidate(offset, start, last)
                }
                i = j
            }
        }

        val candidate = best ?: return null
        val length = candidate.end - candidate.start + 1
        if (length < MIN_RUN_FRAMES) return null

        // candidate.start/.end are LOGICAL loop positions, not directly either array's own index — the
        // same offset-dependent mapping `matches()` used above (ai = i+offset / bi = i when offset≥0,
        // ai = i / bi = i-offset when offset<0) must convert them back to each array's real coordinates.
        fun aIndex(i: Int) = if (candidate.offset >= 0) i + candidate.offset else i
        fun bIndex(i: Int) = if (candidate.offset >= 0) i else i - candidate.offset
        fun toMs(frame: Int) = ((FRAME_OFFSET_SEC + frame * FRAME_SEC) * 1000).toLong()

        var matchCount = 0
        for (k in candidate.start..candidate.end) {
            val ai = aIndex(k); val bi = bIndex(k)
            if (ai in a.indices && bi in b.indices && popcount(a[ai] xor b[bi]) <= HAMMING_THRESHOLD) matchCount++
        }
        // Match density within the run scaled into the same [0.3, 0.9] presentation range the ffmpeg
        // heuristic uses, so both auto-detected sources read comparably in the admin scrubber.
        val confidence = (0.3 + (matchCount.toDouble() / length) * 0.6).coerceIn(0.3, 0.9)

        return FingerprintIntroMatch(
            aStartMs = toMs(aIndex(candidate.start)), aEndMs = toMs(aIndex(candidate.end)),
            bStartMs = toMs(bIndex(candidate.start)), bEndMs = toMs(bIndex(candidate.end)),
            confidence = confidence,
        )
    }
}
