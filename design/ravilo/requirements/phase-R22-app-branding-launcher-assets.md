# Phase R22 — App branding, launcher & store asset pack (FR-RV22)

**Status:** Planned · _produce the production brand assets the Android TV APK/AAB (and web) build
consumes, and wire them into packaging._

## Problem
R01–R21 brought the app to running-on-hardware, but the build still ships with placeholder app
branding. To produce a **store-ready Android TV APK/AAB** the build needs a complete set of
resolution-correct branding assets — an adaptive launcher icon, the **leanback TV banner**, the
Android 12+ splash logo, and Play Store listing graphics — all generated from the finalized brand
mark. R17 §7 named the packaging target ("AAB for Android TV, leanback banner/metadata correct");
this phase produces the **assets** that requirement assumed and locks the brand source of truth.

## Current state (as-is)
- Brand is **finalized**: the stylised **jellyfish mark ("logo 10")** + the **Ravilo** wordmark
  (gradient `o` + motion underline), per the constitution's Visual System. Aurora accent
  (`#7b6ef0 → #3fb6f5`), Space Grotesk display.
- Design source lives in `design/ravilo/`. The rendered asset pack + reference sheet for this phase
  is `design/ravilo/Ravilo — Android TV Assets.html`, with exported PNGs under
  `design/ravilo/assets/android/**` and `design/ravilo/assets/store/**`, and vector masters in
  `design/ravilo/assets/brand/**`.

## Requirements

### Brand source of truth
1. **Vector masters** are authoritative: `ravilo-mark.svg` (jellyfish) and `ravilo-lockup.svg`
   (mark + wordmark). All raster exports derive from these. Gradient and type per the constitution;
   the mark **must not** resemble or reuse Jellyfin's own logo.
2. Launcher/store assets ship in the **Aurora** accent only. **Skins (Midnight/Noir) are in-app
   surfaces, not launcher branding** — there is exactly one app icon and one banner.

### Android TV app resources (`:ravilo-android` `res/`)
3. **Adaptive launcher icon** (108dp): `ic_launcher_foreground` (mark, inside the 66dp safe zone),
   `ic_launcher_background` (brand field), and `ic_launcher_monochrome` (Android 13+ themed icons).
   Declared in `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml`.
4. **Legacy mipmaps** for `ic_launcher` + `ic_launcher_round` at every density
   (mdpi 48 · hdpi 72 · xhdpi 96 · xxhdpi 144 · xxxhdpi 192 px).
5. **Leanback banner** `banner.png` **320×180** in `drawable-xhdpi/`, wired via `android:banner`
   on `<application>` **and** the `LEANBACK_LAUNCHER` activity (TV launcher requirement).
6. **Android 12+ splash** (`androidx.core.splashscreen`): `windowSplashScreenAnimatedIcon =
   @drawable/splash_logo` (transparent, padded), `windowSplashScreenBackground = @color/brand_bg`
   (`#0a0c13`), `postSplashScreenTheme` = app theme.

### Store + web
7. **Play Store**: hi-res icon **512×512**, **Android TV banner 1280×720**, feature graphic
   **1024×500** — all from the same master.
8. **Web (`:ravilo-web`)**: favicon (SVG + ICO), PWA `manifest` icons (192/512, maskable), and an
   `apple-touch-icon` (180) generated from `ravilo-mark.svg`.

### Packaging wiring
9. `AndroidManifest.xml` declares the launcher category `LEANBACK_LAUNCHER` (+ optional
   `android.software.leanback` `required="false"` for sideways phone installs) and references the
   banner/icon resources above. `tvBanner`/`isGame=false` metadata correct. Verify the icon renders
   in the Android TV "Apps" row and the Google TV launcher on real hardware.

## Invariants
- One app identity: **single** Aurora-accent launcher icon + banner; skins never change app branding.
- The brand mark is the finalized **jellyfish ("logo 10")**; **never** Jellyfin's logo.
- Gradient (`#7b6ef0 → #3fb6f5`) and type (Space Grotesk) match the constitution's Visual System.
- All raster assets are regenerable from the SVG masters (no hand-painted one-offs).

## Out of scope
- Animated / Lottie splash; per-skin or seasonal launcher icons; iOS app icon; Play Store
  screenshots and listing copy (marketing task, not a build asset).
