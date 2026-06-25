# Phase R43 — Focus-navigation smoothness

a bit laggy. Run the focus animation entirely in the draw phase (no per-frame recomposition) and use a
snappier spring._

> Follow-up to **[R42](phase-R42-focus-animation-viewport-jump.md)**. UI-perf only — no behaviour change.

## Problem
Navigating between tiles felt sluggish even with no jumping. Two causes:
1. **Recomposition every animation frame.** In `Tile`/`ChannelCard` the focus scale was already
   draw-only (`graphicsLayer`, R42), but the **shadow and border read animated values as modifier
   parameters** — `Modifier.shadow(elevation = animatedDp)` and `Modifier.border(width = animatedDp)`.
   Reading an `animateDpAsState` value in a modifier *parameter* invalidates **composition** every frame,
   so each focusing + un-focusing tile recomposed ~per frame for the whole spring.
2. **Soft/slow spring.** `spring(dampingRatio = 0.65, stiffness = StiffnessMediumLow = 400)` settles
   slowly, which reads as laggy regardless of frame rate (perceived responsiveness wants the visible
   response under ~100 ms — RAIL).

## Best practices studied (sources)
- **Jetpack Compose phases** — a state read in a composable/modifier-parameter schedules recomposition;
  the same read inside a lambda modifier (`graphicsLayer { }`, `drawBehind`/`drawWithCache`) runs in the
  layout/draw phase only. (developer.android.com/develop/ui/compose/phases · …/performance/bestpractices)
- **Animate in the draw phase** — "use `Modifier.graphicsLayer{ }`, as this modifier always runs in the
  draw phase"; it doesn't change measured size, so a scaled focused tile doesn't relayout neighbours.
  (…/animation/quick-guide · …/graphics/draw/modifiers)
- **Snappy feel** — input feels instant only if the response lands within ~100 ms; prefer a stiff spring
  (interruptible across fast d-pad presses) or a ~120–200 ms tween. AndroidX TV `Surface`/`Card` uses a
  300 ms tween (the "soft" default to avoid).
- **Images** — one shared Coil `ImageLoader`, crossfade off, constrained tile sizes so posters downsample.
  (Already satisfied: Coil3's singleton loader, crossfade off by default, fixed tile `width×height`.)
- **Measure on release/R8 + baseline profiles** — debug Compose is much slower and masks the real cost.

## What shipped
`Tile.kt` + `ChannelCard.kt`: the **whole focus animation now runs in the draw phase**:
- scale **and** shadow set inside `graphicsLayer { scaleX/scaleY = scale; shadowElevation = …; shape; clip }`
  (animated values read in the lambda — draw phase);
- the focus ring drawn in `drawWithCache { onDrawWithContent { drawContent(); drawRoundRect(Stroke) } }`
  (animated width read in the draw lambda) instead of `Modifier.border(animatedDp)`.
- → no animated value is read at composition, so a focus move triggers **one** recomposition (to flip
  the title colour + set animation targets), not one per frame.
- Spring retuned to `dampingRatio = 0.8, stiffness = StiffnessMedium (1500)` for a crisp, interruptible
  response.

## Out of scope / future
- **`LazyLayoutCacheWindow`** (pre-compose neighbour tiles to cut first-focus-to-off-screen latency) is
  Compose Foundation ≥1.9; this repo is on Compose Multiplatform **1.8.1**, so it's deferred until a bump.
- R8/minified release build + an app baseline profile for accurate on-device perf measurement.
- Image preloading (`imageLoader.enqueue`) for rows scrolled into view.

## Design reference
`ravilo-ui/.../components/Tile.kt`, `ChannelCard.kt` (focus animation), `theme/Colors.kt`
(`focusRing`/`focusGlow`/`tileRadius`). `ContentRow.kt` keeps its `focusRestorer` + edge bring-into-view.
