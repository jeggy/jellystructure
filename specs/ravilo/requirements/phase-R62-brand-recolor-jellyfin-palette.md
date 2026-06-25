# Phase R62 — Brand recolor: Jellyfin-inspired palette + "D · Lit mark" asset regen (FR-RV-B1)

Recolor the Ravilo brand to a **Jellyfin-inspired palette** and regenerate the entire Android
asset pack in the **"D · Lit mark"** treatment. The jellyfish **mark shape is unchanged** — only
the colours and the backlit presentation change.

## Palette

| Token | Value | Role |
|-------|-------|------|
| Gradient start | `#AA5CC3` | jellyfish bell/tentacle gradient, wordmark underline, accents |
| Gradient end | `#00A4DC` | gradient terminus (Jellyfin blue) |
| Brand background | `#000B25` | the navy field behind the mark (icons, splash, banners) |

Gradient is a 120°/diagonal `#AA5CC3 → #00A4DC`. Replaces the old Aurora brand pair
(`#7B6EF0 → #3FB6F5` on `#0A0C13`). This is the **brand** palette; the in-app **Aurora UI
accent** (focus rings, buttons, dots) is a separate token set and is **not** changed by this
phase — a later phase can align the in-app theme if desired.

## "D · Lit mark" treatment

Chosen from four explored options (`design/ravilo/Logo Options.html`): **flat `#000B25` navy
field with a faint outer glow on the mark itself** ("backlit"), rather than a radial/aura
background. The glow is a soft purple-blue halo (`rgba(120,108,225,.55)`) bled around the
mark's alpha; the navy stays flat.

## Vector masters (source of truth)

`design/ravilo/assets/brand/ravilo-mark.svg` and `ravilo-lockup.svg` carry the new
`#AA5CC3 → #00A4DC` gradient (`<linearGradient id="rg">`). All raster exports regenerate from
these. The jellyfish path geometry and the lockup composition are unchanged.

## Regenerated Android asset pack (`design/ravilo/assets/android/**`, `…/store/**`)

All PNGs re-exported from the mark in the new palette + lit treatment:

- **Adaptive launcher icon** (`mipmap-anydpi-v26/`, 432²): `ic_launcher_background.png` = flat
  `#000B25`; `ic_launcher_foreground.png` = lit mark in the safe zone; `ic_launcher_monochrome.png`
  = white silhouette for API-33+ themed icons.
- **Legacy density mipmaps** (`mipmap-{m,h,xh,xxh,xxxh}dpi/`): `ic_launcher.png` +
  `ic_launcher_round.png`, full-bleed navy + lit mark, 48/72/96/144/192 px.
- **Leanback banner** (`drawable-xhdpi/banner.png`, 320×180) — mark + "Ravilo" wordmark.
- **Splash logo** (`drawable/splash_logo.png`, 432² transparent) — lit mark; window splash
  background is `#000B25`.
- **Play Store** (`store/`): `ic_launcher-512.png` (512²), `feature-graphic-1024x500.png`,
  `tv-banner-1280x720.png` — mark + Space Grotesk wordmark + gradient underline on the navy field.

## Manifest / theme implication

The Android-12+ splash background token changes to `#000B25`
(`windowSplashScreenBackground`). No structural manifest change — same `adaptive-icon`,
`android:banner`, `android:roundIcon` wiring (the R-series packaging phase is unchanged); only
the artwork + the one splash colour value are updated.

## Scope / invariants

- **Mark shape unchanged** — recolor + lit presentation only.
- Vector masters are the single source; every PNG regenerates from them.
- Brand palette only; the in-app Aurora UI accent token set is untouched here.
- The Danish-TV channel logo (`assets/brand/dansk-tv-logo.svg`) is a separate asset, not Ravilo
  branding — not affected.

## Mockup

`design/ravilo/Ravilo - Android TV Assets.html` (asset-pack showcase, recolored + copy updated),
`design/ravilo/assets/brand/*.svg` (masters), `design/ravilo/assets/android/**` +
`design/ravilo/assets/store/**` (regenerated PNGs). Decision record:
`design/ravilo/Logo Options.html`.
