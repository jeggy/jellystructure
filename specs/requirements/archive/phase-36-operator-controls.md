# Phase 36 — Operator Controls (FR-OC1)

**Status:** ✓ Done

## Features

### Per-library scan
`POST /api/scan?library=<jellyfinId>` filters the scan to a single Jellyfin library.

- `JellyfinClient.getItemsByParent(baseUrl, token, parentId)` — new method using `ParentId` query param
- `Scanner.fetchItemsForLibrary(libraryJellyfinId)` — thin wrapper
- `runScan()` accepts optional `libraryJellyfinId` parameter
- `POST /api/scan?library=` passes it through; still returns 202 Accepted
- Frontend: `MediaApi.startLibraryScan(jellyfinLibraryId)` 

### Scheduled rescan
`behavior.scan_interval_hours` config key (int, default 0 = disabled).
Main.kt launches a coroutine that sleeps `intervalHours * 3600s`, then triggers `runScan` if no scan
is already running. Re-checks the current config value on each iteration (hot-reloadable via PUT /config).

### Notifications webhook
`behavior.notifications_webhook` config key (URL string, default "").
After a successful scan completes, fires a background `curl` POST with:
```json
{"event":"scan_complete","jobId":"<id>","items":<count>}
```
Uses `posixSystem("curl ... &")` for non-blocking delivery (fire-and-forget).

## Config changes
`Behavior` gains `scan_interval_hours` and `notifications_webhook` (both defaulted, no migration needed).
Frontend `Behavior` DTO updated to match.
