# Phase R20 — Performance & correctness overhaul (FR-RV20)

**Status:** ✓ Done (2026-06-19)

## Problem
First real hardware test on the stue TV (Sony BRAVIA XR) exposed three classes of bug: a D-pad freeze
caused by accumulated focus state, store coroutine leaks, and allocation hot-paths causing excessive
recomposition.

## What was built

### Focus latch fix (root cause of freeze)
`dpadFocusable` gained an `onBlurred` callback. Every focusable component — `Tile`, `ChannelCard`,
`RaviloButton`, `EpisodeCard`, `SeasonPicker`, browse-grid chips — resets its internal `focused`
state on blur. Previously, `focused` was only ever written `true`, so navigating through tiles left
each one permanently highlighted; every subsequent D-pad event triggered a recompose cascade across
the entire visible screen.

### Store coroutine hygiene
`HomeStore`, `MovieDetailStore`, `SeriesDetailStore`, `BrowseStore`, and `ChannelStore` track their
`loadJob` and cancel it before each new `load()` call. `PlayerStore` heartbeat loop changed from
`delay`-based to `while (isActive)` for instant cancellation on scope exit.

### Allocation hot-paths
`Brush.verticalGradient`, `Color.copy(alpha=…)`, and `RoundedCornerShape` calls in tile/card
composables moved to `remember {}` blocks or `companion object` constants so they are not
re-allocated on every recomposition.

### CastCircle D-pad focus
`CastCircle` components on detail screens gained full `dpadFocusable` integration (left/right
navigation within the cast row, up/down to exit the cast row).
