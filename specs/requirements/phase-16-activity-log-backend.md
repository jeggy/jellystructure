# Phase 16 — Activity Log Backend (FR-A1)

**Status:** Planned

## Problem
The Activity page only shows live WS events for the current browser session. There is no persistent
log, no history from past scans, and no structured record of backend operations.

## Current state (as-is)
- `Activity.kt`: renders live WS events into `#activity-console`. Resets to empty on every page load.
- `MediaHistory.kt`: exists for per-item audit (nfo writes, track changes) but not for scan/system events.
- All backend logging uses bare `println()`.

## Requirements

### Backend — activity log store
1. New `ActivityLog` service (`media/ActivityLog.kt`). Stores entries in SQLite in a new table `activity_log`:
   ```sql
   CREATE TABLE activity_log (
     id INTEGER PRIMARY KEY AUTOINCREMENT,
     ts INTEGER NOT NULL,
     level TEXT NOT NULL,      -- "info" | "warn" | "error"
     category TEXT NOT NULL,   -- "scan" | "nfo" | "artwork" | "track" | "system"
     message TEXT NOT NULL,
     media_id TEXT             -- nullable
   );
   ```
2. Bounded: keep the last 10,000 entries. Trim oldest entries when inserting beyond the limit.
3. Replace the key `println()` calls in `Scanner`, `NfoWriter`, `ArtworkDownloader`, `FfmpegRunner`, `MkvpropeditRunner` with `activityLog.log(level, category, message, mediaId?)`. Keep the existing `println` alongside (log to both stdout and the database).
4. New endpoint: `GET /api/activity/log?page=1&pageSize=100&category=scan&level=warn` — paginated log entries, newest first. Response: `{ entries: [...], total: Int }`.
5. New endpoint: `DELETE /api/activity/log` — truncates the `activity_log` table.
6. New WS event type `log_line` emitted for every `activityLog.log()` call: `{ "type": "log_line", "level": "info", "category": "scan", "message": "...", "mediaId": "..." }`. Feeds the Activity page console in real time.

### Frontend — Activity page
7. On page load, call `GET /api/activity/log?pageSize=200` and populate `#activity-console` with recent history. Newer live events are appended as they arrive via WS.
8. Add a filter bar above the console with category chips: **All** / **Scan** / **NFO** / **Artwork** / **Tracks** / **System**, plus an **"Errors only"** toggle. Filtering is client-side (hides non-matching lines).
9. **Active workers display**: in the pagebar, show a live badge: `"Workers: {activeWorkers}/{configuredWorkers}"` (e.g. `"Workers: 3/5"`). Reads from `GET /api/scan/status` on page load; updates on WS `started`/`finished` events. Shows `"Workers: —"` when idle.
10. The existing **"Clear"** button clears the in-memory display only (does not touch the database).
11. A new **"Clear log"** button (ghost, smaller, separate from "Clear") calls `DELETE /api/activity/log` after a `confirm()` dialog. Re-fetches and re-renders the (now empty) log.
