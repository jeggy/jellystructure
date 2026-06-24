# R59 — Ravilo TV: Backspace key as Back (FR-KB1)

**Status:** Planned

## Goal

The TV remote back button (`Key.Back`) and Escape key (`Key.Escape`) are already handled. Physical keyboard Backspace (`Key.Backspace`) should have the exact same effect — useful for the WASM browser client and for keyboard-attached TV emulators.

## Current state

Back is handled in two places:

1. **`RaviloApp.kt`** — root Box `onKeyEvent`, pops the navigation stack:
   ```kotlin
   ev.key == Key.Back || ev.key == Key.Escape
   ```

2. **`BackToTop.kt`** — `backToTopOnBack` modifier, scrolls to top before popping:
   ```kotlin
   if (ev.key != Key.Back && ev.key != Key.Escape) return@onKeyEvent false
   ```

Neither includes `Key.Backspace`.

## Fix

In both places, add `|| ev.key == Key.Backspace` to the key checks.

**Text-input safety**: Compose's `onKeyEvent` is called after focused child composables consume events. When a `TextField` / `BasicTextField` has focus, it consumes Backspace for text deletion before the event reaches the `RaviloApp` root handler — no accidental navigation from text inputs.

**Browser (WASM) behaviour**: When the Compose canvas has focus, `Key.Backspace` is intercepted by the Compose event system before the browser's history-back behaviour, so no browser navigation conflict.

## Files

| File | Line(s) | Change |
|------|---------|--------|
| `ravilo-ui/.../RaviloApp.kt` | root `onKeyEvent` | Add `|| ev.key == Key.Backspace` |
| `ravilo-ui/.../focus/BackToTop.kt` | early-return guard | Add `&& ev.key != Key.Backspace` |

## Non-goals

- No change to the back navigation stack logic.
- No change to Android activity `onBackPressed` / `BackHandler`.
