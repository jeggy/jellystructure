# Phase R281 — the on-screen keyboard needs a real input to attach to

> Reported: *"Ravilo web doesnt show the keyboard upon input field focus on mobile."*
>
> Confirmed live against a production build under Chromium's Pixel 5 device emulation before writing
> a line of fix: `document.activeElement` is `#ComposeTarget` — the canvas — on page load, after
> tapping a text field, and after tapping a *second* text field. It never changes. A mobile browser
> raises its software keyboard for exactly one thing: a real, focusable, editable DOM element gaining
> focus. There has never been one anywhere on this page.

## Status

`✓ Built` — design-authored and built 2026-09-20 from a live report. Not dev-reviewed. Not verified on
a real device (see below) — verified instead by driving the actual production wasmJs build under
Playwright, which is as close as this bug class gets to device verification without one.

### Root cause

`ravilo-web/src/wasmJsMain/kotlin/dev/jellystructure/ravilo/web/Main.kt` uses `CanvasBasedWindow`,
Compose Multiplatform's deprecated web entry point, kept deliberately over the newer `ComposeViewport`
because migrating would disrupt the hand-authored `#ComposeTarget` canvas layering, the `<video>`
backdrop compositing and the manual key-dispatch this app already has (see that file's own TODO, and
`ravilo-ui/.../seams/TextFieldFocusBridge.kt`'s doc comment, both written well before this phase).
`CanvasBasedWindow` routes all text entry through Compose's own focus system on a single canvas, with
no DOM text field of its own. Desktop typing was never affected — physical keyboard events land on
the canvas either way, proven by `page.keyboard.type()` inserting text correctly with no DOM `<input>`
anywhere on the page. A touchscreen has nothing that works that way: its on-screen keyboard is an OS
overlay gated purely on `document.activeElement` being real and editable, and Compose's own internal
focus state (the caret visibly moving between fields) is invisible to the browser.

Confirmed empirically, in order, against a local static build of the production wasmJs distribution
under Playwright + Chromium's Pixel 5 emulation:
- `document.querySelectorAll('input, textarea')` is empty, before and after tapping a text field —
  Compose Multiplatform's newer `ComposeViewport`-based accessibility DOM (real per JetBrains' own
  1.9.0 release notes, and per string constants — `textarea`, `TextInputService`, `beforeinput`,
  `compositionstart` — genuinely present in the `org.jetbrains.compose.ui:ui-wasm-js:1.9.3` klib)
  simply never runs under `CanvasBasedWindow`.
- A bare `new KeyboardEvent('keydown', {key: 'æ', bubbles: true})` dispatched directly at
  `#ComposeTarget` — no Playwright keyboard API involved — inserts `æ` into the focused field exactly
  like a physical key press. This is the same mechanism `boot.js`'s existing gamepad-to-D-pad bridge
  already uses for arrow keys, now proven to carry arbitrary Unicode text too.
- Mouse/touch clicks on the canvas do move Compose's own focus between fields correctly (a typed
  marker character lands in the newly focused field) — the bug is specifically that nothing on the
  page reflects that change to the DOM, not that Compose's focus system is broken.

### The fix

A hidden native `<input id="ravilo-kb-bridge">` in `index.html` — real, focusable (`tabindex="-1"`
only removes it from Tab order, not from programmatic `.focus()`), 1px, `opacity:0`,
`pointer-events:none` so it is never itself a tap target. `boot.js` only wires it up on a
coarse-pointer (touch) device (`matchMedia('(pointer: coarse)')`) — desktop/TV keep their existing,
unaffected behaviour.

