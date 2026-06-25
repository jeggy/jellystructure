# Phase R65 — Navigate-up: reveal the row title + clear the app bar (FR-RV-UP1)

## Problem
On the Ravilo Home screen, navigating **up** with the D-pad to the top content row scrolls the focused
**poster tile** to the very top edge of the screen. That hides two things that should stay visible:
1. the **top app bar / nav bar** (the tile lands flush under it / collides with it), and
2. the **content-row title** (e.g. "Action", "Comedy") that sits just above the tiles.
There should also be a small **top padding** above the title so it looks intentional, not crammed.

## Findings (root cause)
The Home `LazyColumn` is full-bleed (`fillMaxSize`, content starts at `y=0`) and the `AppBar` is a
**separate 60 dp overlay drawn on top of it** — content scrolls *under* the bar (`HomeScreen.kt:161,164`
deliberately has no top `contentPadding` because item 0 is the full-bleed hero that must sit under the
transparent bar). The vertical bring-into-view spec
(`focus/BringIntoView.kt → rememberEdgeBringIntoViewSpec(peekDp=80.dp)`, installed at
`HomeScreen.kt:158`) reveals a top-clipped target at the viewport's **absolute top (offset 0)** with
**no top inset**:
```kotlin
offset < 0f -> offset          // clipped at TOP → scrolls the target's top edge to y=0
```
`StaticContentRow` (`components/ContentRow.kt:78,82-83`) requests the **whole row (title + tiles)** into
view on focus via a row-level `BringIntoViewRequester`. With no inset, that plants the **row title at
y=0** — under the 60 dp bar — and the first tile flush at the bar's bottom edge. The title band
(~title 24 + `rowHeadPadB` 10 + LazyRow `trackPadV` 20 ≈ 54 dp) falls inside the 0–60 dp strip hidden by
the bar. Exactly the reported symptom.

**Precedent that confirms it:** `DiscoverScreen.kt:160` uses the *same* spec but adds
`contentPadding = PaddingValues(top = 84.dp …)` (60 bar + ~24 headroom) and has no hidden-title problem.
Home can't use top `contentPadding` (the hero must sit under the bar), so the inset has to live in the
**bring-into-view spec** instead. There's also no shared token: the bar's 60 dp is hardcoded in
`AppBar.kt:102`, and nothing tells the spec about it.

## Goal
Navigating up parks the focused top row so that, top-to-bottom, you see: a small **top padding**, the
**app bar**, the **row title**, then the **focused tile** — nothing clipped.

## Requirements
1. **Add a top inset to the vertical bring-into-view spec.** Give `rememberEdgeBringIntoViewSpec`
   (`focus/BringIntoView.kt`) a `topInsetDp: Dp` parameter; in the top-clip branch reveal the target at
   the inset line instead of `y=0`:
   ```kotlin
   val topPx = with(density) { topInsetDp.toPx() }
   when {
       offset < topPx                -> offset - topPx
       offset + size > containerSize -> offset + size - containerSize + peekPx
       else                          -> 0f
   }
   ```
2. **Size the inset to clear the bar + title + padding.** At the `HomeScreen.kt:158` call site pass
   `topInsetDp = appBarHeight + rowTitleBand + headroom` (≈ 60 + ~34 + ~12 ≈ **~94 dp**, in line with
   Discover's 84 dp). Because the row-level requester reveals the whole row including the title, this
   lands the title just below the bar with breathing room; sizing it to also clear the title band makes
   it robust against the per-tile (R54) focusable path (which excludes the title).
3. **Tokenize the app-bar height.** Add `appBarHeight` (and optionally `contentTopInset`) to
   `theme/Dimens.kt`; reference it from `AppBar.kt:102`, `HomeScreen.kt:158`, and `DiscoverScreen.kt:160`
   so the bar height and the inset never drift apart (today they're three separate literals).
4. **Apply the same inset to the Channel screen.** `ChannelScreen.kt:163` uses the identical spec over an
   overlay `ChannelBar` and has the same defect — give it the same inset.

## Scope
- `ravilo-ui/.../focus/BringIntoView.kt` (`rememberEdgeBringIntoViewSpec` + inset param)
- `ravilo-ui/.../screens/HomeScreen.kt` (call site) + `screens/ChannelScreen.kt`
- `ravilo-ui/.../theme/Dimens.kt` (new `appBarHeight` token) + `components/AppBar.kt` (consume it)

## Non-goals
- No change to horizontal tile reveal (the LazyRow's own `BringIntoViewSpec`, `ContentRow.kt:61-73`,
  with the `trackPadH` left inset — R45 — stays).
- Detail screens (`MovieDetailScreen`/`SeriesDetailScreen`) use a different, parameterless spec — out of
  scope here.

## Acceptance
- D-pad up to the top content row: the row title ("Action"/"Comedy"/…) is fully visible below the app
  bar, with a small top padding, and the focused tile sits below the title — nothing clipped by the bar.
- Same on a Channel page. Resting (un-scrolled) hero framing is unchanged.
