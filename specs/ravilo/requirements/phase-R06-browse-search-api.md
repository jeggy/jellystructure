# Phase R06 — Browse + multi-language search + channel feeds (FR-RV6)

**Status:** Planned · _everything-grids and finding things, reusing existing capabilities._

## Problem
Beyond Home, Ravilo needs: a **channel view** (the same row set scoped to a channel), full **browse
grids** for Movies / Series / My List with filters, and **search** that matches every title an item
has ever had. These should reuse jellystructure's existing facet (Phase 30) and multi-language search
(Phase 29) capabilities rather than reinventing them.

## Current state (as-is)
- Phase 29: `titlesByLang` lets search match every title ever pulled + the original title.
- Phase 30: `meta-facets` + multi-axis filters (studios/networks/genres/tags) exist for the admin
  library. No TV-facing browse/search/channel endpoints.

## Requirements

### Channel feed
1. `GET /api/tv/channel/{id}` → a feed shaped like `HomeFeed.rows` but **scoped to the channel's
   filter** (studio/network/genre/tag from `RaviloConfig`): the **same row lineup** (Continue, Newly
   Added, genres) intersected with the channel, plus a channel header (name/logo/brand color).
2. Empty rows within a channel are omitted; a channel with nothing resolves to a friendly empty state
   payload.

### Browse grids
3. `GET /api/tv/browse?kind=movie|series|mylist&filter=…&sort=…&page=…` → a paged grid of
   `MediaCard`s.
   - `movie`/`series`: the library filtered by `kind`, with optional genre/studio/network/tag filters
     **reusing Phase 30 facets**; sortable (recently added, A–Z, year, rating).
   - `mylist`: the user's saved/favourite items (Jellyfin favorites or a Ravilo list).
4. `GET /api/tv/facets?kind=…` → the available filter values + counts for the grid's filter chips
   (thin wrapper over Phase 30 meta-facets).

### Search
5. `GET /api/tv/search?q=…` → `SearchResults` of `MediaCard`s, matching **every title an item has ever
   had** (Phase 29 `titlesByLang`) + original title; case/diacritic-insensitive; bounded result count;
   empty `q` → a small "suggestions" set (popular/recent) for the idle search screen.

## Invariants
- **Reuse Phase 29 search and Phase 30 facets** — do not build a parallel index.
- Channel views render the **same row set**, scoped — consistent with Home.
- Results are `MediaCard`s with Jellyfin image URLs (data plane).

## Out of scope
- The grid/search/channel **UI** (R11, R12).
- Detail and playback (R07, R08).
