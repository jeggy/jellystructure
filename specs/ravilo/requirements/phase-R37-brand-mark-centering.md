# Phase R37 — Brand-mark centering + asset-pack regeneration

**Status:** Planned · _the jellyfish brand mark was small and sat high inside its own viewBox, so every
launcher/store raster and every in-app inline copy inherited an off-centre, undersized glyph. Recentre
the **master SVG** (paths unchanged) and regenerate the whole asset pack from it; fix the in-app inline
marks and the config-header alignment._

> A correctness follow-up to **[R22](phase-R22-apk-packaging.md)** (TV banner, adaptive icon, splash)
> and the brand mark in [`../constitution.md`](../constitution.md) §Brand. No new product surface — it
> makes the existing brand assets correct and consistent. Source-of-truth rule from R22 holds: **all
> raster assets are regenerable from the SVG masters — no hand-painted one-offs.**

## Problem
The jellyfish art in the master `design/ravilo/assets/brand/ravilo-mark.svg` only occupied a small,
low region of its `0 0 100 100` viewBox (art bbox ≈ x[20,80], y[31,88]). Because every consumer renders
that viewBox, the mark came out **undersized and shifted up** everywhere: the adaptive launcher icon,
monochrome, splash, all mipmap densities, the store 512 icon and graphics, **and** the in-app inline
copies (the Ravilo TV app bar, the jellystructure config-page header, the Library "save filter" menu,
the create-flows). The Jellystructure **Ravilo config** page header showed the glyph floating high and
small next to the "Ravilo TV" title — the symptom the user flagged. Assets had also drifted out of sync
(a thin-stroke mark in some files, a different fill in others).

## Goal
One correctly-framed **master** mark, with **every** raster and inline copy regenerated from it so the
jellyfish is centred, optically sized, and identical across the launcher, store, splash and in-app
chrome.

## Requirements

### A. Recentre the master (shape unchanged)
1. In `ravilo-mark.svg`, **keep the path data byte-for-byte** and only **reframe the `viewBox`** so the
   art is tightly centred (`12 20 76 76` — symmetric ~10/11-unit margins around the art bbox). The
   gradient (`#7b6ef0 → #3fb6f5`) is unchanged. `ravilo-lockup.svg` (mark + wordmark + underline) is
   updated to use the recentred mark.
2. Because the shape is untouched, this is a **framing fix, not a redesign** — the brand mark in
   constitution §Brand is the same jellyfish; it just no longer carries dead space.

### B. Regenerate the whole pack from the master
All of `design/ravilo/assets/**` are re-derived from the corrected master (per the R22 "regenerable
from SVG masters" rule), so they share one centred, optically-sized glyph:
1. **Adaptive icon** — `ic_launcher_foreground` (mark in the 66dp safe zone), `ic_launcher_background`
   (brand field), `ic_launcher_monochrome` (white mark, themed-icon).
2. **Legacy mipmaps** — `ic_launcher` (rounded square) + `ic_launcher_round` at mdpi/hdpi/xhdpi/
   xxhdpi/xxxhdpi, brand field + centred mark.
3. **Splash** — `splash_logo` (transparent, extra padding).
4. **Store** — `ic_launcher-512` (rounded square), `feature-graphic-1024x500`, `tv-banner-1280x720`,
   and the leanback **`banner` 320×180** (`drawable-xhdpi`). Text-bearing graphics use the lockup
   (mark + "Ravilo" + the "CINEMATIC STREAMING FOR JELLYFIN" kicker on the two large ones), rendered in
   **Space Grotesk**.

### C. Fix the in-app inline marks
1. Every inline SVG copy of the mark uses the corrected `viewBox` (`12 20 76 76`): the **Ravilo TV** app
   bar, the **config-page header**, the **Library** "save filter" menu, and the **create-flows** mark.
2. The Jellystructure **config-page header** (`design/app/ravilo-config.html`) aligns the mark to the
   "Ravilo TV" wordmark with **flex centring** and an **`em`-sized** mark (tracks the cap height at any
   font size) on a single line — replacing the brittle fixed-px + `vertical-align: middle` that made it
   float high.

## Invariants
- **Path data is never edited** for centering — only the viewBox/frame and the size/placement at the
  consumer. The brand shape is preserved exactly (constitution §Brand).
- **One master, many derivations:** rasters are regenerated from `ravilo-mark.svg` /
  `ravilo-lockup.svg`; no hand-painted or independently-drawn icons (R22 rule).
- The mark **tints with the active skin's accent** in-app (constitution §Brand) — recentring does not
  change tinting; the static assets keep the Aurora gradient.

## Out of scope
- Any change to the jellyfish silhouette, stroke weight, tentacle count, or the gradient palette.
- New icon densities/sizes or store-listing copy beyond what R22 already defined.
- The skin-tint engine itself (unchanged).

## Design reference
Masters `design/ravilo/assets/brand/ravilo-mark.svg` + `ravilo-lockup.svg` (recentred viewBox);
regenerated `design/ravilo/assets/android/**` (adaptive, mipmaps, splash, leanback banner) and
`design/ravilo/assets/store/**` (512 icon, feature graphic, TV banner); the asset showcase
`design/ravilo/Ravilo - Android TV Assets.html`; and the in-app inline marks in
`design/ravilo/ravilo-app.js`, `design/app/ravilo-config.html` (header alignment), `design/app/library.html`,
and `design/app/ravilo-createflows.jsx`.
