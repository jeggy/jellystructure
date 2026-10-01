# Phase R341 — The app icon is sized per platform, and the favicon is the bare jellyfish

> Owner, 2026-10-01, after exploring a flat icon: *"Q1: No, let's keep the gradient. Q2: We are keeping R0."* On size:
> *"There is too much empty space between the jellyfish and the spacing around it."* Then: *"On android it seems
> perfect. But on Macosx it seems too little"* and *"I would like to make the macosx even bigger, while not making the
> android bigger."* On favicons: *"Ravilo should be bare, while Jellystructure is not bare"*. In-app: *"Today — theme
> gradient."*

## Status

`Planned`. Written 2026-10-01 (design-authored) from `design/App Icons - Flat Directions.html`. Not dev-reviewed.
**Numbering:** checked against `main` (tree `f88c706e`) the same day. Ravilo is taken through **R340** (R336 is reserved
by R335).

**Changes:**
- R313 FR-R313-1 and FR-R313-2: the tab icon loses its tile.
- R263 FR-R263-4: only the 180 px `apple-touch-icon` is re-rendered.
- R328 FR-R328-2: the Mac `.icns` is re-rendered.
- R333 FR-R333-4: the Flatpak's 512 px icon is re-rendered.

The admin's side is Phase 291.

## Decisions

| # | Question | Decision |
|---|---|---|
| D1 | Remove the gradient? | **No.** The R62 "lit mark" stays: the gradient `#AA5CC3 → #00A4DC`, the glow, and the `#000B25` navy tile. No colour changes anywhere. |
| D2 | One jellyfish size everywhere? | **No, the size depends on the platform** (FR-R341-1). The drawing stays the same; only its size on the tile differs. |
| D3 | A bare favicon? | **Yes, for Ravilo**: the jellyfish with no tile, in its gradient, redrawn for small sizes (FR-R341-3). The admin keeps its tile (291). |
| D4 | The logo inside the apps | **Unchanged**: the bare mark in the theme's `--accent → --accent-2` gradient. |

## Requirements

**FR-R341-1 — The jellyfish's size on the tile, per platform.** The size is measured as the bell's width (master
x 22–78) divided by the side of the visible tile, and the jellyfish is always **centred**:

| Where | Size | On a 100-unit tile |
|---|---|---|
| **macOS** (the `.icns` / Icon Composer file), **iPhone** (`apple-touch-icon-180.png`), **Linux** (the Flatpak icon) | **about 66 %** | `translate(-9 -20.21) scale(1.18)` of the 76-unit master (`viewBox="12 20 76 76"`) |
| **Everything else**: Android launcher (mipmaps, round, adaptive foreground, monochrome), the web manifest's 192/512/maskable/monochrome, the Play 512 icon, the feature graphic, the TV banner, the Samsung icon | **today's size, unchanged** (about 46 %) | unchanged |

- **Why 66 % on these three:** macOS, iOS and Linux don't crop the tile any further, and the apps beside Ravilo in a
  Dock fill about 60–70 % of their tiles.
- **Why Android stays:** Android crops every launcher icon to a circle or squircle, and the mark must stay inside its
  safe circle. Today's size already looks right there (owner).
- **"The tile" means the visible rounded square**, whatever canvas the toolchain uses. On macOS that is the area inside
  the system's squircle.

**FR-R341-2 — Re-render only what changes.** The outputs to re-render:
- `ravilo-desktop/icons/ravilo.icns`
- the Flatpak's 512 px icon
- `apple-touch-icon-180.png`

Each is rendered from `design/ravilo/assets/brand/ravilo-mark.svg` at FR-R341-1's placement, with the glow kept.

Nothing on Android, the Play listing or the TVs is touched. The master SVG itself is unchanged: placement is a property
of each output, not of the drawing.

