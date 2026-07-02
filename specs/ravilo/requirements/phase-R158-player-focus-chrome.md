# Phase R158 — TV player: drop the opaque white fill on focused transport buttons (FR-RV-PC2)

## Problem
In the Android TV player, the **focused** transport control (next, skip, audio/subs, back) renders an
**opaque white rectangle behind its label** — a jarring "text background" that doesn't exist anywhere
else in the app's focus language (everywhere else focus = accent ring + glow + draw-only scale).

## Root cause (verified in code)
The player's bespoke buttons flip their fill to solid white on focus:
- `SkipButton` — `PlayerScreen.kt:900`:
  `.background(if (focused) Color.White else Color.White.copy(alpha = 0.08f))` (+ label flips to black,
  `:908`).
- `TrackButton` — `PlayerScreen.kt:924`: identical pattern.
- `BackButton` — `PlayerScreen.kt:946`: identical pattern.
- `PlayPauseButton` (`:849-864`) is different by design — an accent-gradient disc with `focusGlow`
  shadow — and reads fine.

`dpadFocusable` itself draws nothing (`FocusModifiers.kt:50-91`); the fill is purely these three
call sites. R69 already compacted sizes/icons; the fill predates the app-wide focus language.

## Requirements

### FR-R158-1 — Standard focus chrome instead of a fill
1. `SkipButton` / `TrackButton` / `BackButton` keep their resting look (translucent
   `White.copy(0.08f)` pill + hairline border) in **both** states — the fill no longer flips to white.
2. Focus is conveyed the app-standard way: **accent focus ring** (border → the skin's accent) + the
   existing `focusGlow` shadow (`:902` already applies it) + the standard draw-only focus scale
   (`graphicsLayer`, R47 pattern) — matching `RaviloButton`'s language (`RaviloButton.kt:82-93`).
3. Label/icon color stays constant (no white↔black flip); a slight brightness lift on focus is allowed
   (e.g. 80 % → 100 % white) for legibility.
4. `PlayPauseButton` is untouched.

### FR-R158-2 — Skin + legibility check
The focused state must stay clearly distinguishable from unfocused on all three skins
(Aurora/Midnight/Noir — Noir's gold accent included) against both dark scenes and bright video, at TV
distance. The glow + ring + scale combination is the differentiator, not a fill.

## Scope
- `ravilo-ui/…/screens/PlayerScreen.kt` — the three modifier chains (`:900`, `:924`, `:946`) + their
  label color params. Nothing else.

## Non-goals
- No layout/size changes (R69 owns sizing), no new components, no change to `PlayPauseButton`, seek bar,
  or chrome timing.
- Web pointer behaviour is R157.

## Acceptance
- Focusing "next"/skip/audio/back on the TV shows an accent ring + glow + slight scale — **no white
  slab behind the text** — on all three skins.
- Unfocused controls look exactly as before; screenshot-diff of the unfocused chrome is a no-op.
