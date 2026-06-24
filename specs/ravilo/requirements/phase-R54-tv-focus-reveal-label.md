# R54 — Ravilo TV: reveal the tile label when focusing down a row (FR-RF1)

**Status:** ✅ Done — implemented the **primary** approach: `dpadFocusable` moved from the inner poster
`Box` to the outer tile `Column` in `components/Tile.kt`, so the focus/bring-into-view target spans the
label; the draw-only scale/ring stay on the poster (R42 preserved).
**Depends on:** R10 (Home), R42/R45 (draw-only focus + edge bring-into-view)

## Goal

Navigating **down** the Home screen and focusing a tile in a lower row brings the **poster image** into
view but leaves the **title/subtitle text just below the poster clipped** off the bottom of the screen.
Focusing a tile should scroll a little further so the **whole tile (poster + its label)** is visible.

## Root cause

`components/Tile.kt`: the **focusable is only the poster `Box`** (fixed `width×height`); the `title` and
`subtitle` `Text`s live in the **outer `Column`, below** the focusable. The Home `LazyColumn` uses
`focus/BringIntoView.kt` `EdgeBringIntoViewSpec`, whose bottom-clip branch reveals exactly
`offset + size − containerSize` where `size` = the **poster** height. So the poster's bottom lands flush
with the viewport edge and the ~8 dp gap + title + subtitle (~40–46 dp) below it stay clipped.

## Target

When a tile gains focus, the vertical scroll reveals the **full tile**, including the label, with a small
breathing gap below it.

## Approach (implementation guidance, not prescriptive)

**Primary — make the focusable bounds the whole tile.** Move `dpadFocusable` from the inner poster `Box`
to the **outer tile `Column`** (poster + gap + title + subtitle). The Column has a **fixed layout size**
(nothing animated), so R42 still holds — keep the focus **scale + ring + shadow draw-only on the inner
poster** (the visual treatment is unchanged; only the *focus/bring-into-view target* grows to include the
label). `EdgeBringIntoViewSpec`'s `size` then spans the label, so the bottom-clip reveal shows it. This
auto-corrects if the label height changes and needs no magic constant, and it fixes every `LazyColumn`
row that uses `Tile` (Home, Channel, Discover, Browse-grid cells).

**Alternative — bottom reveal margin.** Keep the poster focusable but add a small bottom inset
(≈ label height, ~46 dp × tileScale) to the vertical reveal for content rows (a Home-specific
`BringIntoViewSpec` variant), so a bottom-clipped tile over-scrolls by the label area.

Either way: don't regress R42 (no viewport-jump from the scale animation) or R45 (hero re-frame / edge
reveal); the horizontal `ContentRow` left-inset reveal is unaffected.

## Non-goals / invariants

- **Shared `:ravilo-ui`** common; D-pad-first; one clear focus target.
- No change to the focus **visual** (scale/ring/shadow stay on the poster) — only what counts as the
  focused tile's bounds for scroll.
- Server-pushed state only; no API/config change.

## Mockup

Behavioural (no new visual) — the design rows always show poster **and** label for the focused tile;
`design/ravilo/Ravilo TV.html` content rows.
