package dev.jellystructure.shared.tv

/**
 * Phase 309 (FR-309-4/-5) — the one rule every adaptive player enforces over its own ABR, in one place so Android's
 * track selection and the Cast receiver's Shaka restriction cannot disagree.
 *
 * - **Climb one rung at a time** (FR-309-4): never past the next rung up, and only with enough buffered — 30 s on
 *   Jellyfin's per-rung jobs (a new rung is a cold encode), the players' stock 10 s on our own encoder (every rung is
 *   already running in the one process; owner, 2026-10-08).
 * - **Step down before the buffer runs dry** (FR-309-5): as soon as the buffer ahead is under 20 s and falling, below
 *   the rung playing now (again at every evaluation while it keeps falling: as many rungs as needed).
 *
 * The player's own estimate still decides within that bound (its × 0.7 rule, Media3's / Shaka's own).
 */
object LadderRules {
    const val STEP_DOWN_BUFFER_MS = 20_000L
    const val CLIMB_BUFFER_MS = 30_000L
    const val CLIMB_BUFFER_OURS_MS = 10_000L
    /** Less than this between two evaluations is not "falling" (a segment boundary's jitter). */
    const val FALLING_MARGIN_MS = 500L

    fun climbBufferMs(oursEncoder: Boolean): Long = if (oursEncoder) CLIMB_BUFFER_OURS_MS else CLIMB_BUFFER_MS

    /** FR-309-5 — the buffer is under 20 s and lower than at the last evaluation. */
    fun mustStepDown(bufferedMs: Long, lastBufferedMs: Long?): Boolean =
        bufferedMs < STEP_DOWN_BUFFER_MS && lastBufferedMs != null && bufferedMs < lastBufferedMs - FALLING_MARGIN_MS

    /**
     * The highest variant bandwidth the player may choose now, or null for no bound (nothing playing yet: the start is
     * the server's, FR-309-2). [rungsBps]: the variants' bandwidths, any order; [currentBps]: the one playing.
     */
    fun allowedMaxBps(rungsBps: List<Long>, currentBps: Long?, bufferedMs: Long, lastBufferedMs: Long?, oursEncoder: Boolean): Long? {
        if (currentBps == null || rungsBps.isEmpty()) return null
        val sorted = rungsBps.distinct().sorted()
        if (mustStepDown(bufferedMs, lastBufferedMs)) return sorted.lastOrNull { it < currentBps } ?: sorted.first()
        if (bufferedMs < climbBufferMs(oursEncoder)) return maxOf(currentBps, sorted.first())
        return sorted.firstOrNull { it > currentBps } ?: maxOf(currentBps, sorted.last())
    }
}
