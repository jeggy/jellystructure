# Phase 34 — Undo / Revert from History (FR-UR1)

**Status:** Planned

## Problem
Jellystructure mutates files (NFOs, track flags) — the operator needs a safety net. `MediaHistory`
already logs every action per item and the Media Detail **History** tab renders it, but entries are
read-only. There's no way to roll back a bad TMDB match, a hand edit, or a wrong default-flag change.
Because NFO writes are atomic and actions are logged, **revert** is a natural fit.

## Current state (as-is)
- `MediaHistory.kt` — append-only audit log per media id; History tab lists `{when, action, detail}`.
- Entries today record *that* something changed, not necessarily the **previous value**.

## Requirements

### Backend — capture enough to revert
1. Extend history entries with a `revertable: Boolean` flag and a `before` snapshot for the fields the
   action changed (the minimum needed to restore): metadata edits store prior field values; track
   default/language/forced changes store the prior `{specifier → flag/lang}`; NFO writes store the
   prior NFO (or prior managed-field set); a TMDB-id / re-pull stores the prior `tmdbId` + key fields.
   - Non-revertable actions (scan discovery, lock detection) carry `revertable: false`.
2. `POST /api/media/{id}/history/{entryId}/revert` — restores the `before` snapshot:
   - metadata → `PATCH …/metadata`; track flag → the existing set-default/language path (through the
     `SeedingGuard`, Phase 26); NFO → re-write the prior NFO. Then re-`ffprobe`/re-read as needed,
     persist, log a new `revert` history entry (itself revertable = the inverse), and trigger a
     Jellyfin refresh if configured.
   - 409 if a scan is running or the file is seeded (surface the guard message).

### Frontend — History tab
3. Revertable rows show a **Revert** button; non-revertable rows show none.
4. Clicking confirms ("Revert this change? The previous value is restored and re-written to the NFO.")
   then calls the endpoint, toasts success, and re-renders detail + history.
5. A reverted action appears as a new history entry (so the revert is itself auditable / re-revertable).

## Invariants
- **Track flags change only by explicit action** — revert is an explicit action (constitution §1).
- **NFO writes atomic**; revert is an ordinary write of the prior value.
- **Frontend renders server state only** — re-fetch after revert; no optimistic rollback.

## Out of scope
- Reverting a full library scan in one click (per-item only).
- Infinite redo stacks — linear history with revert-creates-entry is enough.
