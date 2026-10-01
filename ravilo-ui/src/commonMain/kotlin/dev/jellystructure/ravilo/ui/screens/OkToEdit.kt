package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.SoftwareKeyboardController
import dev.jellystructure.ravilo.ui.isTvPlatform

/**
 * R350 (re-test on the TV, 2026-10-02 — FR-R350-13 and FR-R350-15) — on a TV a text field takes D-pad focus like any
 * other control (the caller draws a ring from [focused]) and the system keyboard opens only on OK. Off a TV this is
 * inert: the field is always editable and a tap opens the keyboard, as before.
 *
 * **Why not `KeyboardOptions(showKeyboardOnFocus = false)`:** only the state-based `BasicTextField(TextFieldState)`
 * reads it. The `BasicTextField(value: String)` every Ravilo form uses is Compose's legacy `CoreTextField`, which starts
 * an input session whenever it gains focus while editable — and on Android starting a session shows the keyboard
 * (`TextInputServiceAndroid`: *"It doesn't make sense to start a new connection without the keyboard showing"*). The
 * flag was silently ignored, so the Sony raised the keyboard the moment Search's field took focus on arrival.
 *
 * So on a TV the field is **read-only until OK**: a read-only legacy field starts no input session when focused, and
 * turning it editable while it has focus starts one (and the keyboard with it). Leaving the field makes it read-only
 * again, so D-pad focus coming back to it never opens the keyboard.
 *
 * One more way the legacy field opens the keyboard regardless of `readOnly`: its first-composition effect restarts input
 * if the field already holds focus by then. A screen that focuses a field on arrival waits a frame first
 * ([awaitFieldReady]), so that effect has run and found nothing focused.
 */
@Stable
internal class OkToEdit internal constructor(val tv: Boolean) {
    /** The field takes typing and holds an input session while focused. Always true off a TV. */
    var editing by mutableStateOf(!tv)
        private set

    /** The field has focus — what the caller's focus ring reads. Only tracked on a TV (R298: no focus visuals on a handset). */
    var focused by mutableStateOf(false)
        private set

    val readOnly: Boolean get() = !editing

    private var startedBy: Key? = null

    /** An IME action (*Next*) hands over to this field: the viewer is typing, so typing carries on there, keyboard up. */
    fun continueTyping() { if (tv) editing = true }

    internal fun onFocus(isFocused: Boolean) {
        focused = isFocused
        if (!isFocused && tv) editing = false
    }

    /** OK (or a keyboard's Enter) on a focused field that is not being edited starts editing; both halves of the press
     *  are eaten so neither reaches the field. OK on a field already being edited (the keyboard closed with Back) opens
     *  the keyboard again. */
    internal fun onKey(key: Key, down: Boolean, keyboard: SoftwareKeyboardController?): Boolean {
        val ok = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
        if (!ok) return false
        if (!down && key == startedBy) { startedBy = null; return true }   // the release of the press that started editing
        if (!editing) {
            if (down) { editing = true; startedBy = key }
            return true
        }
        if (key == Key.DirectionCenter) {
            if (down) keyboard?.show()
            return true
        }
        return false
    }
}

/** Suspend until the next frame: call before an arrival `requestFocus()` on a text field (see [OkToEdit]). */
internal suspend fun awaitFieldReady() { androidx.compose.runtime.withFrameNanos { } }

@Composable
internal fun rememberOkToEdit(): OkToEdit {
    val tv = isTvPlatform
    return remember(tv) { OkToEdit(tv) }
}

/** On the text field itself, ahead of its own key handling. Pass `readOnly = state.readOnly` to the field. */
internal fun Modifier.okToEdit(state: OkToEdit, keyboard: SoftwareKeyboardController?): Modifier =
    if (!state.tv) this
    else this
        .onFocusChanged { state.onFocus(it.isFocused) }
        .onPreviewKeyEvent { ev ->
            when (ev.type) {
                KeyEventType.KeyDown -> state.onKey(ev.key, down = true, keyboard = keyboard)
                KeyEventType.KeyUp -> state.onKey(ev.key, down = false, keyboard = keyboard)
                else -> false
            }
        }
