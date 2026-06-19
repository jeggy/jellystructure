# Phase 40 — Configure qBittorrent in Settings (FR-QC1)

**Status:** Planned · _revises the Phase 26 "config-only, no UI" decision._

## Problem
Phase 26 added the `SeedingGuard` but deliberately kept it **config-only** (`[qbittorrent]` in
`config.toml`, no UI). That makes the single most safety-critical integration invisible and
edit-by-hand. We now want it **configurable from Settings**, with an explicit master switch — and the
clear rule that **when qBittorrent is not configured/enabled, the guard and all cross-seed safety are
simply not part of media management** (edits proceed without checking qBittorrent).

## Current state (as-is)
- `[qbittorrent]` (url, username, password, `enabled`, `path_mappings[]`) is read from `config.toml`
  only. `SeedingGuard` consumes it; nothing in the UI reads or writes it.
- Config round-trips via `GET /api/config` + `PUT /api/config` (full `AppConfig`).
- `AppConfig` serialization currently treats `[qbittorrent]` as present/absent; we need a stable
  `enabled` flag so the UI can toggle without dropping the saved credentials.

## Requirements

### Backend
1. Ensure `QBittorrentConfig` has an explicit **`enabled: Boolean`** (default `false`) plus `url`,
   `username`, `password`, and `pathMappings: [{local, remote}]`. Toggling `enabled` off must **not**
   discard saved url/credentials/mappings (so re-enabling is one click).
2. The guard's effective state is **`enabled && config present`**: when `enabled == false` (or the
   section is absent), `SeedingGuard.check` returns **allowed** for everything — no qBittorrent call,
   no blocking — and `GET /api/media/{id}/seeding` (Phase 37) returns `"unconfigured"`.
3. `POST /api/config/test-qbittorrent` (or fold into Phase 35 `health/full`) — attempts a login with
   the **submitted** values and validates path mappings, returning `{ ok, detail, torrentCount?,
   mappingsResolved? }`. Surfaced through the single top **Test connections** action (Phase 35) — the
   Cross-seed safety section has **no** separate test button; it shows an inline status line updated by
   that global check. Read-only; never persists.
4. Saving is the existing `PUT /api/config` (the page sends the full `AppConfig`); the password is
   write-only/masked on read (return a sentinel, keep the stored value when unchanged).

### Frontend — Settings → Cross-seed safety (new section)
5. A section titled **Cross-seed safety** (badge "qBittorrent") with a **master enable toggle**.
6. **Disabled (default) empty-state:** a clear panel — "Seeding guard off — edits are never blocked.
   Turn it on to connect qBittorrent and protect seeded files." No connection fields shown.
7. **Enabled:** reveal URL, username, password, a **Test connection** button + live status line, and a
   **Path mappings** editor (local → remote rows, add/remove; longest-match note).
8. A **fail-closed** note: when enabled but unreachable, edits are blocked until it responds or the
   guard is disabled.
9. The live `config.toml` preview reflects `[qbittorrent] enabled = …` (omitted/false when off).

## Invariants
- **Off = no guard:** with the guard disabled/unconfigured, cross-seed safety is not part of media
  management and **no edit is ever blocked** on its account.
- **Fail-closed when on:** enabled-but-unreachable blocks edits (carries over from Phase 26/37).
- Credentials are sensitive: masked on read, never logged; config stays TOML via `PUT /api/config`.
- Detection only — the guard never unseeds, moves, or re-hardlinks files.

## Out of scope
- Multiple qBittorrent instances / other torrent clients.
- Auto-discovering path mappings from qBittorrent (manual rows for now).
- Per-torrent overrides or seeding-ratio awareness.
