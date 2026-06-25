# Phase 33 — Jellyfin ⇄ NFO Drift Detection (FR-DR1)

## Problem
After initial scan, Jellyfin's metadata can diverge from what jellystructure stored — e.g. an admin
edits the title or year directly in Jellyfin, or a third-party importer changes the TMDB match.
The operator has no way to see the discrepancy without switching apps.

## Solution
`GET /api/media/{id}/drift` fetches the live Jellyfin item and compares `title`, `year`, and `tmdbId`
against the stored `MediaItem`. Returns a list of `DriftField(field, inJellyfin, inDb)` objects.

The MediaDetail page calls this on load (in the background) and shows an amber banner listing the
divergent fields if any are found. The banner points the operator to "Re-pull from Jellyfin…" to
absorb Jellyfin's version.

## Implementation
### Backend
- `GET /api/media/{id}/drift` in `MediaRoutes` — fetches Jellyfin item via `JellyfinClient.getItem`,
  compares title / year / tmdbId, responds with list of `DriftField` objects (empty = no drift)

### Frontend
- `MediaApi.getDrift(id)` → `List<DriftField>`
- `loadDrift(id)` suspend function — populates `#drift-banner` with amber warning listing each
  divergent field (Jellyfin value vs. DB value)
- `renderDetailView` triggers `loadDrift` on page load alongside `loadArtworkStatus`
