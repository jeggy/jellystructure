# Phase R45 — Entry scroll + focus restore (frame the hero, keep row insets)

**Status:** Planned · _screens open scrolled to a mid-page button and never re-frame the hero; rows
lose their left inset after you scroll into them and back. Make "top focus = top of page" hold._

## Problem
Across Home and the detail screens, returning focus to the top-most action **does not restore the
full top-of-page framing**, and a content row loses its leading inset once you've scrolled it:

1. **Home hero never re-frames.** On launch the hero is full-bleed. Scroll down to a content row,
   then back up: focus lands on the hero's Play / More Info / +My List buttons but the list does
   **not** scroll back to the top, so the hero image is never shown fully framed again as on launch.
2. **Content row loses its left inset.** Enter a row (e.g. "Newly Added") — item 0 sits at the
   row's left content-padding. Move right a few tiles, then all the way back left: item 0 is now
   flush against the viewport edge; the original left inset is gone.
3. **Movie detail auto-scrolls to Play.** Opening a movie immediately scrolls down to the Play
   button (which was already on-screen), instead of staying at the top showing the full hero.
4. **Series detail auto-scrolls to Resume.** Same as (3). Desired: open at the very top; and when
   focus moves down into the season/episode picker and **back up** to Play/Resume, the page should
   again scroll all the way to the top and re-frame the hero.

## Current state (as-is) — root causes
- **Detail screens request focus on a bottom-aligned button.** `MovieDetailScreen.kt`
  (lines ~95–96) and `SeriesDetailScreen.kt` (lines ~146,152) do
  `val playFR = remember { FocusRequester() }; LaunchedEffect(Unit) { playFR.requestFocus() }`.
  The Play/Resume button lives in a `Column(Modifier.align(Alignment.BottomStart)…bottom = 44.dp)`
  at the bottom of a **full-viewport-height hero** inside a `verticalScroll`. Requesting focus
  triggers Compose's default bring-into-view, which scrolls the column to reveal the button at the
  bottom edge — pulling the hero's top out of frame. (The in-code comment claims "no auto-scroll";
  the `requestFocus()` contradicts it.) The same happens on the way back up: focus returns to
  `playFR`, bring-into-view scrolls to the button, not to the hero top.
- **Home hero is `LazyColumn` item 0 with no snap-to-top.** `HomeScreen.kt` (lines ~96, 120–144):
  the hero is the first item; on re-entry from below, native focus traversal returns to the hero's
  buttons but nothing scrolls `listState` back to index 0/offset 0, so the partially-scrolled hero
  stays partially scrolled.
- **Edge bring-into-view ignores the row's `contentPadding`.** `components/ContentRow.kt`
  `EdgeBringIntoViewSpec` (lines ~33–43) returns `leading` (the raw offset) to bring a partially-off
  item's leading edge to the **container edge (0)**. For item 0 that means the `trackPadH` (48dp)
  `contentPadding` start is scrolled away — the inset is only honoured at true scroll-start, not
  after a scroll-back. Hence the lost left padding in (2).
- **Scroll state is not retained either.** R40 retains each screen's *store* but `rememberScrollState`/
  `rememberLazyListState` are recreated per entry, so even back-navigation can't restore offset
  (secondary; the primary bugs above are the focus-driven scroll, not retention).

## Requirements

### A. Top action focus ⇒ top of page
1. **Detail screens:** opening a movie/series must leave the page scrolled to the very top (offset 0)
   with the full hero framed, and Play/Resume focused **without** a reveal-scroll. When focus later
   returns to the Play/Resume actions row from below (season picker / episode rail / cast / related),
   the page must scroll all the way back to the top and re-frame the hero. Implement by either:
   - snapping the scroll to 0 when the actions row gains focus
     (`onFocusChanged`/`FocusEventModifier` → `scrollState.animateScrollTo(0)`), and/or
   - suppressing the entry reveal-scroll (give the `verticalScroll` a custom `BringIntoViewSpec` that
     returns 0 for an already-visible target, mirroring R42's edge spec), so `requestFocus()` no
     longer drags the hero out of frame.
2. **Home:** when focus is on the hero (top) — on launch and when returning up from a row — the
   `LazyColumn` must be at index 0 / offset 0 so the hero is fully framed. Snap to top when the hero
   regains focus (e.g. hero `onFocused` → `listState.animateScrollToItem(0)`), consistent with the
   detail behaviour.

### B. Row first-item restores its leading inset
3. When focus lands on a row's **first** item (including after scrolling right then back to the
   start), the row must show that item at its original `trackPadH` leading inset, not flush to the
   edge. Fix `EdgeBringIntoViewSpec` to leave the row's `contentPadding` start as margin when
   revealing a leading item (or, when the focused index is 0, scroll the row fully to start so
   `contentPadding` is honoured). Keep R42's guarantee: already-fully-visible tiles never re-centre.

### C. Don't regress focus smoothness
4. All of the above must preserve R42/R43: no per-frame recomposition from the focus scale, no
   viewport jump when moving between already-visible tiles, native traversal + `focusRestorer`
   unchanged. Snap-to-top should be a single smooth animateScroll, not a fight with bring-into-view.

## Invariants
- Native Compose focus traversal + `focusRestorer` (R30) stays the focus model; this phase adjusts
  **scroll** behaviour and entry focus only.
- One clear focus target keeps the scale/glow treatment; only unwanted scroll movement is removed.
- Renders server-pushed state only; no change to feed/detail data.

## Out of scope
- Persisting/restoring exact scroll offset across full back-navigation (R40 territory) — the goal
  here is deterministic "top focus = top of page", not byte-exact scroll memory.
- Changing hero height, tile shape, or the focus animation magnitude.

## Design reference
`ravilo-ui/.../screens/{HomeScreen,MovieDetailScreen,SeriesDetailScreen}.kt`,
`ravilo-ui/.../components/{ContentRow,HeroCarousel}.kt`, `theme/Dimens.kt`
(`trackPadH`/`trackPadV`/`heroBodyStart`). Related: R34 (full-bleed detail hero, "opens at top"),
R42/R43 (focus bring-into-view + smoothness), R40 (instant back navigation).
