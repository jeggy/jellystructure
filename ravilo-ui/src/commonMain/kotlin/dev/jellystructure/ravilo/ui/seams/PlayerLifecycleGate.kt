package dev.jellystructure.ravilo.ui.seams

/**
 * R292 (FR-R292-1/8) — which lifecycle signals mean "the player is off screen" and "it is back", decided
 * once, in one place, without a platform: the Android effect feeds it `ON_PAUSE`/`ON_RESUME`/`ON_STOP`/
 * `ON_START` and the `ACTION_SCREEN_OFF`/`ACTION_SCREEN_ON` broadcasts (a TV entering standby may send no
 * `ON_STOP`, R220 §2.4), and it answers with at most one [Action.Background] per transition and at most
 * one [Action.Foreground] after it — a real `ON_STOP` after `SCREEN_OFF` is a no-op, as is `ON_START`
 * replayed to a freshly added observer.
 *
 * `ON_PAUSE` only pauses (an overlay such as the assistant pauses; it does not tear down), remembering
 * whether the viewer was playing so a plain `ON_RESUME` — one with no background in between — can
 * resume. Once a background happened the engine is gone, so `ON_RESUME` must never call play(): the
 * play intent travels in the resume record instead (dev review item 1).
 */
class PlayerLifecycleGate {
    enum class Signal { PAUSE, RESUME, STOP, START, SCREEN_OFF, SCREEN_ON }
    sealed class Action {
        data object None : Action()
        data object Pause : Action()
        /** A plain resume after a pause with no background in between: play again if the viewer was playing. */
        data object ResumePlayback : Action()
        /** Off screen: capture the record, stop the session, release the engine. [wasPlaying] is the intent to keep. */
        data class Background(val wasPlaying: Boolean) : Action()
        /** Back on screen after a background: a new start from the record. */
        data object Foreground : Action()
    }

    private var pausedWhilePlaying = false
    private var backgrounded = false

    /** [playing] is the viewer's current play intent, read only on [Signal.PAUSE]; [onScreen] is whether the
     *  lifecycle is at least STARTED, read only on [Signal.SCREEN_ON]. */
    fun on(signal: Signal, playing: () -> Boolean = { false }, onScreen: () -> Boolean = { true }): Action = when (signal) {
        Signal.PAUSE -> { if (!backgrounded) pausedWhilePlaying = playing(); Action.Pause }
        Signal.RESUME -> if (!backgrounded && pausedWhilePlaying) { pausedWhilePlaying = false; Action.ResumePlayback } else Action.None
        Signal.STOP, Signal.SCREEN_OFF ->
            if (backgrounded) Action.None
            else { backgrounded = true; val was = pausedWhilePlaying || (signal == Signal.SCREEN_OFF && playing()); pausedWhilePlaying = false; Action.Background(was) }
        Signal.START -> if (backgrounded) { backgrounded = false; Action.Foreground } else Action.None
        Signal.SCREEN_ON -> if (backgrounded && onScreen()) { backgrounded = false; Action.Foreground } else Action.None
    }
}
