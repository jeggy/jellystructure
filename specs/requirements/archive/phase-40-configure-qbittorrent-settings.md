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
- `QBittorrentConfig` gains `no_auth: Boolean = false` — skips login when true (see below).
- `QBittorrentClient.login()` accepts HTTP 204 No Content as a success response (auth disabled / localhost bypass on the qBittorrent side); returns `""` as SID.
- `QBittorrentClient.getTorrents()` omits the `Cookie: SID=` header when SID is blank.

### Frontend — `ConfigApi.kt`
- New `QBittorrentPathMapping` and `QBittorrentConfig` DTOs
- `AppConfig` gains `qbittorrent: QBittorrentConfig? = null`
- `QBittorrentTestResult(ok, detail, torrentCount?)` DTO
- `ConfigApi.testQBittorrent(url, username, password)` method
- `QBittorrentConfig` gains `noAuth: Boolean = false` field (`@SerialName("no_auth")`)

### Frontend — Settings.kt (new section: Cross-seed safety)
- Nav item added: "Cross-seed safety"
- `sect-crossseed` card with:
  - Master enable toggle (`qb-enabled-toggle`) — shows/hides credential fields
  - **Disabled state (default):** minimal description only
  - **Enabled state:** URL field; "No authentication" toggle (`qb-no-auth-toggle`) — hides username/password when on; username, password (write-only/masked, hidden when no-auth); "Test connection" button + inline status; path mappings editor (add/remove rows); fail-closed warning note
- `populateForm()` populates QB fields from loaded config; hides `#qb-credential-fields` when `noAuth`
- `readForm()` builds `QBittorrentConfig` when enabled (`noAuth` flag passed; credentials cleared when no-auth; password sentinel `##KEEP##` when blank and auth enabled)
- `buildToml()` renders `[qbittorrent]` + `[[qbittorrent.path_mappings]]` blocks; omits `username`/`password` when `no_auth = true`
- `renderQbPathMappings()` + `syncQbMappingsFromDom()` helper functions
- Section observer updated to include `sect-crossseed`

## No-auth mode details
qBittorrent can be configured with "Bypass authentication for clients on localhost" or with auth disabled entirely. In these cases:
- The `/api/v2/auth/login` endpoint returns HTTP 204 No Content (not 200 OK)
- Treating 204 as a login failure was a bug — it is now treated as success with no SID
- When `no_auth = true`, login is skipped entirely (fast path)
- Torrents API is called without a Cookie header — qBittorrent accepts this when auth is disabled

## Invariants preserved
- Guard off → `SeedingCheckResult.Unconfigured` → no edits ever blocked (unchanged from Phase 26/37)
- Fail-closed when enabled but unreachable (unchanged from Phase 26)
- Password never returned in GET /api/config response (TOML serialisation only writes it to disk)
