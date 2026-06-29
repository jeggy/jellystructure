# R140 — Comfortable vertical framing for content rows (Home · Channel · Discover)

> Tunes the **vertical** bring-into-view (`rememberEdgeBringIntoViewSpec`, R45/R65) so a focused content
> row sits at an easy-on-the-eyes height instead of hugging the screen edges.

## Problem

When D-pad navigating **down** the home/channel/discover screens, the focused row landed near the very
bottom — you had to look all the way down, and the next row's title wasn't visible (no sense of "what's
next"). On the **last** row it was worse: stranded flush at the bottom. Navigating **up**, the focused
row's *title* was sometimes hidden under the overlay app bar (the top inset reserved only ~34dp, but the
title band — `trackPadV 20 + rowHeadPadB 10 + title ~24` ≈ **54dp** — is taller than that, plus the 60dp
bar), so the row gained focus with no visible label.

## Change (`rememberEdgeBringIntoViewSpec` + the `LazyColumn` bottom padding)

- **Down → focus band** `centerLineFraction = 0.3`: a new BIV mode. On downward nav the focused row's top
  is pulled to **30 % of the viewport height**, so the row you're looking at sits toward the **middle** of
  the screen (next row + its title peeking below) instead of hugging the bottom. Crucially the band fires
  **even when the target is already fully visible** — the edge-only peek can't, because once the previous
  row's peek has revealed the next row, its own bring-into-view is a no-op (returns 0) and it never
  re-centres. Expressed as a **fraction of the viewport** (not a dp) so it's density-independent.
- **Up inset** `topInsetDp (appBar + 34) → (appBar + 64)`: clears the 60dp bar **plus** the full ~54dp
  title band, so the focused row's title is always visible on up-nav. (Up behaviour is otherwise unchanged
  — the band only acts downward.)
- **Last-row lift** bottom `contentPadding 40 → 240`: trailing space so the LazyColumn can scroll the
  **last** row up to the band like any other — never stranded at the bottom edge.

Applied identically to **Home**, **Channel**, **Discover**. No change to R108/R139 (back-focus restore);
detail screens pass no `centerLineFraction` → pure edge behaviour, unchanged.

### Density lesson
The stue TV is **320dpi (2×)** → a 1920×1080 panel is **960×540 dp**. The first attempt used a *dp* centre
line (`centerLineDp = 360`), which became 720 px — *below* the focused tile — so the band never fired. A
viewport-**fraction** sidesteps the density entirely.

## Verification (on-device, stue TV)

- Down → focused row centred mid-screen, **next row + its title peek** below; holds row-to-row. ✓
- Up → focused row's **title clearly visible** below the bar (unchanged, "perfect"). ✓
- Last row → lifts to the same band, not bottom-stranded. ✓

## Files

- `focus/BringIntoView.kt` — `centerLineFraction` param + the down-band branch in `calculateScrollDistance`.
- `screens/HomeScreen.kt`, `screens/ChannelScreen.kt`, `screens/DiscoverScreen.kt` — pass
  `centerLineFraction = 0.3f` + `topInsetDp` + bottom `contentPadding`.
