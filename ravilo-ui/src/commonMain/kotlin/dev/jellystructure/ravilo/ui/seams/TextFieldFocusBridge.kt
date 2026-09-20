package dev.jellystructure.ravilo.ui.seams

import androidx.compose.ui.Modifier

/**
 * Bug fix — ravilo-web's index.html toggles fullscreen on any 'f'/'F' keydown, with no DOM
 * `<input>` to check `document.activeElement` against (CanvasBasedWindow routes all text entry
 * through Compose's own focus system on a single `<canvas>`). This modifier reports focus/blur of
 * a text field out to `window.raviloTextFieldFocused` so the JS handler can skip the toggle while
 * typing. No-op on Android — there's no such keydown handler to guard against there.
 *
 * R281 — the same absence of a DOM `<input>` is why a touchscreen never raises a software
 * keyboard for a Compose text field: mobile browsers only do that for a real focusable, editable
 * DOM element, and `document.activeElement` here is always `#ComposeTarget`, never anything a
 * keyboard can attach to. This modifier's focus/blur report doubles as the trigger for boot.js's
 * hidden bridge `<input>` on touch devices (see index.html / boot.js) — no Kotlin-side change
 * needed beyond calling one more JS hook from the same edge this file already tracks.
 */
expect fun Modifier.reportTextFieldFocus(): Modifier
