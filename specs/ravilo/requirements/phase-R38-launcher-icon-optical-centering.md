# Phase R38 — Launcher-icon optical centering

top-heavy (dome) with a thin trailing tail (tentacles), so it reads as sitting low in the Android TV
Apps overview. Nudge the mark up to its **optical** centre and regenerate the icon pack._

> Follow-up to **[R37](phase-R37-brand-mark-centering.md)**. R37's inline marks (config header, app bar)
> look correct; this refines **only the raster launcher/store icons**. The master SVG
> (`ravilo-mark.svg`) and the inline SVG marks are unchanged.

## Problem
The Android TV "Apps" overview shows the app icon (adaptive `ic_launcher`) with the jellyfish a bit too
far down. The glyph's visual mass — the dome/cap — occupies the top ~45% of the art, while the four thin
tentacles extend downward and add height with little visual weight. R37's regen composited the mark
`-gravity center` (geometric centring), which puts the heavy dome **above** the geometric centre, so the
mark reads as low/heavy in a standalone square icon. (The inline header mark looks fine because it's
small and flex-aligned beside the wordmark; a standalone icon exposes the imbalance.)

## Goal
The app icon's mark looks **optically centred** in the Apps overview and everywhere the launcher/store
icons appear — balanced visual weight, not geometrically centred.

## Requirements
### A. Optical offset (compositing only)
1. Composite the brand mark with a small **upward vertical offset** instead of pure `-gravity center`.
   Start at ~**6–10% of canvas height** and **tune by eye on the TV** until the dome's perceived centre
   sits at the icon's centre.
2. The master SVG path data and `viewBox` (`12 20 76 76`) stay **unchanged** (R37 invariant) — this is a
   placement parameter in the asset-generation step, not a redesign.

### B. Regenerate the affected icons (both trees)
Re-derive with the optical offset into `design/ravilo/assets/**` **and** `ravilo-android/src/main/res/**`:
1. Adaptive `ic_launcher_foreground` + `ic_launcher_monochrome` (432).
2. Legacy `ic_launcher` + `ic_launcher_round` at every density (mdpi–xxxhdpi).
3. Store `ic_launcher-512`.
4. `splash_logo` (re-evaluate; its extra padding may already read fine).
   The horizontal **banner / feature-graphic / TV-banner** lockups are left-aligned mark + wordmark, so
   the vertical imbalance is far less visible — verify, adjust only if needed.

### C. Verify on-device
Install to a TV and confirm the icon in the **Apps overview** (across adaptive masks: circle, squircle,
rounded-square) reads centred. The overview uses the adaptive `ic_launcher`
(`mipmap-anydpi-v26/ic_launcher.xml` → foreground/background); the leanback launcher row may use
`android:banner`.

## Invariants
- Master SVG paths + viewBox unchanged; inline marks unchanged. Only the raster compositing offset moves.
- One offset applied consistently across the icon pack so all icons share the same optical centre.

## Out of scope
- Any change to the jellyfish shape, gradient, or the inline header/app-bar marks (R37 — correct).
- New densities/sizes beyond R22/R37.

## Design reference
The R37 asset-gen step (headless-Chromium mark render + ImageMagick composite); master
`design/ravilo/assets/brand/ravilo-mark.svg`; `ravilo-android/src/main/AndroidManifest.xml`
(`android:icon` / `roundIcon` / `banner`).
