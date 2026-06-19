# Phase 32 — In-app TMDB Match Picker (FR-TM1)

**Status:** Planned

## Problem
When the auto-match is wrong or missing, the only fix today is Phase 24's manual `tmdbId` field: the
operator leaves for themoviedb.org, finds the right title, copies the numeric id, pastes it back, then
re-pulls. That's the most friction-heavy step in the core loop. We want an **in-app search-and-pick**:
type a query, see candidate results with poster/year/overview, click one to set the id and re-pull.

## Current state (as-is)
- `PATCH /api/media/{id}/tmdb-id` sets/clears the id (Phase 24); `POST /api/media/{id}/repull` fetches
  TMDB metadata for the current id.
- `TmdbClient` already does `search` (movie + TV) for the scanner; results aren't exposed over the API.
- Media Detail Identity card shows the id input + "Search TMDB ↗" (external link only).

## Requirements

### Backend
1. `GET /api/media/{id}/tmdb-search?q=<query>` — search TMDB for the item's **kind** (movie vs tv),
   default `q` to `"{title} {year}"`. Returns up to ~8 candidates:
   `[{ tmdbId, title, year, overview, posterPath, originalLanguage }]`.
   - Reuse `TmdbClient.search*`; map fields; no persistence.
2. Setting the chosen match reuses Phase 24: `PATCH /api/media/{id}/tmdb-id` then (optionally, via
   `?repull=true` or a follow-up `POST …/repull`) fetch metadata. Returns the updated `MediaItem`.

### Frontend — Media Detail
3. A **"Find / fix match…"** button in the Identity card opens a modal: a query box (prefilled with
   title + year), a **Search** action, and a result list of candidate cards (poster thumb, title,
   year, `tmdb {id}` badge, 1-line overview, original language).
4. Selecting a candidate highlights it; **"Use match & re-pull"** calls the tmdb-id PATCH + repull,
   closes the modal, and re-renders detail (pagebar badge flips to "TMDB matched", NFO-write enables).
5. Works for an unmatched item (no current id) and for correcting a wrong match. The external
   "Search TMDB ↗" link stays as a fallback.

## Invariants
- **No auto-fetch on id change** beyond the explicit "Use match & re-pull" action (extends Phase 24).
- **Frontend renders server-fetched state only** — candidates come from the search endpoint; the
  detail re-renders from the re-pull response, not optimistic local edits.

## Out of scope
- Bulk match-fixing across many items; multi-provider (TVDB/IMDb) search.
