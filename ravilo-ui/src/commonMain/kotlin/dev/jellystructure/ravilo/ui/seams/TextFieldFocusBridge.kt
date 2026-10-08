package dev.jellystructure.ravilo.ui.seams

import androidx.compose.ui.Modifier

/**
 * Bug fix — ravilo-web's index.html toggles fullscreen on any 'f'/'F' keydown, with no DOM
 * `<input>` to check `document.activeElement` against (CanvasBasedWindow routes all text entry
 * through Compose's own focus system on a single `<canvas>`). This modifier reports focus/blur of
 * a text field out to `window.raviloTextFieldFocused` so the JS handler can skip the toggle while
 * typing. No-op on Android — there's no such keydown handler to guard against there.
 *
 * R281 used the same report to focus a hidden bridge `<input>` so a touchscreen raised its keyboard.
 * R376 (FR-R376-1) — under ComposeViewport a focused text field has a real DOM input of its own, so
 * that bridge is gone; the F-key guard is what is left.
 */
expect fun Modifier.reportTextFieldFocus(): Modifier
