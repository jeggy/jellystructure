# Phase R63 — Ravilo TV image-pipeline performance (FR-RV-PF2)

> Authored from the design project. Follow-up to **[R85](phase-R85-artwork-served-from-jellystructure.md)**
> (artwork served from jellystructure) and the perf work in **[R86](phase-R86-smooth-sofa-feed-cache-and-filters.md)**.

## Problem
On the TV, the poster / backdrop pipeline is the most visible source of jank. Rows of tiles
pop in late, the Home hero backdrop swaps with a hard cut, and rapid D-pad scrolling through a
large grid stutters as full-resolution images are decoded on the main path. The bytes already
come from jellystructure (R85), but the **client-side load → decode → cache** chain is untuned:
images are fetched at source resolution, decoded at full size regardless of the tile's on-screen
dimensions, and re-fetched when a screen is revisited (the R40 store cache keeps *data*, not
decoded bitmaps).

## Goal
Make image-heavy screens (Home, Browse grids, Channel pages, Discover) scroll and enter smoothly:
tiles fill quickly with a graceful placeholder→image transition, the hero backdrop cross-fades,
and revisiting a screen shows artwork instantly from cache — with bounded memory.

## Requirements
1. **Downsample to the target size.** Request / decode each image at the size it is actually drawn
   (tile vs hero vs cast circle), not at source resolution. Honour density so a 9-foot TV and a
   phone (R60) each get an appropriately sized bitmap.
2. **Memory + disk cache.** Keep a bounded in-memory bitmap cache (most-recent tiles) plus a disk
   cache keyed by `{itemId, kind, size}`, so a revisited row is instant and a cold relaunch is warm.
   Cache key must include the size bucket so the hero and the tile don't evict each other.
3. **Placeholder → image transition.** Every image slot shows the existing gradient/blur placeholder
   immediately and **cross-fades** the real image in when ready (no hard pop, no layout shift). The
   Home hero backdrop cross-fades on carousel advance.
4. **Off-main decode + scroll throttling.** Decode off the focus/render path; while a row is scrolling
   fast under a held D-pad key, defer non-visible decodes so focus movement stays at full frame rate
   (ties into R42/R43 draw-only focus).
5. **Prefetch the next neighbours.** Warm the images just outside the viewport (next tiles in a row,
   next hero slide) so they are ready before focus arrives.

## Mockup
The mockups (`design/ravilo/ravilo-app.js` + `ravilo.css`) already render the gradient placeholder
(`artGrad`) under every tile and the hero noise/scrim — this phase is about the **Compose client's**
loader, so the visible target is "tiles/hero never pop or stutter." No new on-screen element.

## Source references
- `ravilo-ui/.../seams/RemoteImage` (the image loader seam), the tile / hero / `CastCircle`
  composables, the R40 screen-store cache (data, not bitmaps).
- Related: **R85** (artwork origin), **R86** (feed cache), **R42/R43** (draw-only focus, frame budget),
  **R40** (instant back / screen-store retention).

## Out of scope
- Changing where bytes come from (R85 owns that).
- Trickplay / scrubbing thumbnails.
- Any change to poster aspect ratios or tile layout.
