# Phase 33 — Jellyfin ⇄ NFO Drift Detection (FR-DR1)

**Status:** Planned

## Problem
Phase 22 detects when Jellyfin has **locked** fields. It does not detect **divergence**: someone edits
the item in Jellyfin's UI after Jellystructure wrote the NFO, or another tool rewrites the NFO on disk.
Either way Jellyfin no longer shows what Jellystructure considers truth, silently. Since the product's
whole premise is "Jellystructure is the single source of truth," we should surface drift and let the
operator re-assert.

## Current state (as-is)
- `NfoWriter` writes the NFO; nothing later compares the live Jellyfin item against it.
- `JellyfinClient.getItem` (Phase 25) can fetch a single live item incl. `LockData`/`LockedFields`.
- Media Detail already has a banner pattern (`nfo-perm-banner`, lock banner) and the Phase 9 word-diff.

## Requirements

### Backend
1. `GET /api/media/{id}/drift` → compares the NFO Jellystructure last wrote (or the stored `MediaItem`)
   against the **live** Jellyfin item for the managed fields (title, overview, genres, tags, year,
   studio/network). Returns `{ fields: [{ field, nfoValue, jellyfinValue }] }` (empty ⇒ no drift).
   - Scope to fields Jellystructure manages; ignore fields it never writes.
2. **Re-assert** reuses the existing write path: `POST /api/media/{id}/nfo` (+ Jellyfin refresh) pushes
   the NFO values back. **Accept** (take Jellyfin's value) writes that value into the stored item +
   NFO via the existing `PATCH …/metadata` then re-write.
3. Drift is computed **on demand** (detail load / explicit re-check), not stored — avoids a background
   reconciliation loop.

### Frontend — Media Detail
4. When `drift.fields` is non-empty, show an **amber drift banner** under the pagebar: "Jellyfin's
   metadata no longer matches the NFO … N fields differ," with **Review differences** and
   **Re-assert NFO → Jellyfin**.
5. **Review differences** opens a modal listing each drifted field with NFO vs Jellyfin values and
   per-field **Re-assert** / **Accept**, plus a footer **Re-assert all → Jellyfin**.
6. Dismissable; re-check available (reuse the lock banner's "Re-check" affordance pattern).

## Invariants
- **Jellystructure is source of truth** but the operator decides per field; nothing auto-reconciles.
- **NFO writes stay atomic**; re-assert is an ordinary NFO write + refresh.
- **`js_tags` survive** any accept/re-assert (Phase 19 invariant).

## Out of scope
- Continuous/background drift polling; drift for episodes (item-level first).
- Three-way merge UI — per-field pick (re-assert vs accept) is enough.
