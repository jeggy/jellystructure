# Phase 37 — Surface the qBittorrent Seeding Guard (FR-QS1)

**Status:** ✓ Done

## Problem
Phase 26 added the seeding guard that blocks mkvpropedit/ffmpeg, but operators had no way to see
whether a file was currently seeded before attempting a track edit. The block arrived as an error
response with no advance warning in the UI.

## Solution
`GET /api/media/{id}/seeding` — calls `SeedingGuard.check()` for the item's file path and returns
`SeedingStatus { status, torrentName?, detail? }` where status ∈ `{unconfigured, allowed, blocked, unreachable}`.

The Tracks tab in MediaDetail loads this on tab-open and on initial page-load (when `?tab=tracks`).
A guard banner is shown above the tracks table:
- **blocked**: red banner with torrent name — track edits blocked
- **unreachable**: amber banner — qBittorrent not reachable, proceed at risk
- **allowed/unconfigured**: no banner shown

## Implementation
### Backend
- `GET /api/media/{id}/seeding` in `MediaRoutes` — routes `SeedingCheckResult` to `SeedingStatus` DTO

### Frontend
- `SeedingStatus` DTO + `MediaApi.getSeedingStatus(id)`
- `loadSeedingStatus(id)` fills `#seeding-guard-banner` div in Tracks tab
- Tracks tab HTML gains `#seeding-guard-banner` div before the card
- Tab switch handler and initial render trigger `loadSeedingStatus` for movie Tracks tab
