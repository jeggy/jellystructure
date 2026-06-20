# Phase R23 — Design system foundations: typography, color tokens, spacing (FR-RV23)

**Status:** Planned

## Problem

The implementation that emerged from R09 omits the two most visible design system pillars:
**custom typography** and **accurate color tokens**. Every text element renders in Material3
defaults instead of the specified Space Grotesk (display) + Sora (UI) typefaces, making the
app look generic rather than branded. Color values for all three skins deviate from the
`ravilo.css` reference — Aurora background is `#06060F` vs the design's `#0A0C13`, the Noir
skin is a flat grayscale instead of the warm-gold palette, and several tokens that the design
relies on (accent-secondary, textDim, card surface, per-skin tile radius) are missing
entirely. Global spacing constants are hardcoded at 60–70 % of their designed values throughout
every component.

## Current state (as-is)

- `RaviloColors` has 15 tokens, all correct in structure but with wrong values.
- No `accentSecondary`, `textDim`, or `card` color tokens.
- No `tileRadius: Dp` per-skin value.
- No `RaviloDimensions` object — all spacing is hardcoded per-file.
- No font files bundled; `fontFamily` is never set on any `Text`.
- Noir skin: `accent = #E2E8F0` (near-white), `text = #E2E8F0` — effectively grayscale.
  Design specifies warm ivory text `#F7F3EA` and gold accent `#F5B542`.

## Requirements

### 1. Custom fonts

1. Bundle five TTF font files in `ravilo-ui/src/commonMain/composeResources/font/`:
   - `space_grotesk_semibold.ttf` (weight 600)
   - `space_grotesk_bold.ttf` (weight 700)
   - `sora_regular.ttf` (weight 400)
   - `sora_medium.ttf` (weight 500)
   - `sora_semibold.ttf` (weight 600)
   Both families are OFL-licensed; include license files in the same directory.

2. Create `ravilo-ui/src/commonMain/.../theme/Typography.kt` defining two `FontFamily` values:
   ```kotlin
   val SpaceGrotesk = FontFamily(
       Font(Res.font.space_grotesk_semibold, FontWeight.SemiBold),
       Font(Res.font.space_grotesk_bold,     FontWeight.Bold),
   )
   val Sora = FontFamily(
       Font(Res.font.sora_regular,   FontWeight.Normal),
       Font(Res.font.sora_medium,    FontWeight.Medium),
       Font(Res.font.sora_semibold,  FontWeight.SemiBold),
   )
   ```

3. Apply `fontFamily`:
   - **`SpaceGrotesk`** — all headlines, row section headers, hero title, detail title, channel
     card watermark text, episode number overlays, the "Ravilo" wordmark in `AppBar`.
   - **`Sora`** — all body text, nav items, metadata lines, synopsis, buttons, badge labels,
     episode descriptions, time display, search keyboard keys.
   - **`JetBrains Mono`** — pairing PIN code only (use `androidx.compose.ui.text.font.GenericFontFamily`
     monospace as the fallback family; the system monospace on Android TV is close enough).

4. Use `FontLoadingStrategy.Async` for both `FontFamily` declarations so the first composition
   frame is not blocked. A brief flash of the system fallback on cold start is acceptable.

### 2. Color token corrections

Update `Colors.kt` to fix all three skin palettes and add four new tokens to `RaviloColors`:

```kotlin
data class RaviloColors(
    // existing tokens — keep names unchanged
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val accent: Color,
    val accentDim: Color,
    val onAccent: Color,
    val text: Color,
    val textSecondary: Color,
    val focusRing: Color,
    val focusGlow: Color,
    val overlay: Color,
    val progressFill: Color,
    val progressBg: Color,
    val badgeWatched: Color,
    val badgeNew: Color,
    // new tokens
    val accentSecondary: Color,   // gradient end; kicker text; "NEW" badge gradient
    val textDim: Color,            // tertiary metadata, role text, "see all" links
    val card: Color,               // inner card surface (channel card bg base, episode card)
    val tileRadius: Dp,            // skin-specific corner radius for tiles and cards
)
```

**Aurora** (purple-blue cinematic):

