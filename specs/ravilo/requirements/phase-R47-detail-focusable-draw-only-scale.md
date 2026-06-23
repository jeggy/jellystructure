# Phase R47 — Detail focusables: draw-only focus scale (viewport-jump fix)

**Status:** ✓ Done (2026-06-23) · `:ravilo-ui` (android + wasmJs) compiles; `:ravilo-android:assembleDebug`
builds. _Verify on-device:_ navigating the season picker, episode rail, and detail/hero action buttons
no longer jumps the page. Follow-up to **R42/R43**.

## Problem
R42 fixed the "whole screen jumps when navigating" by making the focus scale **draw-only** so the
lazy/scroll containers stop chasing the animated bounds — but it was only **"Applied to `Tile.kt` and
`ChannelCard.kt`"** (the Home tiles). The **detail-screen** focusables were never converted, so moving
focus around Movie/Series detail still jumps the viewport:

- `EpisodeCard` (episode rail), `SeasonPicker` pills, and `RaviloButton` (Play/Resume/+My List actions,
  on detail **and** the Home hero) all applied `Modifier.scale(scale)` — and the button's lift
  `graphicsLayer { translationY }` — as an **ancestor** of `.dpadFocusable(...)`. Because the scale layer
  wraps the focusable node, the focused element's reported bounds grow as the 1.0→1.06 spring animates,
  and the scroll container scrolls every frame to follow them (the exact R42 root cause).

## Root cause (same as R42)
`dpadFocusable` uses `focusable()`; a scroll container keeps the focused descendant visible by tracking
its bounds. A scale on an **ancestor** of the focusable transforms those reported bounds, so the
viewport drifts in lock-step with the focus animation. The fix is to keep the **focusable at a fixed
layout size** and run the scale (and glow/lift) on an **inner draw-only `graphicsLayer` child**, so the
tracked bounds are constant — exactly what Tile/ChannelCard already do.

## Fix
Mirrored the Tile/ChannelCard pattern in the three detail-screen focusables:
- **`EpisodeCard`** — `.dpadFocusable` moved onto a fixed-width (`320.dp`) outer `Box`; scale + shadow +
  rounded clip run in an inner `Column`'s `graphicsLayer`. Local `shadowElevation` → `glowElevation`
  (avoids the `GraphicsLayerScope.shadowElevation` name clash; value read in the draw phase, R43).
- **`SeasonPicker`** — each pill's `.dpadFocusable` is now the outer `Box`; scale + shadow on an inner
  `graphicsLayer`; background/border/padding kept.
- **`RaviloButton`** — `.dpadFocusable` is the outer `Box`; scale, the focus **lift** (`translationY`),
  and shadow all run in one inner `graphicsLayer`; `shape` → `buttonShape`, `shadowElevation` →
  `glowElevation`. Fixes detail action rows **and** the Home hero buttons.
- **`CastCircle`** — left as-is: its `.scale` already sits on a **descendant** of the focusable
  (the avatar `Box`), so the focusable's bounds are already constant. No change needed.

## Invariants
- Native Compose focus traversal + `focusRestorer` (R30) unchanged; this only moves the scale/glow into
  the draw phase. The focus scale magnitude, glow, and lift are visually identical.
- R42's guarantee (already-visible focusables never trigger a scroll) and R43's no-per-frame-recomposition
  (animation runs entirely in `graphicsLayer`) now hold on the detail focusables too.

## Design reference
`ravilo-ui/.../components/{EpisodeCard,SeasonPicker,RaviloButton,CastCircle,Tile,ChannelCard}.kt`.
Related: R42 (viewport-jump root cause + Tile/ChannelCard fix), R43 (draw-phase animation), R45 (entry
scroll/focus restore).
