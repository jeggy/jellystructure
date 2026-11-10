package dev.jellystructure.ravilo.ui.desktop

/**
 * DEB-4133 (R337 FR-R337-10, amendment 2026-11-09) — on the desktop, Space is the window's play/pause key, whatever has
 * focus. Compose's `clickable` takes Space as a press, so before this the key pressed whichever control the last click
 * left focused — invisibly, since a click hides the ring (FR-R350-17). The window's preview handler runs first and asks
 * [decide] what a key event is:
 *
 * - [Verdict.TOGGLE] — a bare Space going down while no text field has focus: send `AppCommand.PLAY_PAUSE` and consume
 *   the event, so the focused control never sees it.
 * - [Verdict.SWALLOW] — a repeat of a Space already held, or the Space going up that ends a hold: consumed, nothing
 *   sent. A held key toggles once; the next toggle takes a new press.
 * - [Verdict.PASS] — anything else: not Space; a text field has focus (Space types into it); ⌘, Ctrl, ⌥ or Shift is
 *   held (⌘Space is the system's). Return and keypad Enter are never Space, so they keep pressing what has focus.
 *
 * The film player is not an input here: the command is routed, not the key. While a player is on screen it takes the
 * command itself (`PlayerScreenEffects`) and the bars' handler in `RaviloApp` sees no bar to drive.
 */
object SpaceKey {
    enum class Verdict { TOGGLE, SWALLOW, PASS }

    /** What the key event is, and whether a Space is held once it has been handled. */
    data class Decision(val verdict: Verdict, val heldAfter: Boolean)

    /**
     * The pure decision. [space] — the key is Space; [down] — a `KeyDown` (else `KeyUp`); [modified] — ⌘, Ctrl, ⌥ or
     * Shift is held; [textFieldFocused] — a text field has focus; [held] — a Space went down earlier and has not come
     * up yet, so this `KeyDown` is the key repeating.
     */
    fun decide(space: Boolean, down: Boolean, modified: Boolean, textFieldFocused: Boolean, held: Boolean): Decision {
        if (!space) return Decision(Verdict.PASS, held)
        if (!down) return Decision(if (held && !textFieldFocused) Verdict.SWALLOW else Verdict.PASS, heldAfter = false)   // any Space up ends a hold
        if (textFieldFocused) return Decision(Verdict.PASS, held)
        if (held) return Decision(Verdict.SWALLOW, heldAfter = true)   // key repeat: one toggle per press
        if (modified) return Decision(Verdict.PASS, held)
        return Decision(Verdict.TOGGLE, heldAfter = true)
    }

    @Volatile private var held = false

    /**
     * The stateful edge for the window's preview handler, which passes the key event's parts (a `KeyDown` or `KeyUp`
     * only): the decision, remembering whether Space is held.
     */
    fun onKey(space: Boolean, down: Boolean, modified: Boolean, textFieldFocused: Boolean = DesktopWindow.textFieldFocused): Verdict {
        val d = decide(space = space, down = down, modified = modified, textFieldFocused = textFieldFocused, held = held)
        held = d.heldAfter
        return d.verdict
    }

    /** The window lost focus: a Space whose `KeyUp` went to another window is no longer held. */
    fun reset() { held = false }
}
