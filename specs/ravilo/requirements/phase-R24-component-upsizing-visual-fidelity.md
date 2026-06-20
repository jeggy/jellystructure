# Phase R24 — Component upsizing & visual fidelity (FR-RV24)

**Status:** Planned · _depends on R23 (fonts + color tokens + spacing constants in place)._

## Problem

Every component in the current implementation is significantly smaller than the `design/ravilo/`
CSS target (`ravilo.css` / `Ravilo TV.html`) and several key visual structures are absent
entirely. The design was authored at 1920×1080; measurements in px map 1:1 to dp on a standard
1080p TV. Representative gaps:

| Component | Impl | Design | Delta |
|---|---|---|---|
| AppBar height | 64 dp | 92 dp | −30 % |
| Hero height | 440 dp | 600 dp (~56 % of 1080) | −27 % |
| Hero title | 36 sp | 72 sp | −50 % |
| Tile POSTER | 140×210 dp | 210×315 dp | −33 % |
| Tile LANDSCAPE | 224×126 dp | 360×202 dp | −38 % |
| Channel card | 120×70 dp | 268×150 dp | −55 % |
| Cast circle avatar | 72 dp | 106 dp | −32 % |
| Episode card width | 320 dp | 392 dp | −18 % |
| Episode still | 120×68 dp | full-width 16:9 | major |
| Row header | 18 sp | 29 sp | −38 % |
| Button height | ~48 dp | 60 dp | −20 % |

Additionally: hero has no kicker line, no auto-advance, a single-gradient scrim instead of the
dual tint+floor layering; channel cards have no branded gradient or watermark text; episode cards
have no still overlays; the AppBar focused nav item uses a dim tint instead of the inverted
(white-bg / dark-text) treatment.

## Current state (as-is)

Components exist and are architecturally correct (D-pad focus, spring animations, skin colors).
This phase resizes and visually redesigns them without changing focus wiring, data flow, or APIs.

## Requirements

### 1. AppBar

1. Height: `64.dp` → `92.dp`.
2. Horizontal padding: `40.dp` → `64.dp` (`RaviloDimens.screenPadH`).
3. Nav item gap: `32.dp` → `38.dp`.
4. Nav item font: `15.sp` → `21.sp`, `fontFamily = Sora`, `FontWeight.SemiBold`.
5. Focused nav item treatment: replace translucent `surfaceVariant` background with **inverted**
   — `background(colors.text, RoundedCornerShape(11.dp))` and text color `= colors.background`.
   Animate both with `animateColorAsState`.
6. Wordmark: add the jellyfish mark SVG (`Res.drawable.ravilo_mark`) at 36×36 dp to the left of
   "Ravilo"; wordmark text `SpaceGrotesk Bold 31.sp letterSpacing(-1.sp)`.
7. AppBar background gradient: replace `colors.background.copy(alpha = 0.95f)` with
   `Color(0x8C000000)` (black 55 %) at the top so it reads correctly over any hero backdrop.
8. Clock text: `19.sp`, `fontFamily = Sora`.

### 2. Hero carousel

1. Height: `440.dp` → `600.dp`.

2. Gradient: replace single vertical gradient with **two layers** drawn over the backdrop image:
   ```
   layer 1 — tint (horizontal, left readable):
       0.00 → colors.background.copy(alpha = 0.72)
       0.50 → colors.background.copy(alpha = 0.12)
       1.00 → Color.Transparent
   layer 2 — floor (vertical, bottom darkens):
       0.00 → Color.Transparent
       0.55 → colors.background.copy(alpha = 0.55)
       1.00 → colors.background
   ```
   Draw: backdrop → tint overlay → floor overlay. Both `Box` layers fill the parent.

3. Hero body alignment: change `padding(40.dp, 40.dp)` to
   `Modifier.align(BottomStart).padding(start = RaviloDimens.heroBodyStart, bottom = RaviloDimens.heroBodyBot)`.

4. Kicker line (new — above title): render `active.badge` or `active.genre` in uppercase if
   present:
   ```kotlin
   Text(
       text   = kicker.uppercase(),
       color  = colors.accentSecondary,
       fontSize = 17.sp,
       fontFamily = Sora,
       fontWeight = FontWeight.SemiBold,
       letterSpacing = 2.sp,
   )
   Spacer(Modifier.height(14.dp))
   ```

5. Title: `36.sp` → `72.sp`, `fontFamily = SpaceGrotesk`, `FontWeight.Bold`,
   `letterSpacing = (-2).sp`, `lineHeight = 84.sp`.
   Note: `lineHeight` must be larger than `fontSize` — setting both to `72.sp` produces zero
   leading and jams 2-line titles together. `84.sp` (1.17×) gives minimal readable breathing room.

