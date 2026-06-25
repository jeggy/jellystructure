# Phase 24 — Manage the TMDB ID Field (FR-TI1)


## Problem
On the Media Detail page the TMDB id is shown read-only. When an item has no TMDB match (or the wrong
one), there's no way to set/correct it — and all the "Re-pull from TMDB" / sync functionality is gated
behind `item.tmdbId != null`, so a mismatched item is stuck.

## Current state (as-is)
- Media Detail "Identity" card renders TMDB id read-only: `<div class="input">${item.tmdbId ?: "—"}</div>`
  (MediaDetail.kt ~line 336).
- `nfoDisabled = item.tmdbId == null || …`; pagebar shows "No TMDB match" badge; repull/sync paths
  rely on `item.tmdbId`.
- `PATCH /api/media/{id}/metadata` accepts title/overview/year/originalTitle/tags/director/studio/
  network — **but not `tmdbId`**.
- `Scanner.rescanMetadata` / `syncMovie` use `item.tmdbId ?: tmdb.search…` — so once an id is set, all
  TMDB flows use it.

## Requirements

### Backend
1. Allow setting/clearing the TMDB id:
   - Extend `PATCH /api/media/{id}/metadata` to accept `tmdbId: Int?` (nullable; `0`/absent leaves it
     unchanged, an explicit value sets it, an explicit `null`/`-1` clears it — define the sentinel
     clearly in the plan). OR add a dedicated `PATCH /api/media/{id}/tmdb-id` `{ tmdbId: Int? }`.
     Recommended: dedicated endpoint for clarity and to keep the metadata edit semantics simple.
2. On set, persist the new `tmdbId` via `store.updateOne`. Do **not** auto-fetch — the user can then
   press "Re-pull from TMDB" to populate metadata using the new id. (Optionally support
   `?repull=true` to fetch immediately.)
3. Optional validation: a lightweight TMDB lookup to confirm the id resolves for the item's kind
   (movie vs tv); on failure return 422 with a message. If skipped, document that an invalid id simply
   yields no results on the next repull.

### Frontend — Media Detail
4. Make the TMDB id in the Identity card editable:
   - Replace the read-only div with an `<input type="number">` + a small **"Save"** button (and/or
     reuse the dirty-indicator pattern from Phase 9).
   - On save, call the endpoint; on success re-render the detail page.
5. After a TMDB id is set on a previously-unmatched item, the pagebar badge flips to "TMDB matched",
   the "Re-pull from TMDB" button becomes usable, and NFO-write enable/disable (`nfoDisabled`)
   recomputes accordingly.
6. Provide a convenience: a **"Search TMDB ↗"** link prefilled with the item title/year
   (`https://www.themoviedb.org/search?query=...`) so the user can find the correct id quickly.
7. Validate input is a positive integer client-side; show inline error otherwise.

## Notes
- This unblocks repull/sync for items that scanned without a provider id and weren't matched by the
  title/year search.
