# R139 — Stable back-focus: return to the exact originating tile

> Completes **R137** (which restored scroll only). Home · Channel · Browse.

## Problem

Opening an entry (a media tile, a channel) and pressing **Back** landed focus on the top-left, not the
tile you came from — the biggest "doesn't feel enterprise" gap. R137 restored the *scroll* position but
not focus, and couldn't: `RaviloApp` **disposes the previous screen on navigate** (`AnimatedContent(
targetState = stack.last())`), so Compose's `focusRestorer()` (composition-scoped) has nothing to restore.

## Mechanism

The **retained store** (R40) is the only thing that survives disposal — the same place R137 keeps scroll.
So save the focused tile's **identity** there on the way out and re-focus it on Back:

- **Stores** gain `focusRowKey` + `focusItemKey` (Home/Channel) / `focusItemKey` (Browse grid).
- **Save on select**: the navigate callbacks set the keys (row tiles, channel rail); opening from the
  **hero clears** them (so Back returns to the hero, not a stale tile).
- **Restore** (self-contained in the row / grid):
  - `StaticContentRow` gains `restoreItemKey: Any?` and its `itemContent` lambda gains a trailing
    `FocusRequester?`. The screen passes `restoreItemKey` **only to the row whose id == focusRowKey**; that
    row's `LaunchedEffect` (once per entry) finds the item index, `scrollToItem`s its `LazyRow` to it (the
    row's horizontal scroll isn't retained, so we reconstruct it), and the matching item's content gets the
    row's `restoreFR` → `requestFocus()`. `runCatching`; index −1 → graceful no-op.
  - Browse grid: the cell whose key == `focusItemKey` gets the `restoreFR`; a `LaunchedEffect` requests
    focus directly — the grid's scroll is already retained (R137), so the cell is in view (no re-scroll).
- **Entry focus**: each screen's initial-focus effect **skips** the default hero/app-bar focus on a
  Back-return (`focusItemKey != null`) so the row/grid restore is the sole focus request (no race). The
  originating row is composed because R137 preserved the outer scroll.

## Scope / notes

- Home (content rows + channel rail), Channel (rows), Browse (grid). Discover keeps its existing row-level
  restore (only the `StaticContentRow` signature ripple applies). Detail-screen rails (cast/related) are
  out of scope.
- Trade-off: a restored row's tile lands at the row's left inset (its horizontal offset isn't persisted) —
  reliably focused + visible, not pixel-exact. Persisting per-row horizontal scroll is a later polish.
- App-transparent (server URLs unaffected); **needs on-device verification** — focus can't be
  compile-checked.

## Files

- `components/ContentRow.kt` (`restoreItemKey` + itemContent FR + scroll-and-focus).
- `screens/HomeStore.kt`, `ChannelScreen.kt` (ChannelStore), `BrowseScreen.kt` (BrowseStore) — focus keys.
- `screens/HomeScreen.kt`, `ChannelScreen.kt`, `BrowseScreen.kt`, `DiscoverScreen.kt` — save on select,
  pass `restoreItemKey`, gate the entry-focus effect.