6. Synopsis (new): render `active.overview` at `22.sp Sora`, `lineHeight = 33.sp`,
   `colors.textSecondary`, `maxLines = 2`, `overflow = Ellipsis`, with `Spacer(20.dp)` above.

7. Dots: animate dot width with `animateDpAsState` (spring, same spec as focus scale):
   - Active: `width = 34.dp, height = 12.dp`
   - Inactive: `width = 12.dp, height = 12.dp`
   - Always `CircleShape`, `clip = true`.

8. Auto-advance timer. Use a `resetTick` counter so the timer resets on any user interaction,
   including pressing LEFT at index 0 (where `activeIndex` would not change):
   ```kotlin
   var resetTick by remember { mutableIntStateOf(0) }
   LaunchedEffect(activeIndex, resetTick) {
       delay(6_500L)
       activeIndex = (activeIndex + 1) % items.size
   }
   ```
   In `onLeft`: `if (activeIndex > 0) activeIndex-- else resetTick++`
   In `onRight`: `if (activeIndex < items.lastIndex) activeIndex++ else resetTick++`

   **Why not `LaunchedEffect(activeIndex, userInteracted)` with `userInteracted = false` at the
   end?** That pattern causes an infinite coroutine loop: setting `userInteracted = false` inside
   the effect body changes a key, cancels the current effect, relaunches it, which sets the key
   again — an unbounded cascade of coroutine launches consuming resources. The `resetTick` approach
   avoids mutation of any key inside the effect body.

9. Backdrop alignment: use `BiasAlignment(0f, -0.25f)` as the `alignment` parameter on
   `RemoteImage` / `AsyncImage`. This biases the image 25 % above center vertically (faces
   live in the upper half of most backdrops) while keeping it horizontally centered.
   `BiasAlignment` is in `androidx.compose.ui.Alignment`; `-0.0f` and `0f` are identical in
   IEEE 754 so always write `0f` for the horizontal bias.

### 3. Tile

1. Sizes:
   - `POSTER_W = 210.dp`, `POSTER_H = 315.dp`
   - `LANDSCAPE_W = 360.dp`, `LANDSCAPE_H = 202.dp`
2. Corner radius: `RoundedCornerShape(10.dp)` → `RoundedCornerShape(colors.tileRadius)`.
3. Fallback (no poster URL): replace flat `surfaceVariant` box with a deterministic gradient:
   ```kotlin
   val fallbackGradient = remember(title) {
       val h = title.hashCode()
       val hue1 = (h and 0x7FFFFFFF) % 360
       val hue2 = (hue1 + 40 + ((h ushr 8) and 0x3F)) % 360
       Brush.linearGradient(
           listOf(
               Color.hsl(hue1.toFloat(), saturation = 0.55f, lightness = 0.28f),
               Color.hsl(hue2.toFloat(), saturation = 0.45f, lightness = 0.20f),
           ),
           start = Offset(0f, Float.POSITIVE_INFINITY),
           end   = Offset(Float.POSITIVE_INFINITY, 0f),
       )
   }
   ```
   Draw the gradient box, center `Text(title, SpaceGrotesk 700 24sp white)` with a
   `Shadow(Color.Black.copy(0.6f), blurRadius = 16f)`.

   **Why HSL, not bit-masking?** Bit-masking a `hashCode()` against `0x5F5F7F` caps R/G at 95
   and B at 127, producing colors that may be near-black on the `#0A0C13` background. HSL with
   fixed saturation (45–55 %) and lightness (20–28 %) guarantees every title gets a visible,
   consistently dark-but-not-invisible gradient regardless of hash distribution.

   **Why `Float.POSITIVE_INFINITY` for offsets?** `Brush.linearGradient` start/end coordinates
   are in screen **pixels**, not dp. `Offset(500f, 800f)` would be wrong on any display density
   other than mdpi. `Float.POSITIVE_INFINITY` is the Compose idiom for "fill the bounding box" —
   the gradient engine clamps infinity to the actual draw bounds at draw time, making it
   density-independent without needing a `DrawScope`.
4. Label: `12.sp` → `18.sp`, `fontFamily = Sora`, `FontWeight.Medium`.
5. Subtitle (optional second line): if `subtitle` param provided (year or genre string),
   show below label at `15.sp`, `colors.textDim`, `maxLines = 1`.
