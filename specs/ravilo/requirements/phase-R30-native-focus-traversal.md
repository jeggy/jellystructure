# Phase R30 — Native focus traversal (FR-RV30)

## Problem
The R09 focus engine drove focus **by hand**: one `FocusRequester` per item plus a manual
`requestFocus()` on every D-pad press (`FocusGrid` / `FocusRow` in `focus/FocusEngine.kt`). Inside
lazy lists this breaks — `LazyRow` / `LazyColumn` / `LazyVerticalGrid` don't compose off-screen items,
so their `FocusRequester` isn't attached and `requestFocus()` throws. The failure was swallowed by
`runCatching`, leaving **focus stranded** (no focused node → no key events → stuck). Holding a
direction also backlogged a per-press `scrollToItem` / `animateScrollBy` against the key auto-repeat,
producing visible **lag and stutter**. Reported symptom: "holding left/right/up/down lags and
sometimes gets stuck."

## Current state (as-is, pre-R30)
- `focus/FocusEngine.kt` (`FocusGrid`, `FocusRow`, `globalFocusMemory`) plus `dpadFocusable`, which
  consumed every direction key and called `requestFocus()` on pre-allocated per-item requesters.
- `StaticContentRow` ran a manual scroll-to-focused `LaunchedEffect`; grids snapped via
  `scrollToItem(focusedIndex)` on every move.

## Requirements
1. **Move focus with the framework, not by hand.** Inside lazy lists/grids, items must **not** consume
   directional keys — `onKeyEvent` returns `false` so Compose's focus search moves focus, composes the
   off-screen item in the search direction, and brings it into view. No per-item `FocusRequester`.
2. **`Modifier.focusRestorer()`** on each `LazyRow` / grid so re-entering a row restores its
   last-focused child (replacing `globalFocusMemory`).
3. **`FocusRequester` / `onKeyEvent` are reserved** for: (a) a screen's entry point, (b) non-spatial
   bridges (e.g. the Home app-bar overlay ↔ content), and (c) genuine **content actions** (hero
   carousel paging; the Search keyboard↔grid edge handoff). Never one-per-item across a lazy list.
4. **Delete `focus/FocusEngine.kt`.** `StaticContentRow` is always native (no `focusedIndex`, no manual
   scroll). The on-screen keyboard's previously-dead `onDone` is wired (bottom-row Down enters results).
5. Screens migrated: Home, Browse (chips + grid), Movie & Series detail, Channel, Search; `SeasonPicker`
   rewritten (center-to-select preserved).

## Left on manual nav (intentional — not the bug, and not lazy)
- **Player** episode rail keeps its *virtual-focus* model (cards aren't focusable; a single
  `focusedEpIdx` drives one scroll and the player consumes keys centrally) — unchanged.
- The **on-screen keyboard** internals and the non-lazy **profile / settings / pairing** screens —
  every item is always composed, so they never strand focus; manual nav stays.

## Invariants (unchanged from R09 — now better met)
- One shared, multiplatform focus model; platforms only translate input. Still **no** Android-only
  `androidx.tv` artifact — native traversal is plain `compose.foundation` + `compose.ui.focus`, so it
  also runs on the WASM canvas target.
- Exactly one visible focus target; predictable left↔right / up↕down order; the app never strands focus.

## Notes
- API placement (CMP 1.8.1): `focusRestorer` is in `androidx.compose.ui.focus`; `focusGroup` is in
  `androidx.compose.foundation`.
- Supersedes the manual-engine *mechanism* in
  [R09 §"Focus engine"](phase-R09-design-system-focus-engine.md); R09's *goals* (predictable order,
  remembered focus, scroll-into-view) still hold.

## Out of scope
- The deferred `:ravilo-player` engine fork (R14 / STATUS open thread) — unrelated to focus.
