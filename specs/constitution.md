# Constitution

This document defines the non-negotiable architectural decisions, core principles, and design
constraints for **jellystructure**. All development — human or AI-assisted — must adhere to these
rules. If a decision conflicts with this document, the document wins unless it is formally updated
here first.

This is the **source of truth** for architecture. [`plan.md`](plan.md) describes *how* the system is
built today; this document describes what must always be true.

---

## Vision

jellystructure is a self-hosted web system that **fully replaces Jellyfin's built-in metadata
scraper**. Once this system is running, users should never need to use "Refresh Metadata" inside
Jellyfin. The system owns:

- NFO files (Kodi/Jellyfin-compatible XML): `movie.nfo`, `tvshow.nfo`, `episodedetails.nfo`
- All image assets (posters, backdrops, logos, per-episode stills)
- Media file track ordering (audio and subtitle default flags)
- Config management (editable from the web UI)

The metadata language is driven by the actual audio tracks present in each file — not a global
default — using a language-resolution algorithm backed by TMDB.

### Supported Jellyfin (phase 243)

jellystructure targets **Jellyfin 12.0 and later**. Below that is unsupported: not blocked, not
shimmed, undefined. The comparison is on the **major version only**, which is what makes the
12.0-vs-12.1 distinction academic — no code path can tell them apart.

**No compatibility branches, ever.** No code path may branch on Jellyfin's version to support an
older one. Where a newer Jellyfin changes a contract, the product moves; it does not carry both. A
version check is permitted for *reporting* — a `/api/health/full` check and an advisor finding — and
for refusing a known-broken combination, and that refusal may produce a finding or a health failure
and nothing else: it may never select a request shape, a header form, an endpoint or a payload.
`scripts/check-jellyfin-version-use.sh` enforces this mechanically.

Verification notes in code state the version **and the date** they were measured against
(`verified against Jellyfin 12.1.0, 2026-09-19`), so a reader can tell which are still true.
After a Jellyfin major upgrade, run [`specs/jellyfin-upgrade-checklist.md`](jellyfin-upgrade-checklist.md).

---

## Technology Mandates

These choices are fixed. Do not introduce alternatives without updating this document.

### Backend — Kotlin Native
- Target: `linuxX64` / `linuxArm64`, producing a native binary (no JVM)
- HTTP server: **Ktor with CIO engine** — the only async engine available outside the JVM; no Netty, no Tomcat
- File I/O: **kotlinx-io** (`org.jetbrains.kotlinx:kotlinx-io-core`) — `java.io` and `java.nio` do not exist in Kotlin Native; kotlinx-io is the JetBrains-maintained KMP I/O library
- Config: **ktoml + kotlinx.serialization** — TOML format, `@Serializable` data classes, no runtime reflection
- Local DB: **SQLDelight with native SQLite driver** — `media`, `session`, `media_history`, `scan_state`, `scan_processed` tables in `jellystructure.db` (env `DB_FILE`). Config stays TOML.
- Subprocess: **Kotlin Native process API** — wraps `ffmpeg`, `ffprobe`, `mkvpropedit`
- Real-time: **Ktor WebSockets** — push status updates from backend to frontend; no HTTP polling

### Frontend — Kotlin WASM
- Compiled to WebAssembly (WasmGC)
- DOM interaction via **kotlinx.browser** — direct manipulation of the browser's native HTML tree
- **No Compose Multiplatform for Web** — canvas-based rendering is explicitly rejected (breaks accessibility, SEO, and CSS integration)
- Styling: the **`design/app/wf.css` + `app.css`** CSS-variable system (tokens + components), copied
  **verbatim** into the build by the Gradle `syncDesignAssets` task and linked from `index.html`. There
  is **no Tailwind / PostCSS pipeline** — the mockup CSS *is* the production CSS (so new component
  classes are added to `wf.css`)
- State: **kotlinx.coroutines StateFlow** — reactive stores; WS pushes into flows; no client-side router library

### Infrastructure
- Deployment: **Docker Compose** only
- Config persistence: TOML file mounted as a Docker volume at `/config`
- Media access: direct read/write volume mount at `/media` (same UID/GID as the Jellyfin container)
- Network: shared Docker bridge network with Jellyfin for low-latency API calls

---

## Architectural Invariants

### Authentication
Auth follows the Jellyseerr model — there is **no separate Jellystructure account**:
1. Operator signs in with Jellyfin username + password via `POST /Users/AuthenticateByName`.
2. Jellystructure rejects non-admin users (`Policy.IsAdministrator == false`).
3. On success, an opaque session token (generated from `/dev/urandom`) is stored in SQLite and set as an `HttpOnly; SameSite=Lax` cookie.
4. Every `/api/**` route and WebSocket upgrade validates the cookie; missing/expired → 401.
5. The password is **never** persisted. The per-user Jellyfin access token is stored only in the session row, not in config.
6. A separate machine token (`jellyfin_token` in config) is used by background jobs so they work with no user session active.

Before `jellyfin_url` is configured, a one-time setup screen (`/setup`) is served; it 404s once config exists.

