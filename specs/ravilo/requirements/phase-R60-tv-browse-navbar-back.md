# R60 — Ravilo TV: BrowseScreen — restore AppBar and fix back navigation (FR-BN1)

**Status:** Planned

## Goal

Opening Movies, Series, or My List from the home screen nav causes two problems:

1. The top navbar (AppBar) disappears — only a plain text title is shown.
2. Pressing Back on the remote closes the app instead of returning to the home screen.

Both issues stem from the same root: `BrowseScreen` has no `AppBar` and claims no focus on entry.

## Root cause

`AppBar` is a per-screen composable, not a persistent overlay. `HomeScreen` and `DiscoverScreen` each include `AppBar` and request focus on the nav bar with `LaunchedEffect(Unit) { navBarFR.requestFocus() }`. `BrowseScreen` does neither:
- No `AppBar` call anywhere in `BrowseScreen.kt`.
- No focus request on entry.

Without focus claimed, the Android system receives the Back key directly and calls `finish()` — bypassing Compose's `onKeyEvent` chain entirely.

**Navigation model**: `Dest.Browse` is pushed onto the nav stack in `RaviloApp.kt` (`stack + Dest.Browse(...)`). `stack.size > 1` is true, so the `onKeyEvent` pop handler *would* work — but it never fires because no Compose composable holds focus.

## Target behaviour

### AppBar
`BrowseScreen` renders the same `AppBar` component used by `HomeScreen` and `DiscoverScreen`:
- Same nav items (Home · Movies · Series · My List)
- `activeNav` index reflects the current `BrowseKind` (Movies=1, Series=2, My List=3)
- Nav item click replaces the current `Dest.Browse` in-place (not push) to avoid stacking multiple browse destinations: `stack = stack.dropLast(1) + Dest.Browse(newKind)`

### Focus on entry
`LaunchedEffect(Unit) { runCatching { navBarFR.requestFocus() } }` — same pattern as `HomeScreen` and `DiscoverScreen`. Focus lands in the AppBar nav immediately, even before the grid data loads. This ensures Back fires through Compose and pops the stack correctly.

### Back button
With focus claimed, `RaviloApp.kt`'s root `onKeyEvent` pops `Dest.Browse` off the stack and returns to the home screen. No separate back-button handler needed in `BrowseScreen` itself.

### Layout
The AppBar is a fixed-height overlay (same as other screens). Add `PaddingValues(top = 84.dp)` to the browse grid `LazyVerticalGrid` so tiles are not covered by the bar.

## Files

| File | Change |
|------|--------|
| `ravilo-ui/.../screens/BrowseScreen.kt` | Add `AppBar` composable with `navBarFR` + `LaunchedEffect` focus request; add grid top padding; add `activeNav` derivation from `BrowseKind` |
| `ravilo-ui/.../RaviloApp.kt` | Pass nav callbacks to `BrowseScreen`; replace-in-place on nav tab switch (`dropLast(1) + Dest.Browse(newKind)`) |

## Non-goals

- No change to the grid layout or card style.
- No change to the `BrowseStore` data loading.
- No scroll-to-top-on-back behaviour for the browse grid (add later if desired).
