# Phase 35 — System Health Panel (FR-HC1)

**Status:** ✓ Done

## Problem
Settings had a "Test connections" button that only checked Jellyfin + TMDB (a stub route
`/api/connections/test` that no longer matched the real code). Operators had no visibility into
disk space or tool availability (mkvpropedit, ffprobe).

## Solution
Add `GET /api/health/full` which performs five real checks and returns a structured JSON list:
1. Jellyfin connectivity — calls `JellyfinClient.testConnection()`
2. TMDB API key validity — curls TMDB configuration endpoint
3. Disk space — runs `df -BM .` and checks free MB (warn < 1 GB)
4. `mkvpropedit` available — `which mkvpropedit`
5. `ffprobe` available — `which ffprobe`

"Test connections" button in Settings now calls `/api/health/full` and renders an inline table
of all five checks with ✓/✗ badges and detail text.

## Implementation
### Backend
- `GET /api/health/full` in `Server.kt` (alongside simple `/health`) using `runShell()` helper
  (same popen/fgets pattern as `FfprobeRunner`)

### Frontend
- `ConfigApi.getHealthFull()` → `HealthReport(checks: List<HealthCheck>)`
- `HealthCheck(name, ok, detail)` DTO
- Settings "Test connections" handler replaced to call `/api/health/full` and render a full check table