### NFO Files
- Format: Kodi-compatible XML
  - Movies: `<movie>` → `movie.nfo` beside the movie file
  - Series: `<tvshow>` → `tvshow.nfo` in the series directory
  - Episodes: `<episodedetails>` → `{S01E03}.nfo` (or matching filename) beside each episode file
- NFOs are written **unlocked** — Jellystructure never writes `<lockdata>`. Jellyfin must be able to re-read them freely.
- Written via kotlinx-io streaming (no full XML tree in RAM)
- Atomic write: write to `.tmp` file then `rename()` to avoid partial reads by Jellyfin

### Artwork Conventions
- Series/Movie: `poster.jpg`, `fanart.jpg`, `clearlogo.png` in the media directory
- Episodes: `{episode-filename-without-ext}-thumb.jpg` beside each episode file (Kodi/Jellyfin convention)

### Language Resolution for TMDB Metadata
This is the core domain logic. It determines which language is used when fetching metadata from TMDB:

1. Run `ffprobe` → parse JSON → build a track list (index, codec, language code)
2. Identify tracks with no language tag → surface them in Triage for manual correction
3. **Per-file resolution**: query TMDB for each language found in the file's audio tracks, in physical track-index order (track 0 first, track 1 next, etc.); the first language that returns a result wins
4. If no language from the file's tracks yields a TMDB result, fall back to the single global `fallback_language` (default `en`); per-library overrides take precedence over the global default
5. Track default flags and ordering are **never changed automatically** — they are changed only via explicit manual action in the UI

**For TV series**: language resolution runs **per episode** on that episode's own audio tracks. The
series (`tvshow.nfo`) uses the **majority** of its episodes' resolved languages. Mixed-language
series surface a distribution view and an override; no writes are blocked by a mix.

### Subprocess Hierarchy
Choose the **least destructive tool** sufficient for the operation:

| Operation | Tool | Rationale |
|---|---|---|
| Change default/forced flags on `.mkv` | `mkvpropedit` | Header-only edit, no bitstream rewrite, milliseconds |
| Remove/reorder tracks, fix incompatible containers | `ffmpeg -c copy` | Remux without re-encoding; use only when mkvpropedit is insufficient |
| Re-encode | `ffmpeg` (full) | Last resort only; never triggered automatically |

`ffprobe` output is always parsed as JSON (`-print_format json -show_streams`). FFmpeg/mkvpropedit
stdout+stderr is streamed to the frontend via WebSocket during long operations.

### Series and Episode Management
- **Discovery**: Jellyfin API is the source of truth for series existence; episodes are discovered by filesystem walk within the series directory (no reliance on `GET /Shows/{id}/Episodes` for file discovery, since Jellyfin may not have probed all episodes)
- **Per-episode**: each episode has its own resolved language, TMDB episode metadata (title, overview, still), and episodedetails.nfo
- **Triage**: Triage is **not a page** (Phase 27). Items needing attention (untagged tracks, multiple-default audio, missing still/overview) are stepped through by a floating, navigation-only **Triage dock** that opens each item's **detail page**; all editing — including series-level metadata/artwork/language and per-episode fixes — happens on media/series detail, not a separate Triage flow
- **Track editing**: every episode supports the same track default and language editing as a movie, from the episode's own file

### Jellyfin API Integration
After completing metadata or file modifications, the system calls Jellyfin's REST API to trigger a
refresh — the user never has to do this manually:
- `POST /Items/{itemId}/Refresh` with `MetadataRefreshMode=FullRefresh`
- HTTP 204 response is forwarded to the frontend over WebSocket as a completion event

### Configuration Shape
TOML sections and their purpose:

```toml
[api_keys]
tmdb_v3_key = ""
jellyfin_token = ""   # machine token for background jobs
jellyfin_url = ""

[language_rules]
fallback_language = "en"

[behavior]
overwrite_nfo = false
fetch_images = true
watch_enabled = false
tell_jellyfin = true   # call Jellyfin refresh after writes
scan_workers = 1       # concurrent items processed (live scale-up/down) — Phase 16
scan_threads = 4       # ffprobe/ffmpeg subprocess pool size (restart required) — Phase 16
scan_episode_cap = 0   # 0 = unlimited; cap episodes ffprobe'd per series on a full scan — Phase 49

[[libraries]]
jellyfin_id = "abc123"
name = "Movies"
collection_type = "movies"
jellyfin_path = "/media/movies"    # path as Jellyfin sees it
local_path = "/mnt/media/movies"   # path as Jellystructure sees it
skip = false
fallback_language = ""             # empty = inherit global

# Optional — qBittorrent seeding guard (Phase 26). When absent or `enabled = false`, the guard is
# disabled and cross-seed safety is **not part of media management** — edits proceed without
# checking qBittorrent. Configurable from Settings → Cross-seed safety (Phase 26 revision).
[qbittorrent]
enabled = true
url = "http://gluetun-seeder:8085"
no_auth = false          # set true when qBittorrent has auth disabled / localhost-bypass
username = "admin"       # ignored when no_auth = true
password = ""            # ignored when no_auth = true

[[qbittorrent.path_mappings]]
local = "/mnt/media/movies"        # path Jellystructure sees
remote = "/media/movies"           # path qBittorrent sees (longest match wins)
```