`window.raviloMobileKeyboardBridge(focused)` focuses or blurs that input. It's called from the exact
edge that already existed for a different bug: `TextFieldFocusBridge.kt`'s wasmJs actual already
reports every Compose text-field focus/blur out to `window.raviloTextFieldFocused`, originally so
`boot.js` could stop the 'f'/'F' fullscreen-toggle hotkey from firing while a field is being typed
into. `jsMarkTextFieldFocus` now also calls the new hook on the same edge — no new Kotlin-side
listener, no new composable wiring, just one more statement (chained via the JS comma operator, since
Kotlin/Wasm's `js()` only accepts a single expression) in a function that already ran on every focus
change.

The bridge input's own `beforeinput`/`keydown` handlers translate what the on-screen keyboard
produces into the same synthetic `KeyboardEvent`s on `#ComposeTarget` the gamepad bridge already uses
(`raviloFireCanvasKey`, hoisted out of the gamepad IIFE so both share one implementation):
`insertText`-family input types with `e.data` fire one `KeyboardEvent` per Unicode codepoint (`Array.
from(e.data)`, not per UTF-16 code unit); `deleteContentBackward`/`deleteContentForward` fire
Backspace/Delete; a `keydown` on the bridge itself catches `Enter` (mobile "Go"/"Done" keys don't
reliably surface through `beforeinput`). Every branch calls `preventDefault()`, so the hidden input's
own `.value` never accumulates anything — it is a pure relay, never a second source of truth for the
field's text, which Compose's own canvas-side state still owns exclusively.

Composition input (CJK IME) is not bridged. This household's Ravilo languages are en/da/fo — Latin
script, where a typed accented letter (`æ`, `ø`, `å`, `ý`, `ð`, …) arrives as a plain single-character
`insertText`, not a composition sequence — so the gap is real but out of scope here.

### Verified, and not verified

**Verified**, against the actual production `wasmJsBrowserDistribution` output (not a dev build, not
a mock), under Playwright + Chromium with `devices['Pixel 5']` emulation:
- Before the fix: `document.activeElement` is `CANVAS` on load, after a tap, and after a focus
  hand-off between two different text fields (Login screen's Username → Password via a synthetic
  `ArrowDown`, the same D-pad-navigation path `LoginScreen.kt` already wires for TV remotes).
- After the fix: `document.activeElement` is the real `INPUT#ravilo-kb-bridge` in all three of those
  moments — proving the bridge fires on the page's initial autofocus *and* survives a focus hand-off
  between fields, not just a one-time setup.
- Typing through the bridge (simulated `beforeinput` events, exactly what a real on-screen keyboard
  sends) correctly inserted `"jeggy"` into Username and `"hunter2"` into Password, with a trailing
  backspace correctly removing the last character (6 masked dots, not 7) — screenshotted and read back
  by eye against the actual rendered canvas. The bridge input's own `.value` stayed empty throughout,
  confirming `preventDefault()` is doing its job and there's no second, drifting copy of the text.
  Danish/Faroese-alphabet characters (`æ`) round-tripped correctly.
- The regression is real, not a tautology: reverted to the pre-fix source, rebuilt the actual
  production bundle, and the new Playwright test (below) fails with
  `Expected: "INPUT", Received: "CANVAS"` — then re-applied the fix, rebuilt again, and it passes.

**Not verified on a real phone.** Headless Chromium (real or emulated) never renders an OS-level
software keyboard — no automated framework can observe *"did the keyboard actually appear"* directly,
on this project or any other; `document.activeElement` being a real, focusable, editable element is
the documented, standard, and only precondition mobile browsers use to decide whether to raise one,
and it is what changed from consistently false to consistently true. A real-device pass (iPhone
Safari, Android Chrome) is still the thing that would close this out completely, and is exactly the
kind of test this project's own history says not to skip before calling a fix done.

### Test

`tests/e2e/ravilo-web-mobile-keyboard` coverage lives in `tests/e2e/ravilo-web.spec.ts` (a new
`describe` block in the existing file, not a new one — same target, same boot-wait pattern). Scoped
deliberately to the DOM-focus precondition only, not to whether a typed character reaches Compose:
that file's own existing test already found headless Chromium's rendering behaviour on a real CI
runner too unreliable to assert on the canvas by screenshot (an hour-long hung job, no timeout, no
error — see its comment), and confirming a character arrived would need exactly that. Moves focus via
a synthetic `ArrowDown` on the canvas (the existing TV D-pad-navigation path) rather than a tap or
click at a hardcoded pixel position, so the test doesn't couple to the login screen's exact layout or
font metrics.
