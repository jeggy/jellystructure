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

## Change (all values in `rememberEdgeBringIntoViewSpec` + the `LazyColumn` bottom padding)

- **Down peek** `peekDp 80 → 150`: a focused row's tiles sit higher, leaving room below for the **next
  row's title + a sliver of its tiles** to peek. You see the current row's title *and* the next one.
- **Up inset** `topInsetDp (appBar + 34) → (appBar + 64)`: clears the 60dp bar **plus** the full ~54dp
  title band (+ margin), so the focused row's title is always fully visible when navigating up.
- **Last-row lift** bottom `contentPadding 40 → 220`: enough trailing space that the LazyColumn can scroll
  the **last** row up to the same comfortable height as the others — never stranded at the bottom edge.

Applied identically to **Home**, **Channel**, and **Discover** (same content-row layout). Mechanism is the
existing edge BIV — no new scroll path, no change to R108/R139 (back-focus restore) behaviour.

## Verification (on-device, stue TV)

- Down → focused row mid-screen, **next row's title peeks** at the bottom. ✓
- Up → focused row's **title clearly visible** below the bar. ✓
- Deep/last row → title visible, comfortable height, not bottom-stranded. ✓

## Files

- `screens/HomeScreen.kt`, `screens/ChannelScreen.kt`, `screens/DiscoverScreen.kt` — `peekDp` / `topInsetDp`
  + bottom `contentPadding`. (`focus/BringIntoView.kt` unchanged — only its parameters.)
