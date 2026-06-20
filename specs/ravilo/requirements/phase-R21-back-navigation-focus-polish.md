# Phase R21 — Back navigation & focus polish (FR-RV21)

**Status:** ✓ Done · _wires D-pad Back on all screens and makes the focus cursor visible at TV viewing distance._

> Follows hardware-testing on the stue TV (2026-06-20). Touches **`:ravilo-ui` only** — no backend changes.

## Problem

Two UX gaps found during live testing:

1. **Back navigation exits the app.** The `stack`/`pop()` infrastructure in `RaviloApp.kt` was
   already correct, but the D-pad Back key fell through when the focused element had no `onBack`
   handler (tiles, grid cells). Unhandled `Key.Back` events propagated out of the Compose tree and
   the Activity finished the app — even when the user was three screens deep.

2. **Focus cursor invisible at TV viewing distance.** The R20 focus animations existed
   (`animateFloatAsState` for scale) but were too subtle on a 55" screen viewed from 3 m:
   - Scale was only 3–6% — imperceptible at distance.
   - The focus border appeared/disappeared instantly (no animation).
   - No depth cue (glow / shadow) to lift the focused card above the grid.

## Requirements

### R21-1 — Root back-intercept

1. `RaviloApp.kt` wraps its `AnimatedContent` in a `Box` with `Modifier.onKeyEvent`.
   When `Key.Back` or `Key.Escape` is received at this layer (i.e., no child consumed it) and
   `stack.size > 1`, `pop()` is called and the event is consumed (`true`).
   When `stack.size == 1` (root screen), the event is not consumed — the system handles it and
   the app exits as expected.

### R21-2 — Spring animation spec

2. All focusable components replace the implicit default tween with a **spring** spec:
   `spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)`. This produces a fast
   snap with a subtle overshoot — the classic TV "pop" feel. The same spec is used for both float
   and Dp animations within a component, so scale and border animate in lock-step.

### R21-3 — Stronger scale targets

3. Scale-up targets increased to be legible at TV viewing distance:
   - `Tile` (poster/landscape): 1.0f → **1.10f** (was 1.06f)
   - `ChannelCard`: 1.0f → **1.08f** (was 1.05f)
   - `EpisodeCard`: 1.0f → **1.06f** (was 1.03f)
   - `RaviloButton`: 1.0f → **1.06f** (was 1.04f)
   - `SeasonPicker` pill: 1.0f → **1.06f** (new, was no scale)

### R21-4 — Animated focus ring

4. The focus border on every component is replaced with `animateDpAsState`:
   - Unfocused: `0.dp` (invisible — no conditional `Modifier` branch needed)
   - Focused: `3.dp` (was a hard `2.dp` instant switch)
   - Spring spec as per R21-2.
   - Modifier order: `shadow → clip → border` so the border draws on top of the shadow but inside
     the clip boundary (prevents border from being cut off).

### R21-5 — Glow shadow

5. A colored drop shadow is added to every focusable card/button via `Modifier.shadow`:
   - `elevation`: `animateDpAsState(if (focused) 16–20.dp else 0.dp)` using the same spring spec.
   - `ambientColor` and `spotColor` both set to the component's focus-ring color (`colors.focusRing`
     for tiles/episodes/buttons, `accentColor` for `ChannelCard`). This produces a purple/accent
     halo on API 28+ and a standard black shadow on earlier APIs.
   - `clip = false` on the shadow modifier (clipping is handled by the subsequent `clip(shape)`).

## Files changed

| File | Change |
|------|--------|
| `RaviloApp.kt` | Root `Box` with `onKeyEvent` to intercept unhandled back |
| `components/Tile.kt` | Spring spec; scale 1.10f; animated border 0→3dp; shadow glow |
| `components/ChannelCard.kt` | Spring spec; scale 1.08f; animated border 0→3dp; shadow glow |
| `components/EpisodeCard.kt` | Spring spec; scale 1.06f; animated border 0→3dp; shadow glow |
| `components/RaviloButton.kt` | Spring spec; scale 1.06f; shadow glow |
| `components/SeasonPicker.kt` | Spring spec; scale 1.06f; animated border 0→2dp on pills |

## Invariants

- **No new API endpoints or DTOs.**
- **No new screens or navigation destinations.**
- Back from root screen (Home/Pairing/ProfilePicker) still exits the app — `pop()` guards on
  `stack.size > 1`.
- `SeasonPicker` pills do not get a shadow (too small; shadow would bleed into adjacent pills).
