# Phase R25 — Polish: loading skeletons, focus glow, scroll & misc (FR-RV25)

**Status:** Planned · _depends on R23 + R24 (design system + correct sizes)._

## Problem

After R23/R24 the app will look correct and on-brand. This phase handles the remaining
category of gaps that affect **perceived quality** — the things a user notices in the first
two seconds: blank white-text "Loading…" states that interrupt immersion, mismatched focus
glow shadows, scroll that jumps instead of glides, and detail-level polish items that make
the app feel finished vs. prototype.

## Current state (as-is)

- All screens show plain `Text("Loading…")` while data loads.
- Focus shadow uses `focusRing` (opaque accent) for ambient/spot color — glow looks like a ring
  of colored light rather than a soft radial bloom.
- `LazyRow` uses `scrollToItem` (instant jump) when focus changes columns.
- Watched episodes render at full opacity.
- Browse filter chips are low-contrast in selected state.
- Hero backdrop image is not biased toward the upper half.
- Row "See All" affordance is unimplemented.
- `animateScrollToItem` is not used anywhere.

## Requirements

### 1. Shimmer skeleton utility

Create `ravilo-ui/src/commonMain/.../components/Shimmer.kt`:

```kotlin
@Composable
fun rememberShimmerBrush(): Brush {
    val shimmerColors = listOf(
        Color.White.copy(alpha = 0.05f),
        Color.White.copy(alpha = 0.13f),
        Color.White.copy(alpha = 0.05f),
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -1200f,
        targetValue  =  2400f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "shimmerX",
    )
    // Do NOT wrap in remember(x) — x changes every frame so remember(x) cache-misses every
    // frame, allocating a RememberObserver wrapper object on each call, which is more GC
    // pressure than just creating the Brush directly. Brush.linearGradient is a cheap data
    // object; the allocation cost is negligible compared to the wrapper overhead.
    return Brush.linearGradient(shimmerColors, start = Offset(x, 0f), end = Offset(x + 1200f, 0f))
}
```

**Performance contract:** one `rememberInfiniteTransition` per screen (or per composition root).
Do **not** call `rememberShimmerBrush()` inside a `LazyRow` item slot — call it once above the
list and pass the resulting `Brush` down. One `animateFloat` drives all shimmer boxes in view.

### 2. Skeleton components

```kotlin
@Composable
fun ShimmerBox(width: Dp, height: Dp, radius: Dp = 8.dp, brush: Brush) {
    Box(Modifier.size(width, height).clip(RoundedCornerShape(radius)).background(brush))
}
```

Build higher-level skeletons from `ShimmerBox`:

- **`ShimmerTile(variant: TileVariant, brush: Brush)`** — matches the R24 tile dimensions;
  shows art placeholder + two shimmer text lines below (18 dp tall / 10 dp tall).
- **`ShimmerEpisodeCard(brush: Brush)`** — 392 dp wide; 16:9 still placeholder + two text lines.
- **`ShimmerRow(count: Int, variant: TileVariant, brush: Brush)`** — horizontal `Row` of
  `count` shimmer tiles with `RaviloDimens.itemSpacing` gap, padded with `RaviloDimens.trackPadH`.
- **`ShimmerHero(brush: Brush)`** — full 600 dp hero placeholder; three text shimmer blocks
  (kicker 17 dp, title 72 dp, synopsis 22 dp) aligned to bottom-start matching the live hero.

### 3. Home screen skeleton

Replace `HomeLoadingShell` (currently `Text("Loading…")`) with:

```kotlin
@Composable
fun HomeLoadingShell() {
    val brush = rememberShimmerBrush()
    Column(Modifier.fillMaxSize().background(RaviloTheme.colors.background)) {
        ShimmerHero(brush)
        Spacer(Modifier.height(RaviloDimens.rowGap))
        ShimmerRow(count = 5, TileVariant.LANDSCAPE, brush)  // channel rail
        Spacer(Modifier.height(RaviloDimens.rowGap))
        repeat(3) {
            ShimmerRow(count = 6, TileVariant.POSTER, brush)
            Spacer(Modifier.height(RaviloDimens.rowGap))
        }
    }
}
```

