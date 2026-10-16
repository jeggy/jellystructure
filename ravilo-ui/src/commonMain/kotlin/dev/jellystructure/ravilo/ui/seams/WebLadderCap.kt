package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.LadderRules

/**
 * Phase 309 (FR-309-16) — the web player's hls.js climbs on its own estimate and knows no "one rung at a time" nor the
 * 20 s buffer rule, so the same rule Android and the Cast receiver enforce ([LadderRules]) bounds it from outside, as
 * `autoLevelCapping`. One per player; [evaluate] runs about once a second while a ladder plays.
 *
 * Pure: the caller reads hls.js and the element and passes numbers in; what comes back is the level to cap at.
 */
class WebLadderCap {
    /** What to tell hls.js: cap its ABR at [capLevel]; when [stepDown], also move the next segment there. */
    data class Decision(val capLevel: Int, val stepDown: Boolean)

    private var lastBufferedMs: Long? = null

    /** A seek, a source change or a stream with no ladder: the next sample is only a starting point (FR-309-16). */
    fun reset() { lastBufferedMs = null }

    /**
     * [levelsBps]: the `BANDWIDTH` of hls.js's levels in its own order (ascending, the index is hls.js's);
     * [currentLevel]: the level playing, -1 before one is; [bufferedMs]: the buffer ahead of the playhead.
     * Null = no bound (nothing playing yet, or no ladder): the start is the server's (FR-309-2).
     */
    fun evaluate(levelsBps: List<Long>, currentLevel: Int, bufferedMs: Long, oursEncoder: Boolean): Decision? {
        val current = levelsBps.getOrNull(currentLevel)
        if (current == null || levelsBps.size < 2) { lastBufferedMs = null; return null }
        val last = lastBufferedMs
        lastBufferedMs = bufferedMs
        val allowed = LadderRules.allowedMaxBps(levelsBps, current, bufferedMs, last, oursEncoder) ?: return null
        // Equal bandwidths are one rung (several audio groups): the highest index within the bound.
        val cap = levelsBps.indexOfLast { it <= allowed }.coerceAtLeast(0)
        return Decision(cap, stepDown = LadderRules.mustStepDown(bufferedMs, last) && cap < currentLevel)
    }
}
