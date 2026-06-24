# Phase 69 — Artwork permission banner (FR-AP1)

**Status:** Planned

## Goal

When "Save to disk" or "Fetch all missing" fails on the Artwork tab due to a filesystem permission error, the user only sees a generic "Save failed." toast. The backend logs `Permission denied` opening `.tmp` files in episode subdirectories. A proactive permission check and a proper banner should surface the issue before the user attempts the operation.

## Current state

A proactive NFO write-check **already exists**: `MediaApi.checkNfoWritable(item.id)` calls `GET /api/media/{id}/nfo/writable` and shows `#nfo-perm-banner` when the media directory is not writable. However:

1. The existing check only runs for the **NFO write path** — it tests the item's root directory, not episode subdirectories (e.g. `/mnt/series/jellyfin/Show/Season 1/`).
2. Artwork downloads write to **episode-level paths** (`.thumb.jpg.tmp`), which may be in subdirectories with different permissions.
3. When artwork save fails with `Permission denied`, the error is caught and returned as a generic string — the frontend has no way to distinguish a permission failure from a network failure.

## Target behaviour

1. **Backend**: In the artwork download/save code path, when a `Permission denied` IOException is thrown, include a structured error indicator in the response (e.g., `"errorKind": "permission_denied"` alongside the `"error"` string). Alternatively: surface it via the existing activity log message as a structured event.

2. **Frontend**: On the Artwork tab, when the save result contains a permission error:
   - Show the existing `#nfo-perm-banner` (which already has the copy-command fix-panel) but targeted at the episode/season directory that failed, not just the item root.
   - The banner copy-commands should reference the specific failing path from the error.

3. **Proactive check option** (preferred): Extend `checkNfoWritable` (or add `checkArtworkWritable`) to also test write access in episode subdirectories for series items. Run this check on Artwork tab activation (via the existing tab-switch handler) in addition to the existing check on item load.

## Files

| File | Change |
|------|--------|
| `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/MediaRoutes.kt` | Extend the artwork download error handling to include a `permissionDenied: Boolean` field in the response or activity event |
| `src/wasmJsMain/kotlin/dev/jellystructure/ui/MediaDetail.kt` | On artwork tab activation: call write-check for the item path; show banner on failure. On artwork save error: parse `permissionDenied` and show banner rather than generic toast |

## Non-goals

- No change to how permissions are fixed (the existing copy-command panel is sufficient, pending Phase 67 making copy buttons work).
- No change to the check for NFO paths (already correct for movies and series root).
