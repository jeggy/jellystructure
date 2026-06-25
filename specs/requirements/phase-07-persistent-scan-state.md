# Phase 7 — Persistent Scan State & Resume (FR-S1)

## Problem
`ScanTracker` is entirely in-memory. When the user refreshes the page mid-scan the dock counter
resets to 0 because the frontend was accumulating `ItemScanned` WS events locally. When the backend
restarts mid-scan, the scan state is gone entirely — no way to resume.

## Current state (as-is at time of spec)
- `ScanTracker`: three `var` fields (`running`, `lastCount`, `cancelRequested`) — no persistence
- `ScanStatus` DTO: `{ running: Boolean, lastCount: Int? }` — no state machine, no history
- Frontend dock: initialises `dockScanned = status.lastCount` on page load — correct when backend is alive, zero after a restart
- Cancel button exists; "resume" concept does not exist
- The scan iterates `jellyfinClient.getItems()` in the order Jellyfin returns items; processed item IDs are not recorded anywhere

## Requirements

### Backend — scan state persistence
1. Introduce a scan state file (`scan-state.json`) alongside the media cache. Single-entry file, not a log. Stored in the same config directory as `media-cache.json`.
2. The state machine has four statuses: `IDLE`, `RUNNING`, `CANCELLED`, `COMPLETE`.
3. The state file schema:
   ```json
   {
     "status": "CANCELLED",
     "jobId": "scan-1720000000",
     "startedAt": 1720000000,
     "updatedAt": 1720001234,
     "totalSeen": 142,
     "processedIds": ["abc123", "def456", ...]
   }
   ```
4. `processedIds` is the list of Jellyfin item IDs that were fully scanned and emitted in the current (or last) scan run. This is the resume checkpoint.
5. `ScanTracker` is rewritten to read/write this file atomically (`.tmp` + rename). It loads the file on startup.
6. On backend startup: if the persisted status is `RUNNING` (i.e. the process was killed mid-scan), transition it to `CANCELLED` — the scan was interrupted.
7. `GET /api/scan/status` returns the full state: `{ status, jobId, startedAt, updatedAt, totalSeen, processedCount }`. `processedIds` is not returned (can be large); only the count is.
8. `POST /api/scan` — starts a **new** scan: clears `processedIds`, sets status to `RUNNING`, resets count to 0.
9. `POST /api/scan/resume` — resumes from checkpoint: re-fetches the Jellyfin item list, skips any item whose `jellyfinId` is in `processedIds`, processes the rest. Status goes `RUNNING` again. If status is not `CANCELLED`, returns 409.
10. `POST /api/scan/cancel` — unchanged in surface; now also persists status `CANCELLED`.
11. On scan complete: status → `COMPLETE`, `processedIds` cleared (no checkpoint needed).
12. The scan loop records each successfully scanned Jellyfin ID into `processedIds` and persists after every item (batched flush every 10 items is acceptable).

### Frontend — richer scan UI
1. `ScanStatus` model gains `status: String` (`running` stays for backwards-compat as `status == "RUNNING"`).
2. The Dashboard scan section shows different UI depending on status:
   - `IDLE` / `COMPLETE`: single "Run scan" button
   - `RUNNING`: "Cancel" button + live count from dock
   - `CANCELLED`: two buttons: **"Continue scan"** (resumes, skips processed) and **"New scan"** (starts fresh). A note shows how many items were already processed: "Scan paused — 87 items done, N remaining."
3. The ambient dock reads `lastCount` / `totalSeen` from `GET /api/scan/status` on page load, so the count is correct even after a page refresh. WS events update a local counter added to the server's base count.
4. On Shell initialisation: if scan status is `CANCELLED`, a dismissible inline banner appears in the sidebar status area: "Last scan paused — continue or restart from Dashboard."

## Invariants
- A resume scan must never re-process an item that was already successfully scanned in this run.
- The `processedIds` list is scoped to one scan run. A new scan always clears it.
- Backend shutdown mid-scan (`RUNNING` → `CANCELLED` on next startup) must be handled without manual intervention.
