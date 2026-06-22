# Phase R42 — Fix focus-animation viewport jump

**Status:** Planned · _navigating between tiles plays the focus scale animation but the whole screen
jumps. Stop the bring-into-view from re-centring already-visible tiles, reserve space for the scale, and
keep the scale draw-only._

## Problem
Moving focus left/right scales the focused tile (Tile 1.10×, ChannelCard 1.08×) — fine — but the viewport
jumps. Causes found:
1. **Bring-into-view re-centring.** Content rows use `LazyRow(Modifier.focusRestorer())`; native focus
   search + bring-into-view scrolls to the focused item and re-centres it even when it's already fully
   visible → a horizontal jump.
2. **Insufficient scale-overflow space.** `RaviloDimens.trackPadV = 20.dp`, but a 232dp POSTER tile at
   1.10× overflows ~23dp, exceeding the reserved padding → vertical shift / parent `LazyColumn`
   adjustment.
3. **Scale on the layout wrapper.** Scale is applied to the sizing wrapper
   (`Column(Modifier.width(w).scale(scale))` / `.size(...).scale(scale)`). `Modifier.scale` is draw-only
   so it shouldn't change measured size, but combined with (1)/(2) the screen still shifts.

## Goal
The focus scale animates smoothly with **no viewport movement** when the focused tile is already visible;
rows scroll only when the focused tile is actually off-screen, and then smoothly.

## Requirements
### A. Don't re-centre already-visible items
Tune bring-into-view / scroll-on-focus so an already-fully-visible focused tile does **not** trigger a
scroll (custom `bringIntoViewSpec` or focus handling). Off-screen focus still scrolls into view (minimal,
per R25).

### B. Reserve space for the scale
Ensure rows reserve enough vertical (and edge horizontal) space for the **largest** scaled tile so scaling
never overflows the row / `contentPadding` (e.g. raise `trackPadV` to cover POSTER at 1.10×, and enough
`contentPadding`/spacing for the glow halo). Keep `RaviloDimens` the single source.

### C. Keep the scale draw-only
Apply the focus scale as a draw-only transform (`graphicsLayer`) on the tile **content** so the lazy
list's measured item size is constant; the wrapper that defines the item's layout size stays unscaled.

## Invariants
- Focus model stays native Compose traversal + `focusRestorer` (R30); this only adjusts scroll/scale
  behaviour.
- One clear focus target keeps the scale + glow treatment (constitution); only the unwanted viewport
  movement is removed.

## Out of scope
- Changing the focus-traversal model, the scale magnitude, or the glow look.

## Design reference
`ravilo-ui/.../components/{Tile,ChannelCard,ContentRow}.kt`, `screens/HomeScreen.kt`,
`theme/Dimens.kt` (`trackPadV`/`trackPadH`).
