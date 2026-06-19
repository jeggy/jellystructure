# Phase 35 — System Health Panel (FR-HC1)

**Status:** Planned

## Problem
Settings shows a **"Test connections"** button, but there's no endpoint behind it (the spec-vs-source
audit flagged `POST /api/config/test-connection` as documented-but-missing — now struck from
`plan.md`). Operators have no single place to confirm the moving parts are healthy: Jellyfin, TMDB,
the external binaries (`ffmpeg`/`ffprobe`/`mkvpropedit`), the qBittorrent guard, library path mapping,
and artwork-cache disk space.

## Current state (as-is)
- `GET /api/config/path-check` exists (Phase 15). `GET /api/health` is a bare liveness check.
- TMDB / Jellyfin clients exist; `SeedingGuard.check` can report `Unreachable`. No aggregate check.

## Requirements

### Backend
1. `GET /api/health/full` → a structured report the panel renders:
   ```json
   {
     "jellyfin":   { "ok": true,  "detail": "204 · 38ms" },
     "tmdb":       { "ok": true,  "detail": "valid · 39/40 req left" },
     "tools":      { "ok": true,  "detail": "ffmpeg, ffprobe, mkvpropedit present", "missing": [] },
     "qbittorrent":{ "ok": false, "detail": "unreachable", "configured": true },
     "artworkDisk":{ "ok": true,  "detail": "41 GB free" },
     "paths":      { "ok": true,  "detail": "3/3 libraries mapped" }
   }
   ```
   - Jellyfin: ping `/System/Info` (or reuse the existing auth check) with the machine token.
   - TMDB: a cheap authenticated call; surface remaining rate-limit if available.
   - tools: `which`/version probe for each binary; list any missing.
   - qbittorrent: `SeedingGuard` login probe — `configured:false`/skipped when the guard is disabled
     or no `[qbittorrent]`; also validates the configured **path mappings** resolve.
   - artworkDisk: free space on the artwork cache volume.
   - paths: fold in `path-check`.
2. Each sub-check is independent and **fail-soft** — one failing check never 500s the whole report.

### Frontend — Settings (distributed inline status, no consolidated panel)
3. There is **no "System Check" panel**. A single **Test connections** action lives in the **sticky
   action bar** at the top of Settings (alongside Save) and is reachable from any scroll position.
   Running it writes each check's result **inline, next to the thing it verifies**: TMDB key + Jellyfin
   token chips (Connections), media-tooling chip (Scanning), artwork-cache-disk chip (Metadata),
   per-library path-check (Library mapping), and qBittorrent connection + path-mapping status
   (Cross-seed safety).
4. Failures **bubble up** so the operator can find them: each left-nav section shows a red count badge
   (e.g. "Cross-seed safety ①"), and the top **Test connections** button switches to a **danger**
   style reading "issues found · N". Clicking it scrolls to the first failing section. A failing check
   shows amber/red inline but never blocks the rest; when the seeding guard is disabled its qBittorrent
   checks are skipped (no failure, no badge).

## Invariants
- Read-only diagnostics — no config mutation, no writes.
- **Frontend renders server state only** — the panel mirrors the report.

## Out of scope
- Continuous health polling / status history; alerting (see Phase 36 notifications).
