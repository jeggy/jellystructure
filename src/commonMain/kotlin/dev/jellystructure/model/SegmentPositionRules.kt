package dev.jellystructure.model

/**
 * Phase 233 (FR-233-1) — where in its file an automatically detected marker may sit. Pure; no DOM, no DB.
 *
 * An intro must END at or before half the file; a credits marker must START at or after half. Half is
 * deliberately coarse: it is not an estimate of where themes live, it is the one line no intro and no
 * end-credits can be on the wrong side of — and it makes an intro/credits overlap impossible by
 * construction. Before this rule nothing in `detect_segments` knew a file's duration when it accepted a
 * marker, and 775 production credits markers started inside the intro.
 *
 * An unknown duration judges nothing. Other kinds are not judged. A human-touched marker is never
 * judged — the operator may know about a 20-minute post-credits reel ([humanTouched]).
 */
object SegmentPositionRules {
    const val INTRO = "intro"
    const val CREDITS = "credits"
    const val MANUAL = "manual"

    fun plausible(kind: String, startMs: Long, endMs: Long?, durationMs: Long?): Boolean {
        if (durationMs == null || durationMs <= 0L) return true
        val half = durationMs / 2
        return when (kind) {
            INTRO -> (endMs ?: startMs) <= half
            CREDITS -> startMs >= half
            else -> true
        }
    }

    /** Credits beginning before the intro is over — judgeable with no duration at all. */
    fun creditsInsideIntro(introStartMs: Long, introEndMs: Long?, creditsStartMs: Long): Boolean =
        creditsStartMs < (introEndMs ?: (introStartMs + 1))

    /** A row a person has written, locked or confirmed. Never deleted, never hidden. */
    fun humanTouched(source: String?, locked: Boolean, checkedAt: Long?): Boolean =
        source == MANUAL || locked || checkedAt != null
}
