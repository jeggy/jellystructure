package dev.jellystructure.ravilo.ui.desktop

/**
 * DEB-2627 (FR-3) — Linux plays a live ladder channel by choosing its rung itself, because mpv takes one HLS rung at load
 * (`hls-bitrate`) and never switches during playback.
 *
 * - **Start:** the highest rung at or below 0.7 × the link estimate; `max` (the top rung) when there is no estimate.
 * - **Step:** a rebuffer lasting over [LONG_REBUFFER_MS], or a second rebuffer starting within [REPEAT_WINDOW_MS] of the
 *   first one's start, reloads the same master at the live edge on the rung below. The bottom rung stays where it is.
 * - **No climb:** it never steps up inside a tune; the next tune builds a new stepper.
 *
 * Pure: the player feeds it a sample about once a second and acts on the answer.
 */
internal class LiveRungStepper(ladderBps: List<Long>, linkEstimateBps: Long?) {
    private val rungs: List<Long> = ladderBps.filter { it > 0 }.distinct().sortedDescending()
    private val watch = RebufferWatch()

    /** The rung playing now; the top one while no estimate chose another. */
    var current: Long = startRung(rungs, linkEstimateBps) ?: rungs.firstOrNull() ?: 0L
        private set

    /** What mpv's `hls-bitrate` starts at: the chosen rung, or null for `max` (no estimate). */
    val startBitrate: Long? = if (linkEstimateBps == null || linkEstimateBps <= 0) null else current

    /** Feed one sample ([buffering]: the viewer wants playback and the player is waiting for data). Returns the rung to reload on, or null to leave it. */
    fun onSample(nowMs: Long, buffering: Boolean): Long? {
        if (!watch.onSample(nowMs, buffering)) return null
        watch.reset()
        val below = rungBelow(rungs, current)
        if (below == current) return null   // already the bottom: it stays
        current = below
        return below
    }

    /** A reload is under way: the waits it causes are not stalls. */
    fun reloaded() = watch.reset()

    companion object {
        const val LONG_REBUFFER_MS = 5_000L
        const val REPEAT_WINDOW_MS = 30_000L
        const val LINK_FRACTION = 0.7

        /** The highest rung at or below [LINK_FRACTION] × [linkEstimateBps]; the lowest when none fits; null with no estimate (= `max`). */
        fun startRung(rungs: List<Long>, linkEstimateBps: Long?): Long? {
            if (rungs.isEmpty() || linkEstimateBps == null || linkEstimateBps <= 0) return null
            val budget = linkEstimateBps * LINK_FRACTION
            return rungs.filter { it <= budget }.maxOrNull() ?: rungs.min()
        }

        /** The next rung under [current]; [current] itself at the bottom. */
        fun rungBelow(rungs: List<Long>, current: Long): Long = rungs.filter { it < current }.maxOrNull() ?: current
    }
}

/** DEB-2627 (FR-3) — when a stream's waits are bad enough to step down: one over [LiveRungStepper.LONG_REBUFFER_MS], or a second starting within [LiveRungStepper.REPEAT_WINDOW_MS] of the first's start. */
internal class RebufferWatch {
    private var stallStartMs: Long? = null
    private var previousStallStartMs: Long? = null

    fun onSample(nowMs: Long, buffering: Boolean): Boolean {
        val started = stallStartMs
        if (!buffering) {
            if (started != null) { previousStallStartMs = started; stallStartMs = null }
            return false
        }
        if (started == null) {
            stallStartMs = nowMs
            val previous = previousStallStartMs
            return previous != null && nowMs - previous <= LiveRungStepper.REPEAT_WINDOW_MS
        }
        return nowMs - started > LiveRungStepper.LONG_REBUFFER_MS
    }

    fun reset() { stallStartMs = null; previousStallStartMs = null }
}
