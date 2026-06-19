# Phase R20 — Performance & correctness overhaul (FR-RV20)

**Status:** ✓ Done · _eliminates the D-pad freeze, stutter under rapid navigation, and Compose
correctness violations found during hardware testing._

> Directly follows the hardware-test session documented in [`../STATUS.md`](../STATUS.md) (2026-06-19).
> Touches **`:ravilo-ui` only** — no backend changes, no new API endpoints, no new screens.

## Problem

First real-hardware testing on the stue Android TV exposed three classes of defect working in
concert:

1. **D-pad freeze after 3–5 key presses.** The `focused: Boolean` state in every focusable
   component (`Tile`, `ChannelCard`, `RaviloButton`, `EpisodeCard`, `SeasonPicker` pill) was
   written `true` on focus gain but **never reset to `false`** on focus loss. Every tile the user
   navigated through accumulated `focused = true`, causing cascading recomposition of the entire
   screen on every subsequent D-pad event until the frame budget collapsed.

2. **Stutter / jank during any navigation.** Expensive allocation types — `Brush.verticalGradient`,
   `Color.copy(alpha=…)`, `RoundedCornerShape(Xdp)` — were created fresh on every recompose
   inside composable bodies, triggering Skia gradient shader rebuilds and GC pressure multiple
   times per second. In parallel, concurrent API requests were being launched without cancelling
   in-flight predecessors, causing screen state to flash to stale data when a race resolved
   out-of-order.

3. **Compose correctness violations** causing subtle state loss and animation fighting the user:
   `remember { }` inside a conditional block (illegal), `LaunchedEffect(isPlaying)` restarting
   a polling loop on every play/pause tap, and `animateScrollToItem` competing with D-pad
   movement to scroll the list in the opposite direction from the user's intent.

## Requirements

### R20-1 — Focus state latch fix
1. `dpadFocusable` (the shared `Modifier` in `FocusModifiers.kt`) gains an **`onBlurred`
   callback** invoked whenever the composable loses focus (`isFocused == false`). The existing
   `onFocused` fires only on focus gain; previously `onBlurred` was absent.
2. Every focusable component passes `onBlurred = { focused = false }` so the highlight is cleared
   the moment focus moves away. Affected: `Tile`, `ChannelCard`, `RaviloButton`, `EpisodeCard`,
   `SeasonPicker` pill, `BrowseGrid` chip.

### R20-2 — Store coroutine hygiene
3. Every store that loads remote data (`HomeStore`, `MovieDetailStore`, `SeriesDetailStore`,
   `BrowseStore`, `ChannelStore`) tracks a `loadJob: Job?` and calls `loadJob?.cancel()` at the
   top of every `load()` / `refresh()` invocation before launching a new coroutine. The new job
   is assigned back to `loadJob`. This ensures at most one in-flight request per store at any
   time; rapid back-navigation or fast screen re-entry never produces a race between two parallel
   API responses.
4. `PlayerStore.startHeartbeat` changes `while (true)` to `while (isActive)` so the heartbeat
   coroutine exits immediately when the enclosing scope is cancelled, without waiting for the
   next `delay` tick.

### R20-3 — Allocation hot-paths
5. All `Brush.verticalGradient(…)` calls inside composable bodies are wrapped in
   `remember(key) { }` with the relevant color tokens as keys. Affected files: `AppBar.kt`,
   `HeroCarousel.kt`, `MovieDetailScreen.kt`, `SeriesDetailScreen.kt`, `PlayerScreen.kt`.
6. All `Color.copy(alpha=…)` calls inside composable bodies are wrapped in `remember(key) { }`.
   Affected: `AppBar.kt`, `HeroCarousel.kt`, `RaviloButton.kt`, `ChannelCard.kt`, `CastCircle.kt`,
   `PlayerScreen.kt`.
7. All `RoundedCornerShape(Xdp)` declarations inside composable bodies are wrapped in
   `remember { }`. Affected: `RaviloButton.kt`, `Tile.kt`, `ChannelCard.kt`, `EpisodeCard.kt`,
   `SeasonPicker.kt`, `OnScreenKeyboard.kt`, `PlayerScreen.kt`.
8. The `meta` string built via `listOfNotNull(…).joinToString(…)` in `MovieDetailScreen` and
   `SeriesDetailScreen` is wrapped in `remember(year, runtime, genre, rating) { }` to avoid list
   allocation on every recompose.

### R20-4 — Lazy list key stability
9. `StaticContentRow` (in `ContentRow.kt`) gains an optional **`itemKey: ((T) -> Any)?`**
   parameter that is threaded to the `LazyRow items(count, key = …)` call. Callers pass the
   stable model ID of each item; items that shift position due to a refresh are moved rather than
   destroyed and recreated, preserving focus and scroll state.
