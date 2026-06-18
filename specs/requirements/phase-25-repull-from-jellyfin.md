# Phase 25 — Fix the Sync Button → "Re-pull from Jellyfin…" (FR-RJ1)

**Status:** Planned

## Problem
The Media Detail **"Sync ↻"** button is misleading. It looks like it re-discovers the item from
Jellyfin, but it actually re-probes the local file and re-fetches **TMDB**. We want a button that
genuinely **re-pulls the item from Jellyfin** (re-discovery: fresh name, path, provider ids, lock
state from Jellyfin), gated behind a confirmation popup, and clearly labelled.

## Current state (as-is)
- Pagebar button: `<button id="sync-btn" class="btn sm ghost">Sync ↻</button>` (MediaDetail.kt ~311).
- Click handler: movies → `MediaApi.syncMedia(item.id)` immediately; TV → `showSyncModal(...)`.
- `MediaApi.syncMedia` → `POST /api/media/{id}/sync` → `Scanner.syncMovie` (ffprobe + **TMDB**) for
  movies; for TV `rescanMetadata` (TMDB) or `syncSeriesEpisodes` (ffprobe + TMDB).
- **No path currently re-fetches the single item from Jellyfin.** `JellyfinClient.getItems` fetches
  *all* items; there is no single-item fetch. See [`_investigation-findings.md`](_investigation-findings.md).
- There is a separate "Re-pull from TMDB" button (`repull-btn`) — that one is correct and stays.

## Requirements

### Backend — re-pull a single item from Jellyfin
1. Add `JellyfinClient.getItem(baseUrl, token, jellyfinId): JellyfinItem?` — fetch one item with the
   same `Fields=` as `getItems` (Path, ProviderIds, ProductionYear, and lock fields per
   [`phase-22`](phase-22-remove-lockdata-detect-locks.md)). Endpoint: `/Items/{id}` (or
   `/Users/{userId}/Items/{id}`) with the machine token.
2. New endpoint `POST /api/media/{id}/repull-jellyfin`:
   - Requires `item.jellyfinId`; 400 if absent.
   - 409 if a scan is running (`scanTracker.running`), like the existing sync endpoints.
   - Fetches the fresh `JellyfinItem`, re-matches it to a configured library (same prefix logic as the
     scanner, including the Phase 15 fix), re-derives the local path, and re-runs the scanner's
     per-item path (`scanMovie` / `scanSeries`) so name/path/provider-id/track/TMDB state all refresh
     from the current Jellyfin truth.
   - This means **refactoring `Scanner.scanMovie`/`scanSeries` to be callable for a single
     `JellyfinItem`** (they're currently `private`; expose a `rescanFromJellyfin(item: MediaItem)`
     wrapper that resolves the `JellyfinItem` and calls the shared logic).
   - Preserve Jellystructure tags across the re-pull (same invariant as Phase 19 §14).
   - Persist via `store.updateOne`, emit `ItemScanned` WS, and run the existing `pushToJellyfin`
     follow-up (NFO + artwork + refresh) as the other point-syncs do.
   - Returns the updated `MediaItem`.
3. Keep the existing `/sync` and `/repull` (TMDB) endpoints; this is an additional, distinct action.

### Frontend — relabel + confirmation popup
4. Rename the button to **"Re-pull from Jellyfin…"** (with the ellipsis — it always opens a popup
   first, for both movies and TV). Keep it in the pagebar to the left of "Re-pull from TMDB".
5. Clicking opens a confirmation modal:
   - Title: "Re-pull from Jellyfin"
   - Body: "Re-fetches this item's name, file path, provider IDs and tracks from Jellyfin, then
     re-resolves language and TMDB metadata. Use this when the item moved, was renamed, or its
     Jellyfin match changed."
   - Buttons: **"Re-pull"** (primary) and "Cancel".
6. On confirm, call `POST /api/media/{id}/repull-jellyfin`. The button shows "Re-pulling…" and is
   disabled while in flight. On success, re-render the detail view; on 409 show "Scan already
   running"; on 400 (no `jellyfinId`) show an explanatory message.
7. Remove the old TMDB-fetching behaviour from this button. The TV-specific `showSyncModal`
   (series-metadata-only vs full) belongs to the TMDB/episode sync story — keep it on the existing
   "Re-pull from TMDB" / season sync controls, not on this Jellyfin button. (Confirm in the plan
   whether the old `/sync` button is removed entirely or repurposed; the user's intent is that the
   Jellyfin re-pull replaces the confusing "Sync ↻".)

## Open question for the plan
- Whether to fully remove `POST /api/media/{id}/sync` (movie ffprobe+TMDB) or keep it behind a
  different control. The user only asked to fix the **button**; the ffprobe-only-and-TMDB sync logic
  still has value (e.g. file changed but Jellyfin didn't). Recommend: keep the endpoint, drop the
  ambiguous button, and expose ffprobe re-sync via the existing per-season / track flows.
