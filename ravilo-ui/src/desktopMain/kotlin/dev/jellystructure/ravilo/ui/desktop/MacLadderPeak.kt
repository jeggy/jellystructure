package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.ui.DesktopApp
import dev.jellystructure.ravilo.ui.screens.CLIMB_BLOCK_AFTER_RECOVERY_MS
import dev.jellystructure.ravilo.ui.seams.PlayerLadderHints
import dev.jellystructure.shared.tv.masterBandwidths
import dev.jellystructure.shared.tv.nextPeakBps
import dev.jellystructure.shared.tv.peakAfterStall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Request
import kotlin.concurrent.Volatile

/**
 * Phase 309 (FR-309-9) — the Mac's AVPlayer climbs on its own and knows no "one rung at a time", so its climb is bounded
 * from here: `preferredPeakBitRate` starts at the rung the server started the play on, rises one rung at a time once
 * enough is buffered and drops as soon as the buffer runs low ([nextPeakBps], the same rule as Android's track selection
 * and the Cast receiver's Shaka). Only for a ladder (`adaptive` ticket) on the Mac; mpv is stepped by the store instead.
 *
 * DEB-3214 (DEB-3093 FR-5, FR-9) — a stall (`AVPlayerItemPlaybackStalled`, counted by the library) caps the peak one rung down at once and
 * nothing climbs for [CLIMB_BLOCK_AFTER_RECOVERY_MS], as Android's in-place cap does ([onStall]); a recovery's climb block (the store's) holds the climb too.
 *
 * [start] runs on the player's thread at each load; the master is read once off that thread; [tick] (every couple of
 * seconds, from the player's watch loop) is the only place the engine is told anything, so every AVPlayer call stays on
 * the one thread the library expects.
 */
internal class MacLadderPeak(private val engine: DesktopEngine) {
    @Volatile internal var rungs: List<Long> = emptyList()
    private var peak: Long? = null
    private var lastBufferedMs: Long? = null
    /** The item's stall count already answered; the library zeroes it with each load. */
    private var stallsSeen = 0L
    @Volatile private var generation = 0

    fun start(masterUrl: String, scope: CoroutineScope) {
        val gen = ++generation
        rungs = emptyList(); peak = null; lastBufferedMs = null; stallsSeen = 0L
        if (!PlayerLadderHints.adaptive) return
        scope.launch(Dispatchers.IO) {
            val found = runCatching {
                DesktopApp.okHttp.newCall(Request.Builder().url(masterUrl).build()).execute().use { r ->
                    if (r.isSuccessful) masterBandwidths(r.body.string()) else emptyList()
                }
            }.getOrDefault(emptyList())
            if (gen == generation) rungs = found.sorted()
        }
    }

    /**
     * FR-5 — called every watch tick: a stall the library has counted since the last call caps the peak one rung down now (the lowest rung
     * has nothing below it and is left alone, with no climb block) and blocks the climb for 2 minutes; AVPlayer takes the lower peak for the
     * next segment it picks. Not a ladder (no rungs read), no cap.
     */
    fun onStall(state: MacPlayerState, nowMs: Long = System.currentTimeMillis()) {
        if (state.stalls <= stallsSeen) { stallsSeen = state.stalls; return }
        stallsSeen = state.stalls
        val r = rungs
        if (r.isEmpty()) return
        val from = peak ?: startPeak(r, PlayerLadderHints.startVariantBps)
        val next = peakAfterStall(r, peak, from)
        if (next >= from) return
        PlayerLadderHints.climbBlockedUntilMs = nowMs + CLIMB_BLOCK_AFTER_RECOVERY_MS
        peak = next
        engine.setPeakBitrate(next * PEAK_HEADROOM)
        println("${DesktopLog.stamp()} [player] DEB-3214 stall → peak ${from / 1000}k → ${next / 1000}k, no climb for ${CLIMB_BLOCK_AFTER_RECOVERY_MS / 1000} s")
    }

    fun tick(state: MacPlayerState, nowMs: Long = System.currentTimeMillis()) {
        val r = rungs
        if (r.isEmpty() || !state.ready) return
        val ahead = (state.bufferedMs - state.positionMs).coerceAtLeast(0L)
        val current = peak
        val next = if (current == null) startPeak(r, PlayerLadderHints.startVariantBps)
            else nextPeakBps(r, current, ahead, lastBufferedMs, PlayerLadderHints.oursEncoder, PlayerLadderHints.climbBlocked(nowMs))
        lastBufferedMs = ahead
        if (next == current) return
        peak = next
        engine.setPeakBitrate(next * PEAK_HEADROOM)
        println("${DesktopLog.stamp()} [player] 309 peak ${current?.let { "${it / 1000}k → " } ?: ""}${next / 1000}k (${ahead} ms buffered)")
    }

    companion object {
        /** AVPlayer compares the peak with a variant's `BANDWIDTH`; a hair above keeps the rung itself allowed. */
        const val PEAK_HEADROOM = 1.05

        /** The rung the server started on (its `BANDWIDTH`), or the closest one under it; the lowest when unknown. */
        fun startPeak(rungs: List<Long>, startVariantBps: Long?): Long {
            val sorted = rungs.sorted()
            if (startVariantBps == null || startVariantBps <= 0) return sorted.first()
            return sorted.lastOrNull { it <= startVariantBps } ?: sorted.first()
        }
    }
}