Config is read with `GET /api/config` and replaced wholesale with `PUT /api/config` (the UI sends the
full `AppConfig` as JSON; Ktor converts it to TOML and writes `/config/config.toml`).

Library paths are **not static**. They are auto-discovered from the Jellyfin API
(`GET /Library/VirtualFolders`) after a successful connection test and stored as `[[libraries]]`
TOML array entries. There is no static `[paths]` section. The operator assigns the local mount path
per library; all other fields come from Jellyfin. Config is readable and writable from the web UI;
changes are posted as JSON to Ktor, converted to TOML, and written to `/config/config.toml`.

---

## Real-Time UI Contract

- The frontend establishes a WebSocket connection on load
- All long-running backend operations (scans, downloads, ffmpeg jobs) emit granular JSON progress events over this connection
- The frontend updates specific DOM nodes reactively — no full page reloads
- **The frontend renders server-pushed state only** — no derived or optimistic local state. (Desync from local accumulation was a past bug.) The ambient scan dock's counter is seeded from `GET /api/scan/status`, not accumulated purely from WS events.
- On each `ItemScanned` event during a scan, the Library grid appends/updates the item without waiting for scan completion
- **Tab navigation within a detail page uses `replaceState`** (`Router.updateQuery(..., replace = true)`). The URL updates silently — no `hashchange` fires, no page re-render occurs. Only the tab panel toggles in the DOM. Back/Forward navigate between pages, not between tabs. Deep-linking and refresh still reconstruct the correct tab from the URL.
- **Settings pagebar is sticky** (`id="set-pagebar"`, `position: sticky; top: 0; z-index: 60`) so Save and Test Connections are always reachable. The left section-nav sticks below it (`position: sticky; top: 88px`, where 88 px is the pagebar height).

---

## Visual System (Aurora direction)

The `design/app/` directory contains HTML/CSS mockups that are the **visual target** for the
Kotlin/WASM frontend. They use a custom CSS-variable system in `wf.css` (tokens + components) and
`app.css` (shell). The real frontend **ships `wf.css` + `app.css` verbatim** — the Gradle
`syncDesignAssets` task copies them into the dist and `index.html` links them. There is **no Tailwind
pipeline**; the mockup CSS is the production CSS. The mockup `.html` files themselves are not served.

- Dark-primary with light toggle; persisted in `localStorage` as `js-theme` (`light` / `dark` / `system`)
- Jellyfin-style purple→blue gradient accent
- Type: Space Grotesk (display) · Sora (UI) · JetBrains Mono (code/IDs)
- Color tokens of note: `--ok` (green, resolved/success), `--warn` (amber, dirty/mixed), `--bad` (red, error/destructive)

---

## Testing Strategy

### Integration Tests
- **Playwright** in Docker (`mcr.microsoft.com/playwright`) against the full stack
- Element selection via data attributes (`data-tab`, `data-specifier`) and semantic text matchers — not fragile CSS selectors
- Post-operation validation runs `ffprobe` to assert the file actually changed (not just the UI)
- Mock TMDB and Jellyfin servers run in Docker Compose for deterministic results

### Test Data
A setup script (`scripts/build-fixtures.sh`) downloads and prepares test media before tests run:
- Source films: **Big Buck Bunny**, **Sintel**, **Tears of Steel** (Blender Foundation, open license)
- **Sintel**: `fra` injected as wrong default audio (must be repaired to `eng`)
- **Big Buck Bunny**: 2 untagged audio tracks (→ triage queue)
- **Tears of Steel**: split into 3 episode files under a fake series directory (→ series episode tab)
- **Babel Fish**: mixed-language series fixture (→ language mix badge)

---

## Key Invariants (do not break)

1. **Track flags are never changed automatically** — only via explicit user action in the UI.
2. **Language resolution is per-file** — audio tracks in physical index order, first TMDB hit wins.
3. **NFO writes are atomic** — `.tmp` + `rename()`.
4. **Frontend renders server-pushed state only** — no derived/optimistic state.
5. **No Compose for Web** — DOM manipulation only via `kotlinx.browser`.
6. **Tag lifecycle — JS tags always survive** — non-JS tags are sourced externally; tags defined in
   `js_tags` (`JsTagStore.nameSet()`) are always preserved on every path. **Full scan**: `item.tags` =
   Jellyfin `Tags` + existing JS tags (Jellyfin authoritative for non-JS). **Re-pull from TMDB**: TMDB
   **keywords** + existing JS tags (drops stale Jellyfin-only tags). **Re-pull from Jellyfin**: Jellyfin
   `Tags` ∪ everything existing (additive). See Phase 19 §15 + Phase 51.
   **Predecessor rule (Phase 199):** "preserved" means preserved from the item's predecessor row, and
   across a slug/id rename the predecessor is **not** the row under the fresh item's own id — it is the
   stale row the rename replaces. A guard that reads the fresh item's own id back out of the store sees
   `null` on exactly that rename and preserves nothing.
