# Phase 39 — Subtitle Management (FR-SUB1)

## Features

### Forced flag editing (MKV only)
Toggle the `flag-forced` property on any subtitle track via mkvpropedit — instantly, no re-mux required.

- **Backend**: `MkvpropeditRunner.setForced(filePath, forcedStreamIndex, sameTypeIndices)` — clears all subtitle forced flags and sets exactly one, matching the same pattern as `setDefault`.
- **Route**: `POST /api/media/{id}/tracks/forced` — body `{specifier, forced}`. Validates kind == SUBTITLE and container == MKV. Seeding guard checked. History record written.
- **Frontend API**: `MediaApi.setForcedFlag(id, specifier, forced): Boolean`

### UI surface
- **Media Detail → Tracks tab**: subtitle rows gain a clickable "forced / not forced" badge for MKV files. Badge turns green when forced. Clicking toggles the flag via the API without a page reload. Wired with `wireForcedToggles(container, mediaId, scope)` (internal fun in MediaDetail.kt).
- **Track Order sub-page** (`/track-order`): both movie and episode subtitle tables gain a "Forced" column with the same toggle badge. Table header updated to include the column.

## Implementation

### Backend
- `MkvpropeditRunner.kt`: new `setForced()` method
- `TrackRoutes.kt`: `POST /api/media/{id}/tracks/forced` route (inline request class `SetForcedRequest`)

### Frontend
- `MediaApi.kt`: `setForcedFlag()` API method
- `MediaDetail.kt`: 
  - `buildTracksTable()` now accepts `mediaId` and `filePath` params; renders forced toggle buttons for MKV subtitle rows
  - `wireForcedToggles()` (internal) wires click handlers for `[data-forced-toggle]` buttons
  - Wired on tracks tab switch and on initial render when tracks tab is active
- `TrackOrder.kt`:
  - Both `trackRowHtml()` locals (movie and episode views) render forced toggle for MKV subtitle tracks
  - Subtitle table headers updated to include "Forced" column
  - `wireForcedToggles()` called after DOM injection in both movie and episode views
