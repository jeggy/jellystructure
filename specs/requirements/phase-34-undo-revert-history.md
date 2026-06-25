# Phase 34 — Undo / Revert from History (FR-UR1)

## Problem
Operators can accidentally change a TMDB ID, metadata fields, or language override and have no way to
undo without remembering the old values manually.

## Solution
Extend `media_history` with two new columns: `revertable INTEGER` and `before_snapshot TEXT`. For
actions that modify editable metadata (`metadata_edit`, `set_tmdb_id`, `language_override`), capture
the prior state as a compact JSON snapshot. Expose `POST /api/media/{id}/history/{entryId}/revert`
which reads the snapshot and restores the stored MediaItem.

Frontend: History tab shows a "Revert" button on each revertable row. Clicking it calls the endpoint
and reloads the detail page at the History tab.

## Implementation
### DB
- Migration `1.sqm`: `ALTER TABLE media_history ADD COLUMN revertable ...` and `before_snapshot ...`
- `MediaHistory.sq`: updated schema + new `findById` query; `insert` now includes the two new columns

### Backend
- `MediaHistory.record()` gains `revertable` and `beforeSnapshot` params
- Three call sites updated: `metadata_edit`, `set_tmdb_id`, `language_override` now record snapshots
- `POST /api/media/{id}/history/{entryId}/revert` in `MediaRoutes` decodes the snapshot by action
  type and calls `store.updateOne()`, then records a `revert` history entry

### Frontend
- `HistoryEntry` gains `revertable` and `beforeSnapshot` fields
- `MediaApi.revertHistoryEntry(id, entryId)` → `MediaItem?`
- `loadHistory` now shows Revert buttons for revertable entries; clicking reloads the page at History tab
