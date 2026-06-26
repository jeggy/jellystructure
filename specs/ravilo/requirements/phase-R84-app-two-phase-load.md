# Phase R84 — App two-phase load on the **detail screens**: instant catalog, late playstate hydration

> Consume R83's split on the Ravilo **detail screens** (movie/series): render the catalog instantly from
> jellystructure, then hydrate per-user indicators (resume bars, watched ticks, Play/Resume label) via
> the separate `GET /api/tv/playstate` request. Best perceived speed **with an absolute no-flicker rule**:
> late hydration may only fill content inside already-reserved, fixed bounds — it must never add, remove,
> resize, or reorder anything on screen.
>
> **Home is out of scope here.** Home keeps rendering fully from the unchanged `GET /api/tv/home` (its
> only per-user element, the Continue row, stays inline per R83), so it has nothing to hydrate late and
> no row ever appears after first paint.

## Problem
The detail screens currently wait for one immutable response that bundles catalog **and** per-user
state, so first paint is gated on a Jellyfin call (via the backend). With R83 the catalog comes back
instantly and per-user state arrives on a second request — the app must render catalog immediately and
**fold in** playstate a moment later **without any visible movement**: no jank, no reflow, no row
appearing, no focus loss, no wrong-action.

## Architectural constraint (driving decision)
**FE renders server-pushed state only** ([[fe-reflects-be-no-derived-state]]) — playstate is hydrated
from the server's second response, never guessed client-side. The two phases are two server reads, not
optimistic local state.

## Current state (as-is)
- All four stores model one immutable `Loaded(...)` with `progressPct`/`watched`/`PlaybackState`/
  `SeriesProgress` carried **inline** on each `MediaCard`/detail.
- **`HomeStore` and `MovieDetailStore` already have a silent re-emit path** (`refresh(silent=true)` /
  `refreshSilent`, the R33 live-config mechanism) that swaps in new data with **no `Loading` flash** and
  preserves scroll/focus — directly reusable for a phase-2 merge.
- **Hazard:** `SeriesDetailScreen` keys season-picker state on `remember(detail)` (`initialSeasonIdx`,
  `selectedSeasonIdx`) — a phase-2 re-emit of a new `detail` object **resets the season picker**.
- **Per-user indicators (16, inventoried in the report)** — tile progress bar (`Tile.kt`), tile watched
  ✓, home/channel/browse/search tile bar+✓+subtitle, related-tile ✓, the **Continue Watching row**,
  movie Play/Resume label, series Play/Resume label + "X of Y watched" + resume kicker + **initial season
  selection**, episode-card bar/✓/dim/"UP NEXT", player next-up rail.
- **Key enabling fact:** the **movie resume seek is resolved server-side** — `onPlay` passes only
  `card.id`; `PlaybackService` reads `StreamTicket.startPositionMs` from Jellyfin. So `detail.playback`
  drives **only the movie label** — pressing "Play" before it flips to "Resume" still resumes correctly.
- The app already resolves relative server paths for channel logos (`HomeScreen.kt`:
  `if (it.startsWith("/")) "$baseUrl$it"`).

## Requirements

### A. Two-phase fetch on the detail screens (movie/series only)
1. Phase 1: load catalog from `GET /api/tv/movie|series` and render immediately (no Loading flash on
   re-entry — reuse the `refreshSilent` path).
2. Phase 2: after the detail paints, call `GET /api/tv/playstate?ids=…` (the item + its episode ids) and
   fold the `{id → {resumeMs, played, playedPct}}` map into the rendered detail.
3. Implement the merge as a **parallel overlay** `StateFlow<Map<id, CardPlayState>>` that the detail
   reads by id (empty map → no overlay yet), **or** a `refreshSilent` re-emit. For **series**, the overlay
   map is preferred so phase-2 hydration leaves the catalog `detail` object — and therefore the season
   picker — untouched (see C).
4. **Home is unchanged** — no playstate call, no Continue fetch; it renders fully from `GET /api/tv/home`.

### B. Late hydration must be invisible — fill within fixed bounds only
5. Every late indicator must occupy **space that already exists at first paint**, so filling it causes
   **zero layout movement**:
   - tile progress bar + watched ✓ → overlays inside the fixed tile bounds (already the case);
   - episode-rail resume bar / ✓ / dim / "UP NEXT" → overlays inside fixed-size episode cards;
   - series "X of Y watched" text + resume kicker → render in a **reserved** line (placeholder height held
     from first paint) so the text appearing doesn't push the layout;
   - **movie & series Play/Resume button** → **fixed (or min) width** sized for the longest label
     ("Resume · NN min left" / "Resume S_E_"), so swapping "Play" → "Resume" changes only the glyph/text,
     never the button width or neighbours' positions.
6. Indicators **fade in** (no hard pop). No `Loading` state, no skeleton swap on the detail body — the
   catalog stays on screen throughout.

### C. Correctness fixes
7. **Series Play must never start the wrong episode.** Resolve the resume target before Play can act on
   it — either include the series resume pointer (`resumeEpisodeId` + season) in the phase-1 catalog
   payload, or keep the primary button in a neutral non-committal state until playstate lands. (`onPlay`
   builds context from `resumeEpisodeId`/`selectedSeasonIdx`.) The movie Play button is always safe — its
   seek is resolved server-side from `card.id`, so an early press resumes correctly regardless of the label.
8. Fix the `SeriesDetailScreen` `remember(detail)` season-reset (key off `itemId`, or use the overlay-map
   so the catalog `detail` object stays stable across hydration).

## Invariants
- **Nothing moves after first paint.** Late hydration may change a pixel's *content* but never the size,
  position, count, or order of any element — no row appears/reorders, no button resizes, no text reflows
  (reserved space + fixed-width button enforce this). This is the load-bearing rule of the phase.
- No `Loading` flash on hydration — catalog stays on screen; indicators fade in.
- No focus jump caused by hydration.
- No wrong-action: series Play never targets the wrong episode; movie Play is always safe.
- Renders server-pushed state only; the overlay reflects the `/playstate` response verbatim.

## Out of scope
- Backend endpoints themselves — delivered by **R83**.
- **Home / the Continue row** — unchanged (R83 keeps Continue inline; decoupling it was rejected as it
  would flicker). Home does not get a two-phase load.
- Artwork source switch — that's **R85** (this phase keeps whatever image URLs the DTO carries).
- Owning playstate locally / optimistic updates — none; always server-driven.

## Source references
- `ravilo-ui/.../screens/{HomeStore,DetailStore}.kt` (silent-refresh; two-phase merge), `BrowseScreen.kt`
- `ravilo-ui/.../components/Tile.kt`, `EpisodeCard.kt`, `HeroCarousel.kt`; `screens/HomeScreen.kt`,
  `MovieDetailScreen.kt`, `SeriesDetailScreen.kt` (`remember(detail)` season fix), `ChannelScreen.kt`,
  `SearchScreen.kt`, `PlayerScreen.kt` (next-up rail)
- `shared/.../tv/TvApiClient.kt` (`getPlaystate`), `Models.kt`
- Research report: `specs/research-reports/ravilo-jellyfin-decoupling-investigation.md` §10.5 (Workstream 3 app)
- Depends on: **R83** (catalog-only detail endpoints + `/playstate`). Reuses **R33/R40** (silent refresh /
  instant back). Home / Continue row deliberately untouched.
