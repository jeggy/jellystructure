# R64 — Native IME search (replace custom on-screen keyboard)

**Status:** Planned
**Depends on:** R49 (search screen exists), R09 (focus/dpad engine)

## Problem

The current search screen renders a custom 38-key on-screen keyboard (`OnScreenKeyboard.kt`) built
entirely in Compose. It has no connection to Android's input pipeline — the "text field" is a plain
`Box` + `Text` composable with a block cursor drawn in the string. Consequences:

- **No voice input** — the Android TV remote's microphone button does nothing.
- **No physical keyboard support** — plug in a USB/BT keyboard and typing is silently ignored.
- **Uppercase only, no punctuation** — "star trek: discovery" cannot be searched by colon or apostrophe.
- **Slow to type** — every character requires D-pad navigation across a 10-column grid.
- **Dead on WASM** — the browser build gets the same custom keyboard even though the user already has
  a physical keyboard in front of them.
- **Maintenance burden** — ~120 lines of custom focus management that duplicates what the OS provides
  for free.

## Goal

Replace the custom keyboard with the Android OS soft IME on Android TV, and with a plain `BasicTextField`
(no keyboard, cursor only) on WASM. The `SearchStore.onQuery(String)` contract is unchanged — the store
already accepts full strings.

## Functional requirements

### FR-NK1 — Android: native IME opens automatically on search

When the user opens the search screen (via the search icon in the AppBar), the native Android soft
keyboard appears immediately without any D-pad navigation. The first key tap / voice input / physical
keyboard stroke updates the search query.

### FR-NK2 — Android: IME action triggers search

The keyboard shows a "Search" IME action (magnifier icon on the Enter key). Pressing it dismisses the
IME and moves focus to the results grid if results are present, or keeps the field focused if the query
returns nothing.

### FR-NK3 — Android: D-pad Down dismisses IME and enters results

If the IME is visible and the user presses D-pad Down (on the remote), the IME is dismissed and focus
moves to the first result tile. This mirrors the current `onDone` callback from `OnScreenKeyboard`.

### FR-NK4 — Android: Back from results returns to the search field and re-shows IME

Pressing Back while focus is in the result grid returns focus to the text field. If the IME was
previously dismissed by D-pad Down, it re-appears. A second Back (from the field with empty query, or
from the field after the IME is shown) pops the search screen.

### FR-NK5 — Android: voice input works

The native IME's microphone button (present on Gboard and the default Android TV IME) triggers voice
recognition and populates the text field. No explicit code required — this is a free IME capability.

### FR-NK6 — WASM: plain text field, no keyboard overlay

On the WASM/browser build, a standard `BasicTextField` is shown. The user already has a physical
keyboard. No on-screen keyboard is rendered. `LocalSoftwareKeyboardController.show()` is called on
focus but is a no-op on WASM — this is correct behaviour.

### FR-NK7 — OnScreenKeyboard deleted

`ravilo-ui/src/commonMain/kotlin/.../components/OnScreenKeyboard.kt` is deleted entirely. No other
screen uses it.

### FR-NK8 — `windowSoftInputMode` updated

The Activity's `windowSoftInputMode` is changed from `adjustPan` to `adjustResize` so the result grid
shrinks above the IME rather than being obscured by it.

## Implementation notes

### `SearchScreen.kt` — the only file that needs UI changes

Current state (all in `commonMain`):
- `var query by remember { mutableStateOf("") }` — **keep as-is**; `onValueChange` will write to it
- Fake bar: `Box { Text("$query█") }` — **replace** with `BasicTextField` (see below)
- `OnScreenKeyboard(…)` block — **delete**; the `inGrid` bool that guarded it **delete** too
- `var inGrid by remember { mutableStateOf(false) }` — **delete**

Replacement text field pattern (matches `ServerSetupScreen.kt` which already works on Android TV):

```kotlin
val keyboardController = LocalSoftwareKeyboardController.current
val textFieldFR = remember { FocusRequester() }

// auto-open IME on entry
LaunchedEffect(Unit) {
    runCatching { textFieldFR.requestFocus() }
    keyboardController?.show()
}

BasicTextField(
    value = query,
    onValueChange = { q -> query = q; store.onQuery(q) },
    modifier = Modifier
        .focusRequester(textFieldFR)
        .onFocusChanged { if (it.isFocused) keyboardController?.show() },
    keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Search,
    ),
    keyboardActions = KeyboardActions(
        onSearch = {
            keyboardController?.hide()
            // move focus to grid if results present (firstResultFR.requestFocus())
        },
    ),
    // custom decoration — keep the existing visual search bar style (no borders etc.)
    decorationBox = { inner -> SearchBarDecoration(query, inner) },
)
```

D-pad Down off the text field → `keyboardController?.hide()` → `firstResultFR.requestFocus()`.

Back from results grid → `textFieldFR.requestFocus()` → IME re-shows via `onFocusChanged`.

### Back-press two-stage logic

Replace the `inGrid` boolean with a direct check of whether focus is in the grid. Use
`backToTopOnBack` with `atTop = { !gridHasFocus }` so the first Back always returns to the text field;
the second Back (from the field) propagates to `RaviloApp` and pops the screen.

### `windowSoftInputMode`

Change in `ravilo-android/src/main/AndroidManifest.xml`:
```xml
android:windowSoftInputMode="adjustResize"
```

`adjustResize` causes the Compose layout to reflow above the IME. With `adjustPan` the IME slides
over the content, potentially hiding the results grid. `adjustResize` is the correct mode for any
screen that needs to show content while the IME is open.

Note: `configChanges` already includes `keyboard|keyboardHidden` so the activity survives IME
show/hide without recreating.

### `OnScreenKeyboard.kt` deletion

The file `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/components/OnScreenKeyboard.kt`
can be deleted. It is only referenced from `SearchScreen.kt`. After the replacement, no other file
imports it.

### SearchStore — no changes needed

`SearchStore.onQuery(String)` already accepts the full updated string and debounces it 250 ms before
firing `apiClient.search(query)`. The `onValueChange` lambda of `BasicTextField` calls `onQuery` with
the exact same semantics. The store's `state: StateFlow<SearchState>` and result handling are
unchanged.

### WASM

No platform branch is required. `BasicTextField` renders in the browser as a native `<input>` element
(via Compose WASM's DOM interop). `LocalSoftwareKeyboardController.show()` is a no-op stub on WASM —
calling it is safe. The user's physical keyboard drives input automatically.

## What is NOT in scope

- Search history / suggestions dropdown — separate feature
- Voice search trigger button in the AppBar (beyond what the IME already provides via the microphone
  key) — separate feature
- Localized keyboard layout selection — OS-provided
- Search-while-typing for partial matches beyond the current 250 ms debounce — no change

## Files affected

| File | Change |
|------|--------|
| `ravilo-ui/src/commonMain/…/screens/SearchScreen.kt` | Replace fake bar + custom keyboard with `BasicTextField`; delete `inGrid`; update back-press logic |
| `ravilo-ui/src/commonMain/…/components/OnScreenKeyboard.kt` | **Delete** |
| `ravilo-android/src/main/AndroidManifest.xml` | `windowSoftInputMode` → `adjustResize` |

No changes to: `SearchStore`, `RaviloApp.kt`, `AppBar.kt`, `TvApiClient`, shared DTOs, or backend.
