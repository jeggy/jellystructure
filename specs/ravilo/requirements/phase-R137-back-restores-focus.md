# R137 — Back restores focus + scroll to the originating item

> Builds on **R40** (retained per-screen stores) and the native focus-traversal + `focusRestorer` migration.

## Problem

Open an item (a poster in a Home row, a Browse grid tile, a channel tile) → detail → press **Back**, and
the previous screen returns but focus lands on the **top-most item** and the list is **scrolled back to the
top** — you lose your place instead of returning to the tile you came from.

## Current

- Navigation **stores are retained** across the back stack (`RaviloApp.kt` `storeRegistry`, R40) — so data
  doesn't reload — **but the scroll state is not**: screens create `rememberLazyListState()` /
  `rememberLazyGridState()` (`HomeScreen` ~113, `BrowseScreen` ~142, `ChannelScreen` ~109, detail rails),
  which is recreated on Back → scroll resets to 0 and focus defaults to the first item.
- `Modifier.focusRestorer()` is present on the rails/grids (`ContentRow`, Browse grid), but with the list
  state recreated there's nothing to restore *to*.
- Only **Discover** persists position: `DiscoverStore.lastSelectedRowIndex` (~23) + a restore
  `LaunchedEffect` (`DiscoverScreen` ~134) `scrollToItem`. It restores the row, not the exact item.

## Requirements

1. Pressing **Back** into a scrollable screen restores **both** the prior **scroll position** and **D-pad
   focus to the exact item** the user navigated from — Home rows, Browse/My-List grid, Channel page, and
   the detail rails (cast / related / episodes).
2. No reload flash (R40 already handles data); restoration is within the navigation session (not persisted
   across app restarts).

## Approach

Hoist the scroll state into the **retained store** instead of `remember`: hold a `LazyListState` /
`LazyGridState` on each screen's store (`HomeStore`/`BrowseStore`/`ChannelStore`/detail store, created via
the R40 `storeRegistry`) and pass it to the list — so the same state object (scroll offset + first-visible
index) survives navigate→back. Combined with `focusRestorer()` on the **outer** vertical list **and** each
row/grid, Back re-enters with the preserved scroll and nested `focusRestorer` returns focus to the
last-focused child = the originating item. Generalise Discover's pattern into this shared mechanism.
(Fallback if hoisting a `LazyListState` is awkward: save `firstVisibleItemIndex` + offset + the focused
item key in the store and restore via `scrollToItem` + `FocusRequester.requestFocus` on re-entry.)

## Files

- `ravilo-ui/.../screens/HomeStore.kt`, `BrowseScreen.kt`/its store, `ChannelScreen.kt`/a `ChannelStore`,
  detail stores — hold the list/grid state.
- The screens — use the store-held state; ensure `focusRestorer()` on the outer list + rows/grids.

## Implemented (R137, partial)

Shipped the **scroll-restoration** half: the `LazyListState`/`LazyGridState` is hoisted into the retained
store (`HomeStore`/`ChannelStore`/`BrowseStore`), so navigate→back returns to the scrolled position. On a
scrolled return, focus the **app bar** (a fixed overlay that doesn't disturb the scroll, unlike the
off-screen hero which would yank back to the top); DOWN re-enters the content. **GAP — exact-tile focus**:
the focus system's reliable anchors are the hero + app bar, not individual tiles, so focus lands adjacent,
not on the originating tile; restoring the exact tile (per-row FRs + saved index, surviving the screen's
disposal on nav) is a follow-up that needs on-device tuning. Detail rails not yet covered.

## Out of scope

Cross-session (app-restart) restoration; deep-link entry focus.
