# Phase 37 — Surface the qBittorrent Seeding Guard (FR-QS1)

**Status:** Planned

## Problem
Phase 26 added the `SeedingGuard` that blocks `mkvpropedit`/`ffmpeg` on a file currently being seeded
(409 `Blocked` / 503 `Unreachable`), but it's invisible: config-only, and a blocked edit just surfaces
a toast after the fact. Operators don't know the guard is active, or *why* an edit was refused.

## Current state (as-is)
- `SeedingGuard.check` runs at the five mkvpropedit/ffmpeg call sites; frontend handles 409/503 with a
  toast (Phase 26 §6). No proactive indication.
- No endpoint reports guard status or whether a specific item's file is seeded.

## Requirements

### Backend
1. `GET /api/health/full` (Phase 35) already includes a `qbittorrent` check — reuse it for the
   global "guard active / unreachable / unconfigured" state.
2. `GET /api/media/{id}/seeding` → `{ state: "allowed" | "blocked" | "unreachable" | "unconfigured",
   torrentName?: string }` for the item's file(s), so the detail page can pre-warn before an edit.
   (TV: evaluate per episode file as needed.)

### Frontend
3. **Tracks & order tab** shows a small **guard chip** (e.g. "⛨ guard active") when qBittorrent is
   configured; a tooltip explains edits to a seeded file are blocked to protect torrent hashes.
4. When `seeding.state == "blocked"`, show a **"seeded — edits blocked"** badge near the track controls
   and disable set-default/language/reorder for that file, naming the torrent.
5. A blocked attempt (409) and an unreachable guard (503) still produce clear toasts (Phase 26),
   now consistent with the proactive chip/badge.
6. **Settings → Cross-seed safety** (Phase 40) is where the guard is enabled/configured and reflects
   the live guard state from the health check.

## Invariants
- Detection only — never auto-unseed or auto-break hardlinks (constitution / Phase 26 scope).
- **Fail-closed** stays: enabled-but-unreachable = blocked (surfaced as 503 + amber state). When the
  guard is **disabled/unconfigured**, cross-seed safety is not part of media management and edits are
  never blocked.

## Out of scope
- The qBittorrent config UI itself — that's **Phase 40**.
- Other torrent clients.
