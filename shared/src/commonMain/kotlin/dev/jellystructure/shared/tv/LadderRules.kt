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

/**
 * Phase 309 (FR-309-9) — the variants' `BANDWIDTH` values of an HLS master playlist (any order, duplicates dropped), for
 * a player that bounds its own ABR from outside (the Mac's AVPlayer: `preferredPeakBitRate`). Empty for a media
 * playlist or anything unparsable.
 */
fun masterBandwidths(master: String): List<Long> =
    master.lineSequence()
        .filter { it.startsWith("#EXT-X-STREAM-INF:") }
        .mapNotNull { Regex("(?:^|[:,])BANDWIDTH=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
        .filter { it > 0 }
        .distinct()
        .toList()

/**
 * Phase 309 (FR-309-9) — AVPlayer climbs on its own and has no "one rung at a time" rule, so the Mac bounds it with
 * `preferredPeakBitRate`: the peak is the rung the player may use, raised one rung at a time once enough is buffered and
 * lowered as soon as the buffer runs low ([LadderRules.allowedMaxBps] with the peak as the playing rung). Returns the
 * new peak in the master's units (a variant's `BANDWIDTH`); [peakBps] unchanged when nothing moves.
 */
fun nextPeakBps(rungsBps: List<Long>, peakBps: Long, bufferedMs: Long, lastBufferedMs: Long?, oursEncoder: Boolean): Long =
    LadderRules.allowedMaxBps(rungsBps, peakBps, bufferedMs, lastBufferedMs, oursEncoder) ?: peakBps

/**
 * Phase 309 (FR-309-9) — a player that cannot switch variants itself (mpv on Linux: `hls-bitrate` picks one at open) is
 * stepped by the app: a restream at the same position with a lower or higher `max_video_bitrate`, which bounds the one
 * rung the server makes for it.
 *
 * - **Down** as soon as the buffer is under 20 s and falling ([LadderRules.mustStepDown]), to the next rung below the one
 *   playing; never twice within [MIN_STEP_GAP_MS] (a restream needs its own start).
 * - **Up** one rung after [CLEAN_FOR_UP_MS] without a step down and with [LadderRules.CLIMB_BUFFER_MS] buffered; above
 *   the highest rung the cap is lifted (0 = no cap).
 *
 * Pure: every call carries its own clock; no I/O. One per play.
 */
class RestreamStepper(private val ladderVideoBps: List<Long> = DEFAULT_LADDER_VIDEO_BPS) {
    data class Step(val capVideoBps: Long, val down: Boolean)

    private var lastBufferedMs: Long? = null
    private var cleanSinceMs: Long? = null
    private var lastStepAtMs: Long = Long.MIN_VALUE / 2

    /** The cap now in force (0 = none) — what the next step starts from. */
    var capVideoBps: Long = 0L
        private set

    /**
     * [streamVideoBps]: the video bitrate of the stream playing (from the ticket's start rung), when known; used only
     * while no cap is in force. Returns the step to make, or null.
     */
    fun evaluate(nowMs: Long, bufferedMs: Long, streamVideoBps: Long?): Step? {
        val last = lastBufferedMs
        lastBufferedMs = bufferedMs
        if (cleanSinceMs == null) cleanSinceMs = nowMs
        val sorted = ladderVideoBps.distinct().sorted()
        if (sorted.isEmpty()) return null
        val current = if (capVideoBps > 0) capVideoBps else streamVideoBps ?: Long.MAX_VALUE
        if (LadderRules.mustStepDown(bufferedMs, last)) {
            cleanSinceMs = nowMs
            if (nowMs - lastStepAtMs < MIN_STEP_GAP_MS) return null
            val below = sorted.lastOrNull { it < current } ?: return null
            return step(nowMs, below, down = true)
        }
        if (capVideoBps <= 0) return null   // nothing to climb back to: the stream is already uncapped
        val clean = cleanSinceMs ?: nowMs
        if (nowMs - clean < CLEAN_FOR_UP_MS || bufferedMs < LadderRules.CLIMB_BUFFER_MS) return null
        if (nowMs - lastStepAtMs < MIN_STEP_GAP_MS) return null
        val above = sorted.firstOrNull { it > capVideoBps } ?: 0L
        return step(nowMs, above, down = false)
    }

    private fun step(nowMs: Long, cap: Long, down: Boolean): Step {
        capVideoBps = cap
        lastStepAtMs = nowMs
        cleanSinceMs = nowMs
        lastBufferedMs = null
        return Step(cap, down)
    }

    companion object {
        /** 308's ladder rungs below the top (video bits/s) — `LADDER` in the server's `VideoLadder.kt`. */
        val DEFAULT_LADDER_VIDEO_BPS = listOf(1_500_000L, 4_000_000L, 8_000_000L, 12_000_000L)
        const val MIN_STEP_GAP_MS = 30_000L
        const val CLEAN_FOR_UP_MS = 120_000L

        /** A variant's `BANDWIDTH` (video × 1.5 + audio, FR-313-7) back to its video bits/s, roughly; null when unknown. */
        fun videoBpsOf(bandwidthBps: Long?): Long? =
            bandwidthBps?.takeIf { it > 0 }?.let { (((it - 256_000L).coerceAtLeast(it / 2)) / 1.5).toLong() }
    }
}
