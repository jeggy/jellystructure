# Phase R362 — Movies and Series never strand the D-pad

> Owner, 2026-10-04: *"When using the DPad remote, and going to movies/series page and applying filters and then
> opening and starting to play something and then going back to the movies page my dpad sometimes get stuck on the
> top navbar, so I can't continue my browsing of movies."*

## Status

`Planned` — written 2026-10-04 (dev-authored) from a D-pad sweep on the living-room Sony BRAVIA, reproduced on the
installed `1.47-34` and again on release `1.49-11-g76f351b4` (= `main` for these files). Dev-reviewed 2026-10-04 against `main` `5210045a` (see the end; still `Planned`). Client only
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

## Dev review (2026-10-04, against `main` `5210045a`)

Read against `SeededBrowseScreen` (`FacetBar`, `FacetPopover`, `SortPopover`, `BrowseCardGrid`), `BrowseScreen`,
`FocusModifiers.kt`, `BrowseFocusTest` and the Compose UI 1.9.4 `FocusRequester` bytecode. The design holds. One
correction to the cause and one more shipped bug of the same kind. Eleven items, none for the owner.

1. **Cause 1: right result, wrong mechanism.** `requestFocus()` on a requester with no attached node does not throw
   in Compose 1.9. It prints `FocusRelatedWarning` and returns `false`, so `runCatching` has nothing to swallow. The
   key is still consumed, because `dpadFocusable` consumes any key it has a callback for. The sites are the app bar's
   `onDown` (`SeededBrowseScreen.kt:606`), the grid's `onFirstRowUp` (`:593`) and the facet chips' `onBarDown`
   (`:535`).

2. **A second dead Down, same cause (shipped bug, fix here).** The grid attaches `firstCellFR` to tile 0 *unless*
   tile 0 is the restore target (`:1069`; `BrowseScreen.kt:453` is the same). `store.focusItemKey` is never cleared
   after a successful restore. So: open the **first** title, Back, Up to the facet bar, Down → nothing, until another
   title is opened or the sort changes. The app bar's Down on a page without a facet bar, and My List's, die the same
   way. FR-R361-4's "clear the key once resolved" removes the condition. FR-R362-1's "falls to the grid's first
   visible tile" should not use `firstCellFR` at all (item 7).

3. **Cause 3 is plausible but not proven here.** It is Compose's 2-D search over a lazy grid's beyond-bounds
   layout. FR-R362-5's explicit index arithmetic removes the dependence either way.

4. **Cause 4 confirmed.** The popover is a `Column` child with `padding(horizontal = raviloHPad)` (`:816`), so it
   always sits at the left edge. Its list is `LazyColumn(Modifier.height(280.dp))` (`:832`).

5. **The retry helper has to be fixed first.** `requestFocusRetrying` and `requestFocusRetryingOrMoveNative` test
   `isSuccess`, which is `true` even when `requestFocus()` returned `false`. So they never retry and never fall back
   (R361's review, item 3). After the fix, FR-R362-3's helper is a small wrapper:
   `scrollThenFocus(state, index, requester, fallback)`. It scrolls only if the item is not fully visible, awaits a
   frame, then calls the fixed helper with a native `moveFocus` fallback. That fallback is the "land focus somewhere"
   half of FR-R362-3, since `dpadFocusable` cannot decline a key it has a callback for.

6. **FR-R362-1/2: one `focusFacetBar()`, and drop the bar's `focusRestorer()`.** Keep the last-focused chip's key in
   the store (set in the chips' `onFocused`). If that key is fully inside `facetBarState.layoutInfo.visibleItemsInfo`,
   request its requester, which is composed. Otherwise `facetBarState.scrollToItem(0)`, then Genre with the fixed
   helper. The app bar's `onDown` and the grid's `onFirstRowUp` both call it.
   Replace `Modifier.focusRestorer()` on the bar (`:647`) with this explicit memory. R350 FR-R350-1 found, on the
   season pills, that `focusRestorer()` in Compose 1.9 also redirects a `requestFocus()` aimed at a child. Here it
   could send the Genre request to a chip that is no longer composed.

7. **Down from a chip: the grid's first visible tile.** Replace `onBarDown`'s `firstCellFR` with a key-targeted
   requester: the tile at `gridState.firstVisibleItemIndex` (the first fully visible one), attached the way
   `restoreFR` is, then requested with the fixed helper. Never a requester fixed to item 0.

8. **FR-R362-4 amends R187 FR-RV-BROWSE1-10 too.** That FR keeps the strip "scrolled as it was left"
   (`SeededBrowseScreen.kt:645`). Add it to *Amends*. Do it in the screen's arrival effect (beside
   `LaunchedEffect(store) { store.load() }`) as `facetBarState.requestScrollToItem(0)`. A Back from a title or the
   player never restores focus into the bar (popovers close on leave, R187 FR-8a). So in practice every arrival
   resets the strip. Only movement inside the bar scrolls it.

9. **FR-R362-5 in the grid's key handler.** Extend the `onPreviewKeyEvent` at `:1052` with Down and Up, using a pure
   `gridVerticalTarget(index, cols, count, down): Int?`:
   - `index ± cols`, clamped to the last tile of a shorter last row;
   - `null` when there is no row in that direction, so Up from row 0 stays R350's `onFirstRowUp` and Down from the
     last row stays native, which reaches R190's Seerr overflow row on a person page.
   Focus the target with `scrollThenFocus` and a key-targeted requester. Apply the same handler to `BrowseScreen`'s
   `BrowseGrid` (My List, 6 columns, same fault). A shared grid key handler beats two copies.

10. **FR-R362-6.** Record each chip's x in root coordinates (`onGloballyPositioned` on the chip; the opening chip
    was just focused, so it is composed). Offset the popover to that x, clamped to
    `[raviloHPad, screenWidth − raviloHPad − popoverWidth]`. `facetPopoverWidth` is already computed. Change
    `height(280.dp)` to `heightIn(max = 280.dp)`. Sort works the same way under its chip. The phone gets the same
    alignment, clamped; that is harmless.

11. **Tests.**
    - `commonTest`: `gridVerticalTarget`, for a short last row, the first row and the last row.
    - `BrowseFocusTest` (Robolectric, already renders this screen with `fakeTvApiClient`) gains acceptance 1, 3, 4
      and 5. It also gains item 2's walk (open the first tile, return, Up, Down). It needs enough facets in the fake
      for the bar to scroll at the test's screen width.
    - Device only: acceptance 2 (a real play and two Backs) and 6 (how the popover looks).
    No conflict with the constitution: this is its "never strand focus" rule.
