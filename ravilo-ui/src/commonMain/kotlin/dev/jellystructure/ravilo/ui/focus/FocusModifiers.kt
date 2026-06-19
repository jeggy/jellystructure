package dev.jellystructure.ravilo.ui.focus

import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

fun Modifier.dpadFocusable(
    focusRequester: FocusRequester,
    onFocused: () -> Unit = {},
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onSelect: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
): Modifier = this
    .onKeyEvent { ev ->
        if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (ev.key) {
            Key.DirectionLeft  -> onLeft?.invoke()?.let { true } ?: false
            Key.DirectionRight -> onRight?.invoke()?.let { true } ?: false
            Key.DirectionUp    -> onUp?.invoke()?.let { true } ?: false
            Key.DirectionDown  -> onDown?.invoke()?.let { true } ?: false
            Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> onSelect?.invoke()?.let { true } ?: false
            Key.Back, Key.Escape                            -> onBack?.invoke()?.let { true } ?: false
            else -> false
        }
    }
    .focusRequester(focusRequester)
    .onFocusChanged { if (it.isFocused) onFocused() }
    .focusable()
