# Phase R23 — Design system foundations (FR-RV23)


## What was built

### Font bundling
`Sora` (UI) and `Space Grotesk` (display/headings) bundled as `Compose Multiplatform` resources
(`composeResources/font/`). `JetBrains Mono` used for IDs/timestamps via `FontFamily.Monospace`.
`SpaceGrotesk` and `Sora` exposed as `FontFamily` vals from the theme package.

### Color palette corrections
All three skins corrected against the design prototype:
- **Aurora** (default): purple → blue accent gradient, `#0A0C13` background.
- **Midnight**: deep navy, cool blue accents.
- **Noir**: near-black, warm-gold `accentSecondary` (was incorrectly inheriting Aurora's purple).

New tokens added to `RaviloColors`: `accentSecondary`, `textDim`, `card`, `onAccent`, `focusGlow`.
`accentGradient: Brush` extension property on `RaviloColors`.

### Spacing constants
`RaviloDimens` object: `trackPadH = 48 dp`, `trackPadV = 20 dp`, `itemSpacing = 16 dp`,
`rowGap = 36 dp`, `rowHeadPadB = 10 dp`, `sectionPadH = 48 dp`. Used consistently across all
`ContentRow`, detail, and settings layouts.
