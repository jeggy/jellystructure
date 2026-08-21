# Phase 173 — Artwork tab: show the actual current asset, not just TMDB candidates

> Reported live, same session as Phase 171/172: a screenshot of `Ranvá_Christmas_Show_Download (2020)`'s
> Artwork tab — Poster's rail dot correctly read "2/3 · on disk" (green), but the main panel showed
> "TMDB has no poster candidates for this title" and nothing else. "It still doesn't look like there
> are artwork available within jellystructure."

**Status:** Implemented 2026-08-21.

## Root cause

Confirmed the file is real and correctly placed: `/mnt/media/jellyfin/music/Ranvá/
Ranvá_Christmas_Show_Download-poster.jpg` exists on disk under the exact `<basename>-poster.jpg`
convention Phase 171's `assetFilePath()` fix reads — the rail's "on disk" status
(`MediaApi.getArtworkStatus`) is correctly live and correct. The bug is upstream of that signal: the
Artwork tab's main panel (`renderArtGallery` in `MediaDetail.kt`) **only ever renders TMDB candidate
images** (`GET .../artwork/candidates`). Episode stills already had a "current on-disk preview" block
(R131, shown whenever `t.onDisk`, independent of candidates) — but the equivalent was never built for
item-level assets (poster/backdrop/clearlogo). A movie or series usually has *some* TMDB candidates, so
this gap was rarely visible; a `MUSIC_VIDEO` routinely has **zero** (no TMDB match by default, per
Phase 168, and even a matched one's TMDB candidates are a different concern from the manually-placed
local file) — so its Artwork tab main panel is reliably empty even when the asset is completely fine,
with nothing on screen to prove it.

## Fix

`renderArtGallery()`'s existing `currentPreview` block (previously `t.kind == "episode"` only) gains a
second branch: when `t.kind == "asset" && t.onDisk`, render a 64px thumbnail labeled "Currently in use"
above the (possibly-empty) candidate grid. Sourced from Ravilo's own public `GET /api/tv/image/{id}/
{type}` (`RaviloArtworkService`/`RaviloImageUrl`, `AuthPlugin` `OPEN_API_PATHS` — no new backend route
needed) — it already resolves through the exact same `ArtworkDownloader.assetPath()` the rail's own
"on disk" status comes from, so the preview and the status can't disagree with each other the way two
independently-built paths could. Type mapping: `clearlogo` → the route's `logo`. `artStillBust` (R131's
existing cache-buster, previously still-only) is now bumped on every item-asset save success too
(gallery card click, paste-URL, upload, drag-drop, lightbox "Use this artwork" — 5 call sites), so a
just-saved asset's preview doesn't show a stale cached thumbnail.

## Explicitly out of scope

- Season posters have the same underlying gap (candidate-only gallery, no current-preview) but weren't
  part of what was reported — not touched here.
- No backend change — `RaviloArtworkService`/`ArtworkDownloader` are untouched; this is purely wiring
  an existing, already-correct serving route into a UI panel that never used it.

## Verification

- `compileKotlinWasmJs` clean (covers the admin frontend).
- Confirmed on disk directly: `Ranvá_Christmas_Show_Download-poster.jpg` exists at the exact path
  `assetFilePath()` computes; no bare `poster.jpg` exists in that shared artist folder (ruling out the
  pre-171 movie-shaped convention as an alternate explanation for the rail's "on disk" status).
- Not yet re-verified in a live browser (needs a backend restart to pick up this admin-frontend change,
  and per standing session rules a restart requires the user's explicit go-ahead).
