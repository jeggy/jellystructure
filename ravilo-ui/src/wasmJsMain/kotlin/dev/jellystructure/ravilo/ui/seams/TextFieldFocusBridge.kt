@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import kotlin.js.ExperimentalWasmJsInterop

// A counter, not a boolean — two fields can briefly overlap focus during a focus-requester jump
// (new field's onFocusChanged can fire before the old field's blur), so a plain flag could get
// stuck cleared by the second event. index.html reads window.raviloTextFieldFocused.
private fun jsMarkTextFieldFocus(delta: Int): Unit =
    js("window.raviloTextFieldFocused = (window.raviloTextFieldFocusCount = (window.raviloTextFieldFocusCount || 0) + delta) > 0")

actual fun Modifier.reportTextFieldFocus(): Modifier = composed {
    // A field disposed while still focused (e.g. navigating away mid-edit) never fires a final
    // blur onFocusChanged — track locally so the dispose cleanup can still release its increment.
    val wasFocused = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (wasFocused[0]) jsMarkTextFieldFocus(-1) }
    }
    this.onFocusChanged { state ->
        if (state.isFocused == wasFocused[0]) return@onFocusChanged
        wasFocused[0] = state.isFocused
        jsMarkTextFieldFocus(if (state.isFocused) 1 else -1)
    }
}
