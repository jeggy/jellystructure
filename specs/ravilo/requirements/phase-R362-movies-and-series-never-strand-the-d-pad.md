# Phase R362 — Movies and Series never strand the D-pad

> Owner, 2026-10-04: *"When using the DPad remote, and going to movies/series page and applying filters and then
> opening and starting to play something and then going back to the movies page my dpad sometimes get stuck on the
> top navbar, so I can't continue my browsing of movies."*

## Status

`Planned` — written 2026-10-04 (dev-authored) from a D-pad sweep on the living-room Sony BRAVIA, reproduced on the
installed `1.47-34` and again on release `1.49-11-g76f351b4` (= `main` for these files). Not dev-reviewed. Client only
(`:ravilo-ui`, `SeededBrowseScreen.kt`). The case where the opened title has left the filtered grid is **R361**.

**Amends:** R187 (the facet bar and popover), R350 FR-R350-9 (Up from the grid's first row).

## What was seen

1. **The stuck app bar (the owner's bug).** Movies → Up to the facet bar → Right along the chips until the bar
   scrolls (any chip past *Channel*; the last one, *Sort*, makes it certain) → Up to the app bar → **Down does
   nothing**, on any tab, however many times it is pressed. Left/Right still move along the bar; only Back (to Home) or
   choosing a tab gets out. The bar stays scrolled across a detail page, a play and a Back, and across leaving and
   re-entering Movies (kept store), so the trap waits for the viewer: browse, filter, play, come back, press Up once
   to reach the bar, and Down is dead.
2. **The active filter is out of sight.** Back on Movies with *Genre (Horror)* applied, the facet bar is still
   scrolled right: the chip that says what the grid is showing is off screen, and the grid's count is the only clue.
3. **Down keeps the column only while the next row is on screen.** In the 6-column grid, column 4 → Down lands on
   column 4 while the next row peeks in, but when the next row is entirely below the screen Down lands on
   **column 1**. Every few rows the viewer's column resets. The Discover walls (whose next row always peeks in) keep
   the column.
4. **The popover opens at the left edge**, not under its chip (*Channel*'s and *Watched*'s open at x = 96 under
   *Genre*), and is a fixed 280 dp tall even for *Watched*'s three options.

**Cause of 1 (code-traced on `main` 76f351b4).** The app bar's `onDown` and the grid's `onFirstRowUp` both call
`runCatching { firstFacetFR.requestFocus() }` — the requester on the **Genre** chip, the first item of the facet
`LazyRow`. Scrolled away, that item is not composed, its requester is not attached, `requestFocus()` throws, and
`runCatching` swallows it: the key is consumed and nothing happens. The row's `focusRestorer()` would send the
request to the last focused chip, but only if the request reaches the row. Same shape as R200/R201 (a requester on
an item that is not composed), now in the facet bar. **Cause of 3:** Down inside the grid is Compose's native 2-D
search; when the next row is not composed, the first item the grid composes beyond its bounds (column 1) is the
only candidate.

## Requirements

### FR-R362-1 — Down from the app bar always enters the page
Down from any app-bar item on Movies / Series / a seeded browse page focuses a facet chip that is **on screen**:
the chip last focused in the bar if it is still on screen, else the facet bar scrolls back to its start and **Genre**
is focused. Scroll first, then focus (await the layout, bounded retry, R257's idiom). A Down that cannot find a target
falls to the grid's first visible tile; it never does nothing.

### FR-R362-2 — Up from the grid's first row always reaches the facet bar
R350 FR-R350-9's Up uses the same resolution as FR-R362-1 (the last focused chip if on screen, else the bar scrolls
to its start and Genre is focused). Never the app bar, never nothing.

### FR-R362-3 — No fire-and-forget focus requests on lazy items
In these screens (and as a rule for new code), `runCatching { fr.requestFocus() }` aimed at an item of a lazy list
is replaced by one helper that scrolls the item into view, waits for it to be composed and then requests focus, and
logs (debug) when it gives up. A key handler that consumes a key must land focus somewhere or not consume the key.

### FR-R362-4 — The bar shows the active filters when the page is shown
Arriving at Movies / Series (a tab press, a Back from a title, a Back from the player) with the focus **not** being
restored into the facet bar shows the bar from its start, so the applied filter chips (*Genre (Horror)*, *Watched
(Unwatched)*) are in view. The bar keeps its scroll only while the viewer is in it.

### FR-R362-5 — Down and Up in the grid keep the column
Down from a tile at column *c* focuses the tile at column *c* of the next row (the last tile of that row if it is
shorter), scrolling it into view first; Up likewise. Left/Right unchanged. Handled by the grid's key handler (the
index arithmetic is exact: `index ± columns`), not by native search.

### FR-R362-6 — The popover sits under its chip and fits its options
A facet popover opens horizontally aligned with the chip that opened it (clamped inside the screen's safe area) and
is as tall as its options up to the current 280 dp cap. Sort's popover likewise under *Sort*.

## Acceptance

1. Movies → Up → Right ×10 (to *Sort*) → Up → Down: a chip is focused and visible (Genre, bar scrolled to the start).
2. Then open a title, play 20 s, Back, Back → Up to the bar → Down: lands on a visible chip.
3. Grid first row, any column → Up with the bar scrolled right: lands on a visible chip.
4. Apply *Genre: Horror*, scroll the bar right, open a title, Back: *Genre (Horror)* is on screen.
5. Grid column 4, Down ×6: every focused tile is in column 4.
6. *Channel*'s popover opens under *Channel*; *Watched*'s is three rows tall.
7. Robolectric key-by-key walks for 1–5 on the TV path (the existing `BrowseFocusTest` gains them).
