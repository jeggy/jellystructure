# R51 — Ravilo TV: brand-mark logo in the app bar (FR-RB1)

**Status:** ✅ Done — new `components/BrandMark.kt` vector-draws the jellyfish mark (dome + 4 tentacles,
from `ravilo-mark.svg`) tinted with the skin's `accentGradient`; `AppBar` now shows the mark + an **ink**
wordmark (was accent-purple text).
**Depends on:** R09 (design system), R23/R37 (brand mark + fonts)

## Goal

The TV app bar's top-left "logo" is currently just the word **Ravilo** rendered in the **accent
(purple) colour** as plain text. Replace it with the real brand **lockup** from the design files: the
gradient **jellyfish mark** + the **Ravilo wordmark** — matching `design/ravilo` and the constitution's
brand rule ("a stylised jellyfish + the 'Ravilo' wordmark; the mark tints with the active skin's accent").

## Current state

`ravilo-ui/.../components/AppBar.kt` renders only:
```kotlin
Text("Ravilo", color = colors.accent, fontSize = 24.sp, fontWeight = Bold, fontFamily = SpaceGrotesk, letterSpacing = -1.sp)
```
No mark; the wordmark is tinted accent (the "purple text").

## Target (from the design)

`design/ravilo/ravilo.css` `.brand`:
```
.brand     { display:flex; align-items:center; gap:14px; font-family:'Space Grotesk'; font-weight:700; font-size:31px; letter-spacing:-1px; }
.brand .mark { width:48px; height:48px; filter: drop-shadow(0 0 16px var(--ring-glow)); }   /* the gradient jellyfish */
.brand .wm   { color: var(--ink); }                                                          /* wordmark is INK, not purple */
```
- A **mark** to the left of the wordmark; the **wordmark is light (`--ink`)**, only the **mark** carries
  the purple→blue gradient + a soft glow.
- Brand asset: `design/ravilo/assets/brand/ravilo-mark.svg` (mark only) and
  `ravilo-lockup.svg` (mark + wordmark). The mark is a simple vector — `viewBox="12 20 76 76"`, one
  filled dome path + four stroked "tentacle" paths, gradient `#7b6ef0 → #3fb6f5` (R37 recentred it).

## Approach (implementation guidance, not prescriptive)

A small **`BrandMark`** composable in `:ravilo-ui` is the cleanest path and stays Android+Web identical:
- Draw the mark's paths with Compose `PathParser().parsePathString(...)` on a `Canvas`/vector, filled
  & stroked with the theme's **`RaviloColors.accentGradient`** brush (already defined,
  `theme/Colors.kt:107`) — so the mark **tints per skin** (Aurora purple→blue, Midnight teal, Noir gold)
  for free, with no raster/asset pipeline. (Alternative: bundle a vector in `composeResources/drawable`
  and `painterResource` it — but that won't auto-tint with the skin.)
- Keep the wordmark as a `Text` but recolour it to **`colors.text`** (ink), not `colors.accent`.
- Size the mark + wordmark to suit the **compact** bar from R52 (e.g. ~26–32 dp mark, ~22–24 sp wordmark)
  rather than the mockup's 48 px / 31 px (which is at full mockup scale).

## Non-goals / invariants

- **Shared UI** — the `BrandMark` lives in `:ravilo-ui` common; no Android-only artifact.
- **Skin-aware** — the mark uses the active skin's accent gradient (constitution brand rule).
- App-bar **layout/sizing** is R52; this phase only swaps the wordmark-text for the mark+wordmark lockup.

## Mockup

`design/ravilo/Ravilo TV.html` `.brand` (mark + `.wm`) + `design/ravilo/assets/brand/ravilo-mark.svg`
/ `ravilo-lockup.svg`. Launcher/splash already use the mark (R37 asset pack).
