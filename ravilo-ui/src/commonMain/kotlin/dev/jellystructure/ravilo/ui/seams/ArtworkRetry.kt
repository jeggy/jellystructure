package dev.jellystructure.ravilo.ui.seams

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * DEB-4396 (DEB-4390, FR-1) — when a [RemoteImage] load fails, it is tried again after 1, 2, 4, 8 and 16 s,
 * then every 30 s for as long as the tile stays composed. The first load that fails because a TV woke from
 * standby before its Wi-Fi was back used to stay an empty tile until the viewer left the screen.
 */
object ArtworkRetry {
    private val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000)
    const val STEADY_MS = 30_000L

    /** The wait before retry number [failures] (1 = the first failure just happened). */
    fun delayMs(failures: Int): Long = BACKOFF_MS.getOrNull(failures - 1) ?: STEADY_MS

    /**
     * Waits [delayMs] for [failures], or until [ArtworkRetrySignal.nudge] is called — whichever comes first —
     * and returns. Cancelling the caller (the tile left the screen) cancels the wait; nothing retries after it.
     */
    suspend fun waitForRetry(failures: Int, signal: StateFlow<Int> = ArtworkRetrySignal.nudges) {
        val seen = signal.value
        withTimeoutOrNull(delayMs(failures)) { signal.first { it != seen } }
    }
}

/**
 * DEB-4396 (FR-1) — "retry now": the device's network became available, or the app came back to the foreground.
 * Every failed tile waiting out its backoff hears it and tries at once. Platform code calls [nudge]; tiles never do.
 */
object ArtworkRetrySignal {
    private val _nudges = MutableStateFlow(0)
    val nudges: StateFlow<Int> = _nudges

    fun nudge() { _nudges.value += 1 }
}

/**
 * DEB-4396 (FR-3) — a TV that slept for a while holds pooled sockets the network has already dropped; the first
 * requests after waking would wait those out. [shouldEvict] says when the app came back after [THRESHOLD_MS] or
 * more: the platform then drops its idle connections before anything refreshes. A pure clock-in, answer-out rule,
 * so the threshold is tested without a device.
 */
class ForegroundEvictGate {
    private var backgroundedAt: Long? = null

    fun onBackground(nowMs: Long) { if (backgroundedAt == null) backgroundedAt = nowMs }

    /** The app is on screen again at [nowMs]: true when it was away for [THRESHOLD_MS] or more. */
    fun shouldEvict(nowMs: Long): Boolean {
        val since = backgroundedAt ?: return false
        backgroundedAt = null
        return nowMs - since >= THRESHOLD_MS
    }

    companion object { const val THRESHOLD_MS = 60_000L }
}
