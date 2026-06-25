# Phase 81 — Artwork tab: selecting a new image updates which one is "on disk" (FR-AM3)

> On the Movie/Series detail **Artwork** tab, picking a different poster/backdrop from the TMDB
> gallery appears to do nothing — the **ON DISK** ribbon stays stuck on the previously-chosen image
> and the new one can never take its place.

## Problem

The artwork gallery marks exactly one candidate as **ON DISK**. After the user clicks a different
candidate and it saves successfully, the gallery reloads but still flags the *old* image as on disk;
the freshly-chosen image is never marked, so it feels impossible to overwrite the current art.

## Root cause

The save round-trip writes the file but never records *which* image was chosen.

1. The file IS written correctly. `POST /api/media/{id}/artwork/candidates/save`
   (`MediaRoutes.kt`) → `ArtworkDownloader.saveAsset` → `download()` atomically replaces the on-disk
   asset (`poster.jpg` / `fanart.jpg`) via temp-file + `rename`. ✅
2. But the handler then returns `artwork.check(item)` (file-existence only) and **never updates
   `item.posterPath` / `item.backdropPath`** in the store.
3. The gallery's "on disk" flag is derived by comparing each candidate's TMDB `file_path` against
   that stored field:
   - `GET /api/media/{id}/artwork/candidates` reads `onDiskSource = item.posterPath` (or
     `backdropPath`) and `mapCandidates` sets `onDisk = (img.filePath == onDiskSource)`
     (`MediaRoutes.kt`).
   - The frontend reloads candidates after every save (`selectArtTarget` → `galleryFetch` →
     `MediaApi.getArtworkCandidates`, `MediaDetail.kt`).

Because `posterPath` still holds the *previous* TMDB path, the reload re-marks the old image and
leaves the new one unflagged — even though the bytes on disk did change. There is **no** download
guard at fault; the only defect is the missing write-through of the chosen path.

## Fix

In the `candidates/save` handler, after a successful `saveAsset`, persist the chosen source into the
item and save it, then return the updated status:

- `poster` → `item.copy(posterPath = source)`, `backdrop` → `item.copy(backdropPath = source)`,
  via `MediaStore.updateOne`.
- Guard: only TMDB paths (`source.startsWith("/")`, e.g. `/abc.jpg`) map to those fields, since the
  app renders posters via the TMDB CDN (`$TMDB_IMG${posterPath}`). A **custom http(s) URL** save
  still overwrites the on-disk file but leaves `posterPath`/`backdropPath` untouched (it isn't a TMDB
  path). `clearlogo` / `banner` have no item field — their on-disk state stays file-existence based.

No frontend change: the gallery already reloads candidates after save, so the corrected
`posterPath`/`backdropPath` immediately moves the **ON DISK** ribbon to the chosen image.

## Acceptance

- Selecting a different TMDB poster/backdrop moves the **ON DISK** ribbon to that image, and the old
  one is no longer flagged — repeatedly switchable.
- The chosen poster/backdrop also shows as the item's art elsewhere (library grid, detail header).
- Saving a **custom image URL** overwrites the on-disk file and reports success without corrupting
  `posterPath` (poster rendering elsewhere is unaffected).
- `clearlogo` / `banner` saving still works (file-existence based on-disk state, unchanged).
