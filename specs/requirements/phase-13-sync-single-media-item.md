# Phase 13 — Sync Single Media Item (FR-S2)


## Problem
The only targeted re-fetch is "Re-pull from TMDB" which re-fetches TMDB only (no ffprobe). A full
library scan re-probes everything. There is no way to do a targeted full rescan (ffprobe + TMDB) for
one item, one season, or one series.

## Requirements

### Backend
1. New endpoint: `POST /api/media/{id}/sync`
   - Accepts optional JSON body: `{ "scope": "series" | "episodes" }`. Default is `"episodes"`.
   - For movies: always runs full probe + TMDB fetch (body ignored).
   - For TV series with `scope: "episodes"`: re-probes all episode files with `ffprobe`, re-fetches TMDB episode details, re-runs language resolution. Updates all episodes and top-level series metadata.
   - For TV series with `scope: "series"`: re-fetches TMDB series-level metadata only, no `ffprobe`. Fast path.
   - Emits `ItemScanned` WS event on completion.
   - Returns the updated `MediaItem`.
   - Returns 409 if a scan is already running (`scanTracker.running`).
2. New endpoint: `POST /api/media/{id}/seasons/{seasonNumber}/sync`
   - Accepts optional JSON body: `{ "scope": "season" | "episodes" }`. Default is `"episodes"`.
   - `scope: "season"`: re-fetches TMDB season-level metadata only.
   - `scope: "episodes"`: re-probes all episode files in that season + re-fetches per-episode TMDB details.
   - Returns `{ "synced": N }`.
   - Returns 409 if a scan is already running.
3. Both endpoints reuse `Scanner.scanMovie` / `Scanner.scanSeries` internals (extract shared logic as needed). They do not go through `ScanTracker` start/complete state transitions — they are point operations that run synchronously in the request, with WS progress events.

### Frontend — Media Detail pagebar
4. Add a **"Sync ↻"** button in the `pagebar`, to the left of "Re-pull from TMDB". Style: `btn sm ghost`.
5. For **movies**: clicking immediately calls `POST /api/media/{id}/sync`. Button shows "Syncing…" while in progress. On success, re-renders the detail view. On 409, shows "Scan already running".
6. For **TV series**: clicking opens a modal with:
   - Title: "Sync series"
   - Option 1 **"Series metadata only"** — "Re-fetches TMDB series info. Fast." — calls `scope: "series"`.
   - Option 2 **"Full sync"** — "Re-probes all episode files and re-fetches TMDB. May take several minutes for large series." — calls `scope: "episodes"`.
   - Cancel button.
7. For **seasons** (in the episodes tab, each season header row): add a small `↻` icon button at the right of the season header. Clicking opens a modal:
   - Option 1 **"Season metadata only"** — calls season-scope endpoint with `scope: "season"`.
   - Option 2 **"Season + all episodes"** — calls with `scope: "episodes"`.
   - Cancel button.
8. During any sync operation, the triggering button is disabled and shows "Syncing…". WS events stream to the Activity page in parallel.

## Related (Phase 19)
- Sync/repull must preserve Jellystructure tags — see [`../phase-19-metadata-page.md`](../phase-19-metadata-page.md) §14.
