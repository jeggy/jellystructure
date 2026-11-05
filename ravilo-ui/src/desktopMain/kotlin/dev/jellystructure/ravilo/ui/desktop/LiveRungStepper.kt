package dev.jellystructure.ravilo.ui.desktop

/**
 * DEB-2627 (FR-3) — Linux plays a live ladder channel by choosing its rung itself, because mpv takes one HLS rung at load
 * (`hls-bitrate`) and never switches during playback.
 *
 * - **Start:** the highest rung at or below 0.7 × the link estimate; `max` (the top rung) when there is no estimate.
 * - **Step:** a rebuffer lasting over [LONG_REBUFFER_MS], or a second rebuffer starting within [REPEAT_WINDOW_MS] of the
 *   first one's start, reloads the same master at the live edge. DEB-3740 (FR-3.1): on the highest rung at or below
 *   0.7 × the throughput measured over the last [THROUGHPUT_WINDOW_MS], and always under the rung playing; the next rung
 *   below when there is no usable measurement. The bottom rung stays where it is.
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

    private val bytes = ArrayDeque<Pair<Long, Long>>()   // (nowMs, bytes the demuxer has taken in) since the tune or the last reload

    /** The throughput the last step used, bits/s; null when it fell back to the next rung below. */
    var lastThroughputBps: Long? = null
        private set

    /**
     * Feed one sample ([buffering]: the viewer wants playback and the player is waiting for data; [demuxedBytes]: what the
     * engine has taken in since the last load, null when it cannot say). Returns the rung to reload on, or null to leave it.
     */
    fun onSample(nowMs: Long, buffering: Boolean, demuxedBytes: Long? = null): Long? {
        if (demuxedBytes != null) {
            bytes.addLast(nowMs to demuxedBytes)
            while (bytes.size > 1 && nowMs - bytes.first().first > THROUGHPUT_WINDOW_MS) bytes.removeFirst()
        }
        if (!watch.onSample(nowMs, buffering)) return null
        watch.reset()
        val measured = throughputBps()
        val target = stepTarget(rungs, current, measured)
        if (target == current) return null   // already the bottom: it stays
        lastThroughputBps = measured
        current = target
        return target
    }

    /** A reload is under way: the waits it causes are not stalls, and its byte count starts from zero. */
    fun reloaded() { watch.reset(); bytes.clear() }

    /** Bits/s the demuxer took in over the window, or null while the window spans under [MIN_WINDOW_MS] or the count went backwards. */
    private fun throughputBps(): Long? {
        val first = bytes.firstOrNull() ?: return null
        val last = bytes.last()
        val ms = last.first - first.first
        val delta = last.second - first.second
        if (ms < MIN_WINDOW_MS || delta < 0) return null
        return delta * 8_000L / ms
    }

    companion object {
        const val LONG_REBUFFER_MS = 5_000L
        const val REPEAT_WINDOW_MS = 30_000L
        const val LINK_FRACTION = 0.7
        const val THROUGHPUT_WINDOW_MS = 10_000L
        const val MIN_WINDOW_MS = 3_000L

        /** The highest rung at or below [LINK_FRACTION] × [linkEstimateBps]; the lowest when none fits; null with no estimate (= `max`). */
        fun startRung(rungs: List<Long>, linkEstimateBps: Long?): Long? {
            if (rungs.isEmpty() || linkEstimateBps == null || linkEstimateBps <= 0) return null
            val budget = linkEstimateBps * LINK_FRACTION
            return rungs.filter { it <= budget }.maxOrNull() ?: rungs.min()
        }

        /**
         * DEB-3740 (FR-3.1) — where a step lands: the highest rung at or below [LINK_FRACTION] × [throughputBps] and under
         * [current]; the lowest when none fits; the next rung below with no measurement. [current] itself at the bottom.
         */
        fun stepTarget(rungs: List<Long>, current: Long, throughputBps: Long?): Long {
            val below = rungBelow(rungs, current)
            if (below == current || throughputBps == null || throughputBps <= 0) return below
            val fit = startRung(rungs, throughputBps) ?: return below
            return minOf(fit, below)
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
