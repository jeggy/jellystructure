package dev.jellystructure.ravilo.ui.seams

import androidx.compose.ui.Modifier

/**
 * Bug fix — ravilo-web's index.html toggles fullscreen on any 'f'/'F' keydown, with no DOM
 * `<input>` to check `document.activeElement` against (CanvasBasedWindow routes all text entry
 * through Compose's own focus system on a single `<canvas>`). This modifier reports focus/blur of
 * a text field out to `window.raviloTextFieldFocused` so the JS handler can skip the toggle while
 * typing. No-op on Android — there's no such keydown handler to guard against there.
 */
expect fun Modifier.reportTextFieldFocus(): Modifier
