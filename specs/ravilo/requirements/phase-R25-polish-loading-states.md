# Phase R25 — Polish: loading skeletons, focus glow, smooth scroll (FR-RV25)


## What was built

### Shimmer skeleton utility
`ShimmerBrush` animates a sweep gradient from dark → lighter → dark across a placeholder shape.
`HomeLoadingShell`, detail shimmer, browse shimmer, and search shimmer composables render full-screen
skeletons matching the real layout, so the screen is never blank while loading.

### Focus glow fix
All `Modifier.shadow(…, spotColor = …)` calls that previously passed `focusRing` (the border color,
purple/blue) corrected to use `focusGlow` (a softer ambient version of the accent). The result is
a diffuse halo rather than a sharp colored shadow.

### Smooth horizontal scroll
`StaticContentRow` `LazyRow` uses `animateScrollToItem` on `focusedIndex` change (guarded by
`rowHasFocus` so the initial composition does not scroll to 0). Updated in a follow-up to use
`animateScrollBy(minimalOffset)` — scrolls only the amount needed to reveal the focused tile's edge
rather than snapping the tile to position 0, eliminating the "jump" on right navigation.

### Season progress bar
`SeasonPicker` renders a thin progress bar under each season tab showing watched/total ratio.

### Browse chip contrast
Browse filter chips (Movies / Series / Genre / etc.) use higher-contrast focus state: white fill
with black text when focused (was: only a border change).

### Search bar sizing
Search input bar raised to 76 dp height to match the on-screen keyboard's visual weight.
