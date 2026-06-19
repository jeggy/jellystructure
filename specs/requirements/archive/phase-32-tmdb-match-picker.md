# Phase 32 — In-app TMDB Match Picker (FR-TM1)

**Status:** ✓ Done

## Problem
When jellystructure scans a movie or series it picks the first TMDB hit. If that match is wrong the
operator has to look up the correct ID on themoviedb.org and paste it manually. There is no way to
browse alternatives inside the app.

## Solution
Add `GET /api/media/{id}/tmdb-search?q=&year=` that returns up to 10 TMDB candidates for the item's
kind (movie / TV). On the frontend, replace the static "Search TMDB ↗" link in the Identity card with
a **"Find / fix match…"** button that opens a search modal pre-populated with the item's title and
auto-searches on open. The operator can edit the query, re-search, and click a result row to select it
— which calls the existing `PATCH /api/media/{id}/tmdb-id` and reloads the detail view.

## Implementation
### Backend
- `TmdbClient.searchMovieAll(query, year)` and `searchTvAll(query, year)` — return up to 10 results
- `Scanner.searchMovieTmdb` / `searchTvTmdb` — thin wrappers passed through to routes
- `GET /api/media/{id}/tmdb-search` in `MediaRoutes` — dispatches on item kind

### Frontend
- `MediaApi.tmdbSearch(id, query, year)` → `List<TmdbMatchResult>`
- `showTmdbMatchModal` in `MediaDetail.kt` — modal with live search, poster thumbnail, overview
  preview, current-match badge; clicking a row selects it and reloads the detail view
- Identity card: "Search TMDB ↗" link replaced with "Find / fix match…" button
