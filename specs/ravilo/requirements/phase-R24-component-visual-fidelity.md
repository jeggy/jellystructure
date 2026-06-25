# Phase R24 — Component upsizing & visual fidelity (FR-RV24)


## What was built

All core UI components resized to match the design prototype and feel correct at TV viewing distance.

### AppBar
Height raised to 92 dp. Focused nav item uses inverted (white background, black text) treatment
rather than an underline. Gradient overlay on the hero ensures legibility.

### Hero carousel
Height 600 dp. Title 72 sp `SpaceGrotesk Bold`. Kicker text (show/season label) above the title
in `accentSecondary`. Dual gradient: left-edge content vignette + bottom fade.
`HeroCarousel` auto-advances every N seconds (configurable via `RaviloConfig.autoAdvanceSeconds`).

### Tile
Full-size poster. When no image loads, renders a branded gradient placeholder with a short title
overlay. Aspect ratio enforced (2:3 portrait / 16:9 landscape). Progress bar at the bottom for
continue-watching tiles.

### Channel card
268×150 dp. Branded gradient background derived from `Channel.brandColor`. Logo centered. Name
below in 14 sp.

### Episode card
Full-width still image with overlaid episode number, title, and duration. Watched checkmark badge.
Progress bar on the still.

### Row headers
29 sp `SpaceGrotesk SemiBold` with optional "See all →" right-aligned.

### Buttons (`RaviloButton`)
Standard height 60 dp. Two variants: `PRIMARY` (accent fill) and `SECONDARY` (outline).
