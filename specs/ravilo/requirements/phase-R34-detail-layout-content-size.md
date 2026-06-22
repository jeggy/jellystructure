# Phase R34 — TV detail-screen layout + per-user content size

**Status:** ✓ Done (2026-06-22) · _the Movie/Series detail screens open as a full-bleed cinematic hero
(no entry-scroll that strands the top of the screen), with TV-tuned sizing; plus a per-user
**content-size** setting that scales the grid tiles, applied live._

> Builds on the detail screens from **[R13](phase-R13-detail-screens.md)**, the config store
> (**[R04](phase-R04-per-user-config-store.md)**/**[R26](phase-R26-config-dto-unification.md)**), and the
> live push (**[R33](phase-R33-live-config-push.md)**) — a content-size change reflects on the TV in ~1s.

## Problem
Opening a movie/series detail auto-focused the **Play** button, which sat *below* a fixed 460dp hero
band. Compose's `bringIntoView` then scrolled the container down to reveal Play, shoving the backdrop +
title off the top — and because nothing in the hero is focusable, D-pad Up couldn't bring it back. The
screen opened cropped and awkward, with no way to see the full hero. Separately, the detail typography was
oversized for 10-foot viewing, and there was no way to tune the overall content size on the grid screens.

## Goal
Detail screens open at the **top**, showing a full, cinematic hero; navigation never strands the
viewport. Detail text/spacing is sized for TV. And the operator can pick a **content size** per user that
scales the TV grids — without hardcoding.

## Requirements

### A. Full-bleed detail hero (Movie & Series)
1. The hero is a **backdrop sized to the viewport** (from `LocalWindowInfo.containerSize`, density-correct)
   with **title · meta · (series progress) · short synopsis · (resume) · Play / + My List** overlaid in
   the lower third.
2. The first focusable (**Play**) sits *inside the initial viewport*, so entry focus causes **no
   auto-scroll** — the page opens at the top showing the whole hero.
3. D-pad **Down** from the actions scrolls into cast → (episodes, series) → related, which live below the
   hero.
4. The hero text column is width-constrained (~60%) for a readable line length.

### B. Detail sizing (detail-only)
1. Reduced for TV: title 46→34sp, meta 18→15, synopsis 16→14 (2–3 lines), section headers 22/29→18,
   tighter button/row spacing. The **shared poster Tile is not changed here** — its size is governed by §C.

### C. Per-user content size
1. `RaviloConfig` gains **`uiDensity`** (`COMPACT` / `COZY` / `COMFORTABLE`; default `COMFORTABLE` =
   current sizing). `UiDensity.tileScale()` → `0.82 / 0.91 / 1.0`.
2. The TV scales **all grid/row tiles** (Home / Channel / Browse) uniformly by the density, via a
   `LocalTileScale` CompositionLocal fed from the active user's config in `RaviloApp.refreshConfig`.
3. Editable in the Ravilo config **Behaviour** section ("Content size"); read/written in `collectConfig`.
   Applied **live** via the R33 push (config change → re-pull config → new scale → recomposition).

## Invariants
- Config is server-owned per user (R04/R26); the TV renders server state. `uiDensity` defaults to
  `COMFORTABLE`, so existing layouts are unchanged until the operator picks otherwise.
- The detail hero is fixed-tuned (not scaled by `uiDensity`); density governs the **grid tiles** only.

## Out of scope
- Per-element font scaling beyond the tiles; scaling the detail hero by density.
- Changing the shared Tile aspect (that's `tileShape`, R32 §F / P0-4).

## Implemented (2026-06-22)
- `ravilo-ui/.../screens/MovieDetailScreen.kt`, `SeriesDetailScreen.kt` — full-bleed viewport hero with
  overlaid actions; detail-only sizing pass.
- shared `tv/Models.kt` — `UiDensity` enum + `UiDensity.tileScale()` + `RaviloConfig.uiDensity`.
- `ravilo-ui/.../RaviloApp.kt` — `LocalTileScale` provided from `uiDensity`; `components/Tile.kt` scales
  its dimensions by it.
- admin `ui/RaviloConfig.kt` — "Content size" picker in Behaviour + `collectConfig` read/write.
