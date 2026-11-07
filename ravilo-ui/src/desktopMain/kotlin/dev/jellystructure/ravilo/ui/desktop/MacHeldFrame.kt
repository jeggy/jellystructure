package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.ui.seams.WebHeldFrame

/**
 * DEB-3214 (DEB-3093 FR-6, FR-9) — the Mac's replacement window. A stream replaced behind the picture is a new `AVPlayerItem`: until its
 * start seek has landed AVPlayer reports a position of 0 and no duration, and a clock that reads 0:00 in the middle of a film is chrome the
 * switch must not show. The picture itself is held by the surface (Compose keeps the last frame it drew), so what is left to hold is the
 * clock: for as long as the window is open, the position is where the replacement starts and the length is the one the play had
 * ([WebHeldFrame], the web's rule for the same gap).
 *
 * The window opens at a load made while a picture was on screen and closes with the new item's first frame, its failure, or [MAX_HOLD_MS].
 * One per player, on Compose's thread.
 */
internal class MacHeldFrame {
    private var open = false
    private var startMs = 0L
    private var durationMs = 0L
    private var sinceMs = 0L

    /** A load at [startMs] is about to replace the item playing, whose picture was on screen when [shown]. */
    fun begin(shown: Boolean, startMs: Long, durationMs: Long, nowMs: Long) {
        open = shown
        this.startMs = startMs
        this.durationMs = durationMs
        sinceMs = nowMs
    }

    /** Whether the window is open at [nowMs], closing it when the new item has its first frame, has failed, or has taken too long. */
    fun holding(state: MacPlayerState, nowMs: Long): Boolean {
        if (open && (state.firstFrame || state.failed || nowMs - sinceMs > MAX_HOLD_MS)) open = false
        return open
    }

    /** A seek inside the window moves where the replacement starts. */
    fun seek(ms: Long) { if (open) startMs = ms }

    fun positionMs(state: MacPlayerState, nowMs: Long): Long = WebHeldFrame.positionMs(holding(state, nowMs), startMs, state.positionMs)

    fun durationMs(state: MacPlayerState, nowMs: Long): Long = WebHeldFrame.durationMs(holding(state, nowMs), durationMs, state.durationMs.coerceAtLeast(0L))

    /** The most a replacement may hold the clock: after it the player reports what it has (and FR-6's own 2 s hold on the screen has long ended). */
    companion object {
        const val MAX_HOLD_MS = 10_000L
    }
}