6. Spacing between art and label: `6.dp` → `12.dp`.
7. "NEW" badge: change background from flat `colors.badgeNew` to `colors.accentGradient` brush
   applied via `Modifier.background(brush = colors.accentGradient, shape = ...)`.
8. Watched badge: change from rounded rect to **circle** — `size(28.dp)`, `CircleShape`,
   `colors.badgeWatched` background, white `✓` at `14.sp FontWeight.ExtraBold` centered.
9. Focus shadow: change `ambientColor = colors.focusRing, spotColor = colors.focusRing` →
   `ambientColor = colors.focusGlow, spotColor = colors.focusGlow`.

### 4. Channel card

1. Size: `120×70.dp` → `268×150.dp`.
2. Corner radius: `10.dp` → `18.dp`.
3. Background: replace flat `surfaceVariant` with a branded gradient derived from `brandColor`.
   Wrap in `remember(brandColor, colors.accent, colors.card)` — Brush objects are created once
   per channel and cached:
   ```kotlin
   val gradient = remember(brandColor, colors.accent, colors.card) {
       val base = if (brandColor != null) Color(brandColor) else colors.accent
       val dark = base.copy(alpha = 0.55f).compositeOver(colors.card)
       Brush.linearGradient(
           listOf(dark, base),
           start = Offset(0f, Float.POSITIVE_INFINITY),   // bottom-left
           end   = Offset(Float.POSITIVE_INFINITY, 0f),   // top-right
       )
   }
   ```
   `Offset(Float.POSITIVE_INFINITY, ...)` is the Compose idiom for density-independent diagonal
   gradients — the engine clamps to the actual draw bounds at draw time. Never hardcode pixel
   values like `Offset(400f, 300f)` in a brush created outside a `DrawScope`.

4. Sheen overlay (always): radial highlight in the top-right corner. Use `Float.POSITIVE_INFINITY`
   for both center x and radius to make the sheen position density-independent:
   ```kotlin
   val sheenBrush = remember {
       Brush.radialGradient(
           listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
           center = Offset(Float.POSITIVE_INFINITY, 0f),  // top-right corner
           radius = Float.POSITIVE_INFINITY,              // scale to bounding box
       )
   }
   Box(Modifier.matchParentSize().background(sheenBrush))
   ```