### 4. Detail screen skeleton

Replace loading state in `MovieDetailScreen` / `SeriesDetailScreen` with:

```kotlin
@Composable
fun DetailLoadingShell() {
    val colors = RaviloTheme.colors
    val brush = rememberShimmerBrush()
    Column(Modifier.fillMaxSize().background(colors.background)) {
        // hero band — use fillMaxWidth directly; ShimmerBox takes a fixed width so can't represent
        // a full-bleed hero. A plain Box is cleaner here:
        Box(Modifier.fillMaxWidth().height(620.dp).background(brush))
        Spacer(Modifier.height(32.dp))
        // cast row label
        ShimmerBox(200.dp, 29.dp, 6.dp, brush)
        Spacer(Modifier.height(12.dp))
        ShimmerRow(5, TileVariant.POSTER, brush)
    }
}
```

### 5. Browse & search skeleton

- **Browse** (`BrowseScreen`): while loading, show `ShimmerRow(count = 8, TileVariant.POSTER, brush)`
  × 2 rows below shimmer filter chips.
- **Search** (`SearchScreen`): while `results.isEmpty() && query.isNotBlank() && isLoading`,
  show `ShimmerRow(count = 6, TileVariant.LANDSCAPE, brush)`.

### 6. Focus glow — one-pass fix

The shadow on every focusable component currently uses `colors.focusRing` (full-opacity accent)
for both `ambientColor` and `spotColor`. This makes the shadow look like a hard-colored halo
rather than a soft bloom.

**Fix in:** `Tile.kt`, `ChannelCard.kt`, `CastCircle.kt`, `EpisodeCard.kt`, `SeasonPicker.kt`,
`ProfileTile.kt`, `RaviloButton.kt` — change every `.shadow(elevation, shape, ambientColor = ...,
spotColor = ...)` call to:

```kotlin
.shadow(
    elevation    = shadowElevation,
    shape        = tileShape,
    clip         = false,
    ambientColor = colors.focusGlow,   // ← was focusRing
    spotColor    = colors.focusGlow,   // ← was focusRing
)
```

`focusGlow` carries the skin-appropriate alpha (55–80 %) so the glow spreads softly.
The border (`.border(borderWidth, colors.focusRing, shape)`) keeps using `focusRing` at full
opacity — crisp ring + soft glow is the correct two-layer treatment.

### 7. Smooth scroll-to-focused item

When focus enters a `LazyRow` item that is partially or fully off-screen, the row should
scroll smoothly to reveal it. Replace `scrollToItem` calls with `animateScrollToItem`:

```kotlin
LaunchedEffect(focusedIndex) {
    if (focusedIndex >= 0) lazyListState.animateScrollToItem(focusedIndex)
}
```

Apply in: `StaticContentRow`, `HomeScreen` episode row, `BrowseScreen` grid scroll triggers.

### 8. Browse filter chip polish

Filter chips use `surfaceVariant` for selected state — insufficient contrast. Update:

- **Unselected unfocused**: no border, `textSecondary` label.
- **Selected**: `border(2.dp, colors.accent)` + `accent.copy(0.15f)` background + `accent` label color.
- **Focused (any state)**: inverted — `background(colors.text)`, `text = colors.background` — same
  pattern as AppBar nav item.
- Animate `borderWidth` and `backgroundColor` with `animateColorAsState`.

### 9. Watched episode opacity

In `EpisodeCard`, when `ep.watched`:
```kotlin
RemoteImage(url = ep.stillUrl, modifier = Modifier.matchParentSize().alpha(0.62f), ...)
```

The 38 % dim makes watched episodes visually recede without disappearing, matching the design.

