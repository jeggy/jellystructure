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
   `letterSpacing = (-2).sp`, `lineHeight = 72.sp`.

6. Synopsis (new): render `active.overview` at `22.sp Sora`, `lineHeight = 33.sp`,
   `colors.textSecondary`, `maxLines = 2`, `overflow = Ellipsis`, with `Spacer(20.dp)` above.

7. Dots: animate dot width with `animateDpAsState` (spring, same spec as focus scale):
   - Active: `width = 34.dp, height = 12.dp`
   - Inactive: `width = 12.dp, height = 12.dp`
   - Always `CircleShape`, `clip = true`.

8. Auto-advance timer:
   ```kotlin
   var userInteracted by remember { mutableStateOf(false) }
   LaunchedEffect(activeIndex, userInteracted) {
       if (!userInteracted) {
           delay(6_500L)
           activeIndex = (activeIndex + 1) % items.size
       }
       userInteracted = false
   }
   ```
   Set `userInteracted = true` inside the `onLeft` / `onRight` handlers. The `LaunchedEffect`
   is cancelled and relaunched whenever `activeIndex` or `userInteracted` changes — zero polling.

9. Backdrop `Alignment`: use `Alignment(-0.0f, -0.25f)` to bias the image toward the upper-center
   portion (faces live in the upper half of most backdrop images).

### 3. Tile

1. Sizes:
   - `POSTER_W = 210.dp`, `POSTER_H = 315.dp`
   - `LANDSCAPE_W = 360.dp`, `LANDSCAPE_H = 202.dp`
2. Corner radius: `RoundedCornerShape(10.dp)` → `RoundedCornerShape(colors.tileRadius)`.
3. Fallback (no poster URL): replace flat `surfaceVariant` box with a deterministic gradient:
   ```kotlin
   val fallbackGradient = remember(title) {
       val h = title.hashCode()
       val c1 = Color(0xFF_00_00_00L or ((h and 0x5F5F7F).toLong()))
       val c2 = Color(0xFF_00_00_00L or (((h ushr 8) and 0x5F7F5F).toLong()))
       Brush.linearGradient(listOf(c1, c2), start = Offset.Zero, end = Offset(500f, 800f))
   }
   ```
   Draw the gradient box, center `Text(title, SpaceGrotesk 700 24sp white)` with a
   `Shadow(Color.Black.copy(0.6f), blurRadius = 16f)`.
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
3. Background: replace flat `surfaceVariant` with a branded gradient derived from `brandColor`:
   ```kotlin
   val base  = if (brandColor != null) Color(brandColor) else colors.accent
   val dark  = base.copy(alpha = 0.55f).compositeOver(colors.card)
   val gradient = Brush.linearGradient(listOf(dark, base), start = Offset(0f,0f), end = Offset(400f,300f))
   ```
4. Sheen overlay (always): radial highlight in the top-right corner:
   ```kotlin
   Box(Modifier.matchParentSize().background(
       Brush.radialGradient(
           listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
           center = Offset(268f * 0.85f, 0f),
           radius = 268f * 1.1f,
       )
   ))
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
5. Focus lift: add `val lift by animateFloatAsState(if (focused) -4f else 0f, focusSpec)` and
   apply `graphicsLayer { translationY = lift }` — creates the subtle upward nudge on focus.

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

## Out of scope

- Loading skeleton screens (R25).
- Focus glow shadow fix (R25 — applied uniformly across all components in one pass).
- Search screen keyboard resizing (R25).
- Player UI chrome resizing (separate player phase).
