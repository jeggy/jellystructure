# R57 — Home screen: fix row skip on D-pad down + peek next row (FR-RSP1)

**Status:** ✅ Done

## Row skip fix

`HeroCarousel`'s `onFocusChanged { if hasFocus → animateScrollToItem(0) }` launched an animated scroll using `MutatorMutex`. When the user quickly pressed DOWN after returning to the hero, the focus-driven `bringIntoView` (higher `PreventUserInput` priority) cancelled the animation mid-flight, leaving the `LazyColumn` at a partial offset that placed one row just outside the composition window — which focus traversal silently skipped.

**Fix**: Changed to `scrollToItem(0)` (instant single-frame snap, no `MutatorMutex` contention).

## Peek next row

When a content row gains focus, `EdgeBringIntoViewSpec` scrolled only enough to reveal the focused row's bottom edge — no preview of the row below.

**Fix**: Added `rememberEdgeBringIntoViewSpec(peekDp: Dp)` to `BringIntoView.kt`. Home and Discover screens use `peekDp = 80.dp` — scrolls 80dp further past the bottom clip point, revealing the title of the next row.

## Files changed

- `ravilo-ui/.../focus/BringIntoView.kt` — added `rememberEdgeBringIntoViewSpec`
- `ravilo-ui/.../screens/HomeScreen.kt` — `animateScrollToItem(0)` → `scrollToItem(0)`; use peek spec
- `ravilo-ui/.../screens/DiscoverScreen.kt` — same two changes
