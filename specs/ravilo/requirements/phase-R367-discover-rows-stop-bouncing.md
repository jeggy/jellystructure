# Phase R367 — Discover's rows stop bouncing

> Owner, 2026-10-04: *"When on Discover Request and not the first content row, then it just jumps up and down, looks
> like it's because there isn't enough space on the screen and it gets stuck in this loop."*

## Status

`Planned` — written 2026-10-04 (dev-authored), reproduced the same day on the living-room Sony BRAVIA (release
`1.49-11-g76f351b4`). Not dev-reviewed. Client only (`ravilo-ui`).

## What was seen

Discover → Request → Down into the second row (*Trending*) and leave the remote alone: the page keeps scrolling up
and down by itself. Twelve `uiautomator` reads of the row heading's top over ~10 s, no key pressed: **524, 508, 499,
486, 499, 508, 499, 469, 499, 521, 524, 499 px**. It never settles. The first row is still.

## Cause (code-traced, to be confirmed by the fix's test)

Two bring-into-view requests reach the Request list's `LazyColumn` for one focus move:
1. `StaticContentRow` asks for the **whole row** (heading + tiles) whenever the row gains focus
   (`ContentRow.kt:242-246`, `rowBIVR.bringIntoView()`);
2. the focused **tile** asks for itself (the default focus bring-into-view).

Both go through `rememberEdgeBringIntoViewSpec(peekDp = 150, topInsetDp = 64, centerLineFraction = 0.3)`
(`RequestScreen.kt:102`, R140): scroll so the target's top is **no higher than 64 dp** and **no lower than 30 % of the
list's height**. On Home the list is the whole screen; on Discover it starts below the page title and the tab strip,
so 30 % of it is only a little below 64 dp — a band of about 40 dp. The row's top and the tile's top are further
apart than that, so no scroll position satisfies both: fixing the tile breaks the row, fixing the row breaks the tile,
and Compose's bring-into-view keeps animating between the two. The first row is pinned at the list's start, so the
fight has nowhere to go there.

`UpcomingContent` (Coming Soon) and `TaxonomyScreen` (the walls, `peekDp = 120`) use the same spec in the same
region and must be checked for the same loop.

## Requirements

### FR-R367-1 — A focus move scrolls once and then stops
For any focus move in Discover's content (Request, Coming Soon, the walls), the list reaches one resting position and
does not move again until the next key. The framing rule must have a solution for every request it receives: the
band is computed from the list's own height with a **minimum width larger than the distance from a row's top to its
tiles' top**, or the row and its tile ask for the **same** target (one request per focus move: the row's), so they
cannot disagree.

### FR-R367-2 — The framing Discover aims for
The focused row's heading sits just under the tab strip (64 dp inset, as today), its tiles fully visible, and the next
row's heading peeking below when there is room. When there is not room for the peek, the peek is dropped — never the
heading, never the tiles' captions.

### FR-R367-3 — One rule, tested for convergence
`calculateScrollDistance` is pure; a unit test applies it repeatedly (scroll by the result, recompute) for both
requests (row rect, tile rect) at Home's and Discover's list heights (960 × 540 dp TV, plus a 600 dp-tall desktop
window) and asserts it reaches **0** within two steps for every row index. The same test covers every screen that
calls `rememberEdgeBringIntoViewSpec`.

## Acceptance

1. Discover → Request → Down to the second, third and last row: the page settles within one scroll animation; ten
   reads of the heading's position with no key pressed are identical.
2. Same on Coming Soon and on each wall (Networks, Studios, Genres).
3. Home and a collection page frame rows as before (heading under the app bar, next row peeking).
4. The convergence test (FR-R367-3) fails on today's code and passes after the fix.