### 10. LazyRow smooth focus-scroll behaviour

Currently all `LazyRow`s use `rememberLazyListState()` but never scroll to the focused item.
The focus wiring in `FocusRow` / `dpadFocusable` calls `onFocused` with the column index.
Each `ContentRow` should hold a `LazyListState`, a focused-index state, and a focused-row flag:

```kotlin
val listState = rememberLazyListState()
val focusedIdx = remember { mutableIntStateOf(-1) }
var rowHasFocus by remember { mutableStateOf(false) }

// Only scroll when the row is actually focused — prevents spurious scrolls on first
// composition (when focusedIdx is set to 0 before any user interaction).
LaunchedEffect(focusedIdx.intValue, rowHasFocus) {
    if (rowHasFocus && focusedIdx.intValue >= 0) {
        listState.animateScrollToItem(
            index = focusedIdx.intValue,
            scrollOffset = -80,   // reveal partial neighbour on left
        )
    }
}
```

Update each tile's `onFocused` to set both: `focusedIdx.intValue = i; rowHasFocus = true`.
Update the `onBlurred` or the row's `onDown`/`onUp` exit handlers to set `rowHasFocus = false`.

**Why the guard?** `LaunchedEffect(focusedIdx.intValue)` without the flag fires immediately on
first composition when `focusedIdx = 0`, causing the list to scroll to item 0 before any user
has touched it. On a partially-off-screen row this snaps the scroll position unexpectedly.

### 11. Series season progress bar

In `SeriesDetailScreen`, next to the season label render a compact inline progress bar:

```kotlin
Row(verticalAlignment = Alignment.CenterVertically) {
    Text("Season $n", ...)
    Spacer(Modifier.width(12.dp))
    Box(Modifier.width(180.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
        .background(colors.progressBg)) {
        Box(Modifier.fillMaxWidth(watchedFraction).fillMaxHeight()
            .background(colors.progressFill))
    }
    Spacer(Modifier.width(10.dp))
    Text("$watchedCount / $totalCount", color = colors.textDim, fontSize = 14.sp)
}
```

### 12. Search screen keyboard sizing

Search keyboard keys: increase from whatever the current size is to `heightIn(min = 62.dp)`,
`widthIn(min = 62.dp)`, `fontSize = 24.sp Sora`. Increase key gap to `12.dp`. This matches the
design's on-screen keyboard which is sized for remote-control D-pad precision.

### 13. Search bar prominence

The search query bar at the top of `SearchScreen`:

- Height: `height(76.dp)`.
- Background: `colors.card`, `RoundedCornerShape(16.dp)`, `border(1.dp, colors.surfaceVariant)`.
- Query text: `27.sp SpaceGrotesk 600`, `colors.text`.
- Search icon (left): `30.sp`, `colors.textDim`.
- Placeholder: `"Search movies, series, people…"`, `27.sp`, `colors.textDim`.

## Invariants

- `rememberShimmerBrush()` must be called **once per screen**, not per item — pass the `Brush`
  down as a parameter. Compose will correctly invalidate only the single `animateFloat` node.
- Shimmer boxes should use `remember { RoundedCornerShape(r) }` for their shape — shape
  allocation is not free.
- `animateScrollToItem` is a `suspend` function; call it from `LaunchedEffect`, never from
  inside layout or composition.
- Skeleton composables are purely visual — no state, no network calls, no `remember` other
  than shape objects.
- The focus glow fix (`focusGlow` instead of `focusRing` for shadows) is a one-liner in each
  file — apply in a single commit to keep the diff readable.

## Out of scope

- Player seek bar and player chrome resizing (separate player phase).
- Backdrop blur / frosted glass effects (requires API 31 `RenderEffect`; deferred).
- AppBar appearance on detail pages (detail pages currently do not render an AppBar; no change here).
- Per-profile avatar gradient (can be added as a small follow-up once profiles are wired).
