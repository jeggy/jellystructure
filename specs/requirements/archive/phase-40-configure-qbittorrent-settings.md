# Phase 40 — Configure qBittorrent in Settings (FR-QC1)

**Status:** ✓ Done

## Changes from the "config-only, no UI" Phase 26 decision

Phase 26 kept qBittorrent config-only (TOML only). This phase adds a full Settings UI section so
operators can toggle the guard on/off and manage credentials without touching the config file.

## What changed

### `QBittorrentConfig.enabled` default
Changed from `true` → `false`. New installs ship with the guard **off** by default.

### Backend
- `POST /api/config/test-qbittorrent` — receives `{url, username, password}`, attempts login + torrent list, returns `{ok, detail, torrentCount?}`. Never persists. Uses a temporary `QBittorrentConfig` object.
- `PUT /api/config` — handles `password == "##KEEP##"` sentinel: keeps the stored password instead of overwriting.
- `startServer()` now accepts `qbClient: QBittorrentClient?` (default null) and passes it to `configureConfigRoutes()`.
- `Main.kt`: extracts `QBittorrentClient` as a named val so it can be shared between `SeedingGuard` and `startServer`.

### Frontend — `ConfigApi.kt`
- New `QBittorrentPathMapping` and `QBittorrentConfig` DTOs
- `AppConfig` gains `qbittorrent: QBittorrentConfig? = null`
- `QBittorrentTestResult(ok, detail, torrentCount?)` DTO
- `ConfigApi.testQBittorrent(url, username, password)` method

### Frontend — Settings.kt (new section: Cross-seed safety)
- Nav item added: "Cross-seed safety"
- `sect-crossseed` card with:
  - Master enable toggle (`qb-enabled-toggle`) — shows/hides credential fields
  - **Disabled state (default):** minimal description only
  - **Enabled state:** URL, username, password (write-only/masked), "Test connection" button + inline status, path mappings editor (add/remove rows), fail-closed warning note
- `populateForm()` populates QB fields from loaded config
- `readForm()` builds `QBittorrentConfig` when enabled (password sentinel `##KEEP##` when blank)
- `buildToml()` renders `[qbittorrent]` + `[[qbittorrent.path_mappings]]` blocks in TOML preview
- `renderQbPathMappings()` + `syncQbMappingsFromDom()` helper functions
- Section observer updated to include `sect-crossseed`

## Invariants preserved
- Guard off → `SeedingCheckResult.Unconfigured` → no edits ever blocked (unchanged from Phase 26/37)
- Fail-closed when enabled but unreachable (unchanged from Phase 26)
- Password never returned in GET /api/config response (TOML serialisation only writes it to disk)