**FR-R341-3 — The tab icon is the bare jellyfish, drawn for small sizes.**
- **`favicon.svg`** (R313) becomes a transparent SVG with **no tile**: a small-size drawing of the same jellyfish in
  the same gradient.
- **The drawing:** a fuller bell and three heavier tentacles. At 16 px the master's four 4.5-unit tentacles merge into
  one smudge (see the loupes in the mockup's §3).
- **`favicon.ico`** (16 + 32 px) is rendered from the same SVG.
- **The `icon-192.png` fallback link stays as it is.**

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="14 24 72 72">
  <defs><linearGradient id="rg" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#AA5CC3"/><stop offset="1" stop-color="#00A4DC"/></linearGradient></defs>
  <path d="M18 56 C18 21 82 21 82 56 C70 49 60 49 50 53 C40 49 30 49 18 56 Z" fill="url(#rg)"/>
  <g stroke="url(#rg)" stroke-width="9" stroke-linecap="round" fill="none">
    <path d="M32 56 Q27 71 33 86"/><path d="M50 58 L50 88"/><path d="M68 56 Q73 71 67 86"/>
  </g>
</svg>
```

The small drawing is used **only for 32 px and below**. Anything larger uses the master, which has no tile in this
case either.

**FR-R341-4 — Contrast on both tab strips.** Without the navy tile, the gradient itself must be readable on a light
and on a dark tab strip.
- **Measured on Chrome's strips:** the purple end is about 4 : 1 on both. The blue end is about 3 : 1 on the light
  strip and better on the dark one.
- **Required:** 3 : 1 is the minimum for a non-text graphic, so check it against Safari's and Firefox's strips too.
- **If a strip falls below 3 : 1,** the fix is a darker blue stop **in the favicon only**, never in the app icon.

**FR-R341-5 — The logo inside the apps is unchanged.** The bare mark beside the wordmark keeps the theme's
`--accent → --accent-2` gradient in all five themes (R338). This phase changes nothing in `ravilo-ui`.

## Non-goals

- Any colour change, or a flat or monochrome redesign. The owner explored this and declined it.
- The admin's icon (Phase 291).
- The Mac's music-mode icon (Phase R342). It builds on FR-R341-1's Mac size.

## Acceptance

1. **The Mac Dock:** the jellyfish fills about two-thirds of the tile, centred, with the gradient and the glow.
   Android's launcher icon is pixel-identical to before.
2. **The iPhone home screen** (Add to Home Screen): the jellyfish is the larger size. An installed Android web app is
   unchanged.
3. **A desktop browser tab**, light and dark: the jellyfish has no navy square, and its bell and three tentacles are
   distinct at 16 px.
4. The web app's `/favicon.ico` answers 200 and shows the same bare drawing.
5. Every theme's in-app logo looks as before.

## Open questions (for the dev review)

The code review comes later. These are left for it to answer, against the real code and the real toolchain.

1. **Contrast:** FR-R341-4's contrast on Safari's and Firefox's light tab strips has only been estimated, not measured. Measure both. If either falls below 3 : 1, darken the favicon's blue stop.
2. **The macOS canvas:** where is the Mac's visible tile on the `.icns` canvas? The Mac icon template insets the rounded square inside the canvas. Is FR-R341-1's 66 % measured against that square in `ravilo-desktop/icons/`'s build script, or against the full canvas?
3. **apple-touch:** does iOS's own corner mask on `apple-touch-icon-180.png` crop the larger jellyfish? 264's admin version sits on an opaque ground with the tile inset for this reason. Does Ravilo's file need the same?
4. **The Flatpak icon:** GNOME draws app icons with no tile of their own. Should the Flatpak's 512 px PNG keep the navy tile at 66 %, or go bare? The lean is to keep the tile, since it's an app icon, not a favicon. Check this against Flathub's icon guidelines.
5. **The renderer:** R313 and 264 rasterised once in headless Chromium and committed the results. Should these re-renders use the same script, so the Mac, iPhone and Linux outputs have one source?