10. All `LazyColumn`, `LazyRow`, and `LazyVerticalGrid` `items(…)` calls across all screens are
    given a `key = { i -> items[i].id }` lambda (or equivalent stable identifier):
    - `HomeScreen`: channel rail (`ch.id`), content rows outer (`row.id`), inner tile (`card.id`).
    - `ChannelScreen`: outer rows (`row.id`), inner tile (`card.id`).
    - `BrowseScreen`: grid tiles (`card.id`), genre chips (`chip ?: "all"`).
    - `SearchScreen`: result grid (`card.id`).
    - `SeriesDetailScreen`: episodes (`ep.id`), cast (`person.id`), related (`card.id`).
    - `MovieDetailScreen`: cast (`person.id`), related (`card.id`).
    - `SeasonPicker`: season pills (`season.index`).

### R20-5 — Scroll behaviour
11. Every `animateScrollToItem` call driven by a `LaunchedEffect` is replaced with
    `scrollToItem` (instant, no animation). Animated auto-scroll visibly fought the user's
    D-pad direction by driving the list back to a computed position while the user was navigating
    away from it. Affected: `ContentRow.kt` (`ContentRow` + `StaticContentRow`), `HomeScreen.kt`
    (`LaunchedEffect(focusSection)`), `BrowseScreen.kt` (`LaunchedEffect(focusedIdx)`).

### R20-6 — Compose correctness
12. `PlayerChrome` declared `val fr = remember { FocusRequester() }` **inside** the conditional
    `if (nearEnd && nextEpLabel != null) { … }`. This violates Compose rules (remember must not be
    called conditionally); when the condition flipped, the FocusRequester was a new instance and
    focus was silently lost. The inner `val fr` is removed; the already-hoisted `nextEpFR`
    parameter (passed from `PlayerScreen`) is used directly.
13. `PlayerScreen` `LaunchedEffect(isPlaying) { while (true) { … } }` restarted the 500 ms
    position-polling loop on every play/pause toggle, creating a burst of coroutine
    start/cancel overhead during the moments the user interacts most. Key changed to
    `LaunchedEffect(Unit)` — the loop body reads `player.isPlaying` directly so it does not need
    the key to capture the current value.

## Invariants
- **No new API endpoints or DTOs** — purely a client-side correctness and performance fix.
- **No behavior changes visible to users** — same screens, same navigation model. The only
  observable difference is smoothness: no freeze after rapid D-pad input, no focus highlight
  lingering on tiles that are no longer focused.
- `StaticContentRow`'s `itemKey` is optional (defaults to `null`) so callers that do not yet
  have a stable ID are not broken.

## Files changed

| File | Change |
|------|--------|
| `focus/FocusModifiers.kt` | Added `onBlurred` callback |
| `components/Tile.kt` | `onBlurred`; memoize shape |
| `components/ChannelCard.kt` | `onBlurred`; memoize shape + accent Color |
| `components/RaviloButton.kt` | `onBlurred`; memoize shape + ghost border Color |
| `components/EpisodeCard.kt` | `onBlurred`; memoize card + thumb shapes |
| `components/SeasonPicker.kt` | `onBlurred`; memoize pill shape; key lambda |
| `components/ContentRow.kt` | `scrollToItem`; optional `itemKey` param |
| `components/AppBar.kt` | Memoize bar gradient Brush + Color |
| `components/HeroCarousel.kt` | Memoize vignette Brush + dot Color |
| `components/CastCircle.kt` | Memoize role text Color |
| `components/OnScreenKeyboard.kt` | Memoize key + container shapes |
| `screens/HomeStore.kt` | `loadJob` cancellation |
| `screens/DetailStore.kt` | `loadJob` cancellation (Movie + Series) |
| `screens/BrowseScreen.kt` | `loadJob` cancellation; `onBlurred` chip; key lambdas; `scrollToItem` |
| `screens/ChannelScreen.kt` | `loadJob` cancellation; key lambdas |
| `screens/PlayerStore.kt` | `while (isActive)` in heartbeat |
| `screens/PlayerScreen.kt` | Fix `remember` in conditional; `LaunchedEffect(Unit)`; memoize Brush + Colors |
| `screens/HomeScreen.kt` | `scrollToItem`; key lambdas |
| `screens/SeriesDetailScreen.kt` | Memoize gradient Brush + meta string; key lambdas |
| `screens/MovieDetailScreen.kt` | Memoize gradient Brush + meta string; key lambdas |
| `screens/SearchScreen.kt` | Key lambda on result grid |
