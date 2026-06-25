# Phase 17 — Activity Log Backend & Live Runners (FR-A1)


## Problem
The Activity tab is **always empty** except for live WS events in the current browser session — it
resets on every page load and has no history. We want a real activity log managed on the backend: every
important log line goes to **both** stdout and a queryable log the Activity tab reads. We also want a
live **active-runners** display.

## Current state (as-is)
- `Activity.kt` renders only live WS events into `#activity-console`; idle on load. No history fetch.
- All backend logging is bare `println(...)`.
- `MediaHistory` is **in-memory only** (ArrayDeque cap 2000), per-item audit, not a general log.
- **No SQLite exists** — persistence is JSON files. See [`_investigation-findings.md`](_investigation-findings.md).
- `JobEvent` has no `log_line` type. `WsBroadcaster.broadcast` is mutex-guarded.

## Requirements

### Backend — activity log store
1. New `ActivityLog` service (`media/ActivityLog.kt`). Entry shape:
   ```kotlin
   @Serializable data class ActivityEntry(
     val id: Long, val ts: Long,
     val level: String,     // "info" | "warn" | "error"
     val category: String,  // "scan" | "nfo" | "artwork" | "track" | "system"
     val message: String,
     val mediaId: String? = null,
   )
   ```
2. **Persistence follows the existing JSON-file + atomic-write pattern** (like `MediaStore`/`ScanTracker`),
   e.g. `<data>/activity-log.json`, guarded by a `Mutex`. Bounded to the last **10,000** entries —
   trim oldest on insert. (Do **not** introduce SQLite "as the constitution says"; if a DB is wanted,
   that's a separate, deliberate dependency decision — flag it, don't assume it.)
3. Provide `activityLog.log(level, category, message, mediaId?)` that:
   - appends to the store (with trim),
   - also `println`s the same line to stdout (keep existing stdout logging),
   - broadcasts a `log_line` WS event (see §6).
4. Replace/augment the key `println` calls in `Scanner`, `NfoWriter`, `ArtworkDownloader`,
   `FfmpegRunner`, `MkvpropeditRunner` (and the scan lifecycle in `runScan`) with `activityLog.log(...)`.
   Keep stdout output. `ActivityLog` must be injected where needed (it's currently constructed in
   `Main.kt` and passed like `MediaHistory`).
5. Endpoints:
   - `GET /api/activity/log?page=1&pageSize=100&category=scan&level=warn` → `{ entries: [...], total }`,
     newest first, filters optional.
   - `DELETE /api/activity/log` → truncate the store.
6. New WS event `JobEvent.LogLine`:
   ```json
   {"type":"log_line","level":"info","category":"scan","message":"...","mediaId":"..."}
   ```
   emitted for every `activityLog.log(...)`.

### Frontend — Activity page
7. On load, `GET /api/activity/log?pageSize=200` and populate `#activity-console` with history; append
   live `log_line` events as they arrive over WS (in addition to the existing job-event rendering).
8. Filter bar above the console: category chips **All / Scan / NFO / Artwork / Tracks / System** plus an
   **"Errors only"** toggle. Client-side filtering (hide non-matching lines).
9. **Active-runners display** in the pagebar, driven by Phase 16's `GET /api/scan/status`
   (`activeWorkers`, `configuredWorkers`):
   - Idle: `"Workers: —"`.
   - Running: `"Workers: {activeWorkers}/{configuredWorkers}"`, e.g. **`5/5`**.
   - When the user lowers `scan_workers` from 5 to 1 mid-scan, the display reflects the **drain**: it
     ticks `5/1 → 4/1 → … → 1/1` as excess workers finish their current item and stop. (`activeWorkers`
     is the live count; `configuredWorkers` is the new target.)
   - Update on WS `started`/`finished` events and on a light poll/`log_line` cadence while a scan runs,
     so the drain is visible without a manual refresh.
10. Existing **"Clear"** button clears the in-memory display only (no DB call).
11. New **"Clear log"** button (separate, smaller, ghost) → `confirm()` → `DELETE /api/activity/log` →
    re-fetch and re-render the (now empty) log.

## Notes
- `MediaHistory` (per-item audit) stays as-is; the activity log is the broader system log. They may
  later converge, but not in this phase.
