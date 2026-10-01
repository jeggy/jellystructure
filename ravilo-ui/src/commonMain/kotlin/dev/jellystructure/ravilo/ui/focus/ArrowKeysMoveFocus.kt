package dev.jellystructure.ravilo.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/**
 * R350 (FR-R350-11) — the arrow keys move focus on a computer the way the D-pad does on a TV.
 *
 * On Android the platform turns an arrow nobody handled into a focus move (Compose's 2-D focus search); the app's
 * whole D-pad design leans on that — `dpadFocusable` leaves lazy items' directions to it on purpose. Compose
 * Multiplatform's desktop owner maps only Tab, Shift+Tab and Back to focus moves, so on the Mac and Linux app every
 * arrow that relied on the native search did nothing: Down from a season pill, Up/Right/Down from an episode's
 * *Mark watched*. Mounted at the app root, this runs only when no focused element consumed the arrow, and asks the
 * same focus search the TV gets.
 *
 * [enabled] is the desktop. Not Android (the platform already does it) and not the web for now (unverified there).
 */
@Composable
fun Modifier.arrowKeysMoveFocus(enabled: Boolean): Modifier {
    if (!enabled) return this
    val focusManager = LocalFocusManager.current
    return this.onKeyEvent { ev ->
        if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
        val direction = arrowFocusDirection(ev.key) ?: return@onKeyEvent false
        focusManager.moveFocus(direction)
    }
}

/** The focus direction an arrow key asks for, or null for any other key. */
internal fun arrowFocusDirection(key: Key): FocusDirection? = when (key) {
    Key.DirectionUp -> FocusDirection.Up
    Key.DirectionDown -> FocusDirection.Down
    Key.DirectionLeft -> FocusDirection.Left
    Key.DirectionRight -> FocusDirection.Right
    else -> null
}