| Token | Current | Correct |
|---|---|---|
| `background` | `#06060F` | `#0A0C13` |
| `surface` | `#0F0F1E` | `#161A28` |
| `surfaceVariant` | `#16162A` | `#1B2031` |
| `accent` | `#8B5CF6` | `#7B6EF0` |
| `accentSecondary` | — | `#3FB6F5` |
| `text` | `#E2E8F0` | `#F3F4FB` |
| `textSecondary` | `#94A3B8` | `#AEB4CB` |
| `textDim` | — | `#6B7290` |
| `card` | — | `#0E111B` |
| `badgeWatched` | `#10B981` | `#2DD49A` |
| `tileRadius` | — | `12.dp` |

**Midnight** (teal-blue deep sea):

| Token | Current | Correct |
|---|---|---|
| `background` | `#080C18` | `#04101A` |
| `surface` | `#0D1426` | `#0A1C28` |
| `surfaceVariant` | `#141D35` | `#0E2533` |
| `accent` | `#3B82F6` | `#19D6C6` |
| `accentSecondary` | — | `#2A8CF0` |
| `accentDim` | `#1D3A7A` | `#0A3D38` |
| `focusRing` | `#3B82F6` | `#28E6D6` |
| `focusGlow` | `#553B82F6` | `#8028E6D6` |
| `text` | `#E2E8F0` | `#EAFCFF` |
| `textSecondary` | `#94A3B8` | `#9FC2CF` |
| `textDim` | — | `#5A7D8A` |
| `card` | — | `#07151F` |
| `tileRadius` | — | `14.dp` |

**Noir** (warm-gold film grain — currently implemented as flat grayscale; full redesign):

| Token | Current | Correct |
|---|---|---|
| `background` | `#0A0A0A` | `#080807` |
| `surface` | `#141414` | `#16140F` |
| `surfaceVariant` | `#1E1E1E` | `#1F1C15` |
| `accent` | `#E2E8F0` | `#F5B542` |
| `accentSecondary` | — | `#E0792F` |
| `accentDim` | `#64748B` | `#7A5B1F` |
| `onAccent` | `#FFFFFF` | `#08070A` |
| `focusRing` | `#E2E8F0` | `#FFCF6B` |
| `focusGlow` | `#44E2E8F0` | `#80FFCF6B` |
| `text` | `#E2E8F0` | `#F7F3EA` |
| `textSecondary` | `#94A3B8` | `#C7BFAE` |
| `textDim` | — | `#807868` |
| `card` | — | `#0D0C0A` |
| `badgeNew` | `#94A3B8` | `#C79A3F` |
| `tileRadius` | — | `6.dp` |

### 3. Global spacing constants

Create `ravilo-ui/src/commonMain/.../theme/Dimens.kt`:

```kotlin
object RaviloDimens {
    val screenPadH   = 64.dp   // left/right padding on all screens
    val trackPadH    = 64.dp   // LazyRow contentPadding start/end
    val trackPadV    = 28.dp   // LazyRow contentPadding top/bottom
    val itemSpacing  = 22.dp   // gap between tiles / channel cards / cast circles
    val rowGap       = 52.dp   // vertical gap between content rows
    val heroBodyStart = 64.dp  // hero text column left inset
    val heroBodyBot   = 86.dp  // hero text column bottom inset
    val detailBodyBot = 44.dp  // detail page hero text bottom inset
    val rowHeadPadB  = 12.dp   // padding below row header before content
    val sectionPadH  = 64.dp   // horizontal padding for section headers on detail pages
}
```

Replace all hardcoded values with `RaviloDimens.*` throughout every component and screen that
currently uses `40.dp` for horizontal padding, `12.dp` for item spacing, or `28.dp` for row gaps.

### 4. Accent gradient helper

Add an extension property to `RaviloColors` for the brand gradient (accent → accentSecondary):
this is used by the NEW badge, Up Next ribbon, and any future gradient-tinted elements:

```kotlin
val RaviloColors.accentGradient: Brush
    get() = Brush.linearGradient(listOf(accent, accentSecondary))
```

## Invariants

- Font files must live in `commonMain/composeResources/font/` — no platform-specific font loading.
- Token names in `RaviloColors` must not change (callers reference them by name).
- `tileRadius` is the **only** per-skin measurement in `RaviloColors`; all other dp values live in
  `RaviloDimens` (screen-size invariant, not skin invariant).
- Both `FontFamily` objects are package-level `val`s in `Typography.kt` so all components can
  import them without going through the theme object.

## Out of scope

- Component resizing and visual redesign (R24).
- Loading skeletons (R25).
- Applying fonts to platform-specific code outside `:ravilo-ui`.