5. When `logoUrl == null`: render channel name as **watermark text** centered,
   `SpaceGrotesk Bold 38sp`, `Color.White.copy(alpha = 0.85f)`, padding 16dp, max 2 lines.
   Remove the below-card name label (it's now inside the card).
6. Focus shadow: use `colors.focusGlow` for `ambientColor` and `spotColor`.

### 5. Episode card

1. Card width: `320.dp` → `392.dp`.
2. Still: replace `120×68.dp` thumbnail with a **full-width 16:9 box**:
   ```kotlin
   Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
       RemoteImage(url = ep.stillUrl, ...)
       // overlays below
   }
   ```
3. Episode number overlay (top-left of still):
   ```kotlin
   Text("${ep.number}", Modifier.align(TopStart).padding(14.dp, 8.dp),
        color = Color.White, fontSize = 40.sp, fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Bold,
        style = TextStyle(shadow = Shadow(Color.Black.copy(0.7f), blurRadius = 12f)))
   ```
4. Duration badge (top-right):
   ```kotlin
   Box(Modifier.align(TopEnd).padding(12.dp)
       .background(Color.Black.copy(0.55f), RoundedCornerShape(7.dp))
       .padding(horizontal = 9.dp, vertical = 3.dp)) {
       Text("${ep.runtimeMin}m", color = Color.White, fontSize = 14.sp, fontFamily = Sora)
   }
   ```
5. Progress bar: move from below-card to overlaid at bottom of still (4dp tall, full width),
   matching the Tile progress bar treatment.
6. "Up Next" ribbon (bottom-left, shown when `ep.isUpNext`):
   ```kotlin
   Box(Modifier.align(BottomStart).padding(12.dp)
       .background(colors.accentGradient, RoundedCornerShape(7.dp))
       .padding(horizontal = 10.dp, vertical = 4.dp)) {
       Text("UP NEXT", color = Color.White, fontSize = 13.sp, fontFamily = Sora,
            fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
   }
   ```
7. Watched indicator (bottom-right, shown when `ep.watched`):
   ```kotlin
   Box(Modifier.align(BottomEnd).padding(12.dp).size(30.dp)
       .background(colors.badgeWatched, CircleShape)) {
       Text("✓", Modifier.align(Center),
            color = Color(0xFF04281A), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
   }
   ```
8. Play button overlay (visible only while focused; fades in with `animateFloatAsState`):
   ```kotlin
   val playAlpha by animateFloatAsState(if (focused) 1f else 0f)
   Box(Modifier.align(Center).size(60.dp).alpha(playAlpha)
       .background(Color.Black.copy(0.5f), CircleShape)
       .border(2.dp, Color.White.copy(0.75f), CircleShape)) {
       Text("▶", Modifier.align(Center), color = Color.White, fontSize = 23.sp)
   }
   ```
9. Watched episode opacity: when `ep.watched`, wrap the `RemoteImage` in `alpha(0.62f)`.
10. Below-still text: episode title `20.sp Sora 600`, overview `16.sp Sora textDim` max 2 lines.

### 6. Row headers

Update every `StaticContentRow` (and inline headers in detail screens) to:

```kotlin
Row(Modifier.fillMaxWidth().padding(horizontal = RaviloDimens.sectionPadH)
    .padding(bottom = RaviloDimens.rowHeadPadB)) {
    Text(
        text = title,
        color = colors.text,
        fontSize = 29.sp,
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
        modifier = Modifier.weight(1f),
    )
    if (onSeeAll != null) {
        Text(
            text = "See all",
            color = colors.textDim,
            fontSize = 16.sp,
            fontFamily = Sora,
            modifier = Modifier.clickable(onClick = onSeeAll),
        )
    }
}
```

### 7. Primary button

1. Height: use `heightIn(min = 60.dp)`.
2. Horizontal padding: `24.dp` → `30.dp`.
3. Corner radius: `8.dp` → `12.dp`.
4. Font: `21.sp`, `fontFamily = Sora`, `FontWeight.SemiBold`.
5. Focus lift: `graphicsLayer { translationY }` operates in screen **pixels**, not dp. Convert
   the target displacement before animating:
   ```kotlin
   val density = LocalDensity.current
   val liftTargetPx = remember(density) { with(density) { 4.dp.toPx() } }
   val lift by animateFloatAsState(-liftTargetPx, focusSpec)  // negative = up
   Modifier.graphicsLayer { translationY = if (focused) lift else 0f }
   ```
   Without the `toPx()` conversion, `-4f` means 4 screen pixels — about 1.5 dp on a 1080p TV
   and nearly invisible on 4K or higher-density panels.

### 8. Detail hero

1. Hero band height in `MovieDetailScreen` / `SeriesDetailScreen`: `420.dp` → `620.dp`.
2. Title: `36.sp` → `72.sp`, `SpaceGrotesk Bold`, `letterSpacing = (-2).sp`.
3. Synopsis: `15.sp` → `20.sp Sora`, `lineHeight = 30.sp`, `maxLines = 3`.
4. Body inset: `padding(40.dp)` → `padding(start = RaviloDimens.heroBodyStart, bottom = RaviloDimens.detailBodyBot)`.

## Invariants

- No change to focus wiring, D-pad callbacks, `FocusRow` usage, or navigation.
- No change to DTO models or API calls.
- All new overlay composables inside `EpisodeCard` and `HeroCarousel` must use `remember`/
  `rememberUpdatedState` to avoid capturing stale lambdas.
- `fallbackGradient` and channel `gradient` brushes must be inside `remember(key)` — never
  created inline during composition.
- `Brush.linearGradient` for `accentGradient` does not need `remember` if called from an
  extension property; callers must wrap in `remember(colors)` if materializing inline.
- **LazyRow vertical padding for scale overflow:** tiles scale to 1.10×, which overflows ~16 dp
  above and below their measured bounds. Every `LazyRow` containing focusable tiles must use
  `contentPadding = PaddingValues(horizontal = RaviloDimens.trackPadH, vertical = 24.dp)` (or
  more) so that the scaled content is not clipped by the row's scroll container bounds. The
  existing `trackPadV = 28.dp` value already satisfies this if applied consistently. Do not use
  a zero-padding `LazyRow` for any row containing `Tile`, `ChannelCard`, or `EpisodeCard`.
- **Brush pixel coordinates:** all `Brush.linearGradient` and `Brush.radialGradient` start/end/
  center values are in screen pixels. Use `Float.POSITIVE_INFINITY` for density-independent
  diagonal or edge-anchored gradients. Never hardcode `dp` values as raw floats in brush offsets.

## Out of scope

- Loading skeleton screens (R25).
- Focus glow shadow fix (R25 — applied uniformly across all components in one pass).
- Search screen keyboard resizing (R25).
- Player UI chrome resizing (separate player phase).
