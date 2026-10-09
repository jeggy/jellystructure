package dev.jellystructure.shared.tv

/**
 * Phase 309 (FR-309-1, owner Q8 2026-10-08) — which stalls count: one that lasts at least [COUNTS_MS], or two that come
 * within [PAIR_WINDOW_MS] of each other. One rule, read by the server's stream record (a counting stall lowers what a
 * device can take) and by the players (a direct play that stalls this way switches to the ladder, FR-309-8), so the two
 * can never disagree about the same stall.
 */
object StallRule {
    const val COUNTS_MS = 2_000L
    const val PAIR_WINDOW_MS = 60_000L

    /** The stalls among [stalls] that count. */
    fun counting(stalls: List<QoeStall>): List<QoeStall> {
        val sorted = stalls.sortedBy { it.afterFirstFrameMs }
        return sorted.filterIndexed { i, s ->
            s.durationMs >= COUNTS_MS ||
                (i > 0 && s.afterFirstFrameMs - sorted[i - 1].afterFirstFrameMs <= PAIR_WINDOW_MS) ||
                (i < sorted.lastIndex && sorted[i + 1].afterFirstFrameMs - s.afterFirstFrameMs <= PAIR_WINDOW_MS)
        }
    }

    /** True when any stall among [stalls] counts. */
    fun anyCounts(stalls: List<QoeStall>): Boolean = counting(stalls).isNotEmpty()
}
