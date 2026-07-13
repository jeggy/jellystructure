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
}
