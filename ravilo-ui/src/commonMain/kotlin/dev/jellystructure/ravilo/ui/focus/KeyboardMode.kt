package dev.jellystructure.ravilo.ui.focus

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver

/**
 * R337 — on a computer the focus ring follows the keyboard: it shows once a navigation key is pressed and goes when the
 * pointer is used again, as the platforms' own focus rings (and a browser's `:focus-visible`) do. Provided to the app
 * as [LocalFocusVisible].
 *
 * R350 (FR-R350-17) — *used again* means the pointer really moved or pressed. Compose Desktop sends a synthetic Move
 * at the pointer's last position whenever the layout under it changes (`SyntheticEventSender.updatePointerPosition`),
 * so leaving the player for the series page — a whole new layout under a pointer nobody touched — read as mouse use:
 * the mode went off, and the *Resume* button R350 FR-2a focused on the way back drew no ring. A Move that does not move
 * is ignored now. Esc and Enter count as keyboard use too (a Back by Esc is a keyboard arrival, and a page entered by
 * Enter on a button was entered by keyboard), so a focus the app restores after either of them is drawn.
 */
class KeyboardMode(initial: Boolean = false) {
    /** The ring is drawn. */
    var on by mutableStateOf(initial)
        private set
    private var lastPointer: Offset? = null

    /** A key went down. */
    fun onKey(key: Key) {
        if (key in NAVIGATION_KEYS) on = true
    }

    /** A pointer event reached the window, at [position] in the window's (root's) coordinates. */
    fun onPointer(type: PointerEventType, position: Offset?) {
        val before = lastPointer
        if (position != null) lastPointer = position
        when (type) {
            PointerEventType.Press -> on = false
            // A Move counts only when the pointer went somewhere. The first Move after the window opens has nothing to
            // compare with: it records the position and leaves the mode alone (real motion follows at once).
            PointerEventType.Move -> if (before != null && position != null && (position - before).getDistance() > MOVE_SLOP_PX) on = false
        }
    }

    companion object {
        /** Tab, the arrows, Enter and Esc: the keys a keyboard user moves, activates and goes back with. */
        val NAVIGATION_KEYS: Set<Key> = setOf(
            Key.Tab, Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
            Key.Enter, Key.NumPadEnter, Key.Escape,
        )
        /** Under a pixel of travel is the same place (a synthetic Move repeats the last position exactly). */
        const val MOVE_SLOP_PX = 1f
    }
}

/** Wires [mode] to the window: every key (preview, so nothing focused can hide one) and every pointer event (the
 *  Initial pass, so nothing consumes one first). Mount once, at the app root, on a computer. */
fun Modifier.keyboardMode(mode: KeyboardMode): Modifier = this
    .onPreviewKeyEvent { ev ->
        if (ev.type == KeyEventType.KeyDown) mode.onKey(ev.key)
        false
    }
    .pointerInput(mode) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                mode.onPointer(e.type, e.changes.firstOrNull()?.position)
            }
        }
    }

/**
 * R350 (FR-R350-17) — a component that paints a focus ring says whether it is painting one, so a walk test can check
 * what the viewer sees and not only where focus is (the Mac's *Resume* had focus and no ring).
 */
val FocusRingShown = SemanticsPropertyKey<Boolean>("FocusRingShown")
var SemanticsPropertyReceiver.focusRingShown by FocusRingShown
