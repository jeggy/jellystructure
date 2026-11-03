package dev.jellystructure.ravilo.ui.seams

/**
 * R376 (FR-R376-4f–4h, amendment 2026-10-30, DEB-2998) — what a browser page's hide, return and leave events mean for
 * the player, decided once and without a browser. The wasmJs `PlayerLifecycleEffect` feeds it the DOM's events and does
 * what the answer says.
 *
 * - **A computer** ([phone] false): [Signal.HIDDEN] and [Signal.VISIBLE] answer nothing, in either direction — a hidden
 *   tab keeps its film (FR-R376-4f). Only leaving ends it: [Signal.PAGE_HIDE] answers one [Action.Stop], and the return
 *   from the back-forward cache ([Signal.PAGE_SHOW] or the [Signal.VISIBLE] that comes with it) answers one
 *   [Action.Foreground] (FR-R376-4g).
 * - **A phone or tablet** ([phone] true): hidden still stops, and the return is still a fresh start (FR-R376-4h), except
 *   while the picture is in picture-in-picture or on AirPlay ([exempt]) — the viewer is still watching then.
 *
 * The `away` latch is FR-R372-11's: the first of `pagehide` and `visibilitychange → hidden` stops, a second one does not,
 * and only a return clears it. `pagehide` is never exempt — a page being unloaded takes its picture with it.
 */
class WebPageLifecycleGate(private val phone: Boolean) {
    enum class Signal { HIDDEN, VISIBLE, PAGE_HIDE, PAGE_SHOW }
    enum class Action { None, Stop, Foreground }

    private var away = false

    /** [exempt]: the picture is in picture-in-picture or on AirPlay. Read on [Signal.HIDDEN] only. */
    fun on(signal: Signal, exempt: Boolean = false): Action = when (signal) {
        Signal.HIDDEN -> if (phone && !exempt) leave() else Action.None
        Signal.PAGE_HIDE -> leave()
        Signal.VISIBLE, Signal.PAGE_SHOW -> if (away) { away = false; Action.Foreground } else Action.None
    }

    private fun leave(): Action = if (away) Action.None else { away = true; Action.Stop }
}
