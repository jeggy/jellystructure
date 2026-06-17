# CLAUDE.md

## What is Jellystructure?

**jellystructure** is a self-hosted web application that fully replaces Jellyfin's built-in metadata scraper. Once running, users never need to use "Refresh Metadata" in Jellyfin. The system owns:

- **NFO files** (Kodi/Jellyfin-compatible XML): `movie.nfo`, `tvshow.nfo`, `episodedetails.nfo`
- **Artwork** (poster, fanart, logo, per-episode stills)
- **Media track management** (audio/subtitle default flags and language tags via `mkvpropedit`/`ffmpeg`)
- **Config** (editable from the web UI, stored as TOML)

The metadata language is driven by the actual audio tracks in each file — not a global default — using a language-resolution algorithm backed by TMDB.

---

## Tech Stack

### Backend — Kotlin Native (`linuxX64`, `linuxArm64`)
- Produces a single native binary (no JVM)
- **HTTP server**: Ktor with CIO engine (`io.ktor:ktor-server-cio`)
- **File I/O**: `kotlinx-io` (`org.jetbrains.kotlinx:kotlinx-io-core`) — `java.io` does not exist in Kotlin Native
- **Config**: `ktoml` + `kotlinx.serialization` — TOML format, `@Serializable` data classes
- **Local DB**: SQLDelight with native SQLite driver — sessions, scan cache, audit log, triage
- **Subprocess**: Kotlin Native process API for `ffprobe`, `ffmpeg`, `mkvpropedit`
- **Real-time**: Ktor WebSockets — push `JobEvent` JSON from backend to all connected frontends

### Frontend — Kotlin WASM (`wasmJs`)
- Compiled to WebAssembly (WasmGC), served as static assets by the backend
- **DOM interaction**: `kotlinx.browser` — direct HTML tree manipulation, no Compose for Web
- **Styling**: Tailwind CSS via Webpack + PostCSS in the Gradle build; class names scanned from Kotlin source at build time
- **Routing**: hash-based (`#/route`) via `Router.kt`; `StateFlow` for reactive state
- **Real-time**: WebSocket client in `Shell.kt` (dock) and per-page where needed

### Infrastructure
- **Deployment**: Docker Compose only
- **Config persistence**: TOML file mounted at `/config/config.toml`
- **Media access**: volume mount at `/media` (same UID/GID as Jellyfin container)
- Backend serves the compiled WASM bundle as a SPA (SPA fallback for all non-`/api` routes)

### Build
- Single Gradle KMP project; `build.gradle.kts` at root
- `./gradlew linkDebugExecutableLinuxX64` — build backend binary
- `./gradlew wasmJsBrowserDevelopmentWebpack` — build frontend bundle
- `./gradlew runDev` — build both + start backend (port 9505) + webpack dev server (port 8081)
- Dev config/sessions written to `./config/`

---

## Source Layout

```
src/
  commonMain/kotlin/dev/jellystructure/
    model/Media.kt          — MediaItem, Episode, Track, MediaKind, TrackKind
    jobs/JobEvent.kt        — sealed JobEvent (Started, ItemScanned, FileProgress, Finished)
    resolver/LanguageResolver.kt — language priority list logic

  linuxX64Main/kotlin/dev/jellystructure/
    Main.kt                 — entrypoint, wires all services
    config/
      AppConfig.kt          — @Serializable config data classes
      ConfigStore.kt        — read/write config.toml
    auth/
      AuthPlugin.kt         — Ktor auth plugin (cookie validation)
      JellyfinClient.kt     — Jellyfin REST API calls
      SessionService.kt     — opaque session tokens in SQLite
      Models.kt             — Jellyfin API response models
    media/
      Scanner.kt            — Jellyfin-driven discovery + ffprobe + TMDB match
      MediaStore.kt         — SQLite CRUD for MediaItem/Episode
      MediaHistory.kt       — audit log of actions per media ID
      FfprobeRunner.kt      — parse ffprobe JSON output → List<Track>
      FfmpegRunner.kt       — set default track / language / remux
      MkvpropeditRunner.kt  — header-only edits for .mkv files
      ArtworkDownloader.kt  — download poster/fanart/logo/stills from TMDB
      ScanTracker.kt        — running/cancelled/count state for active scan
    nfo/NfoWriter.kt        — write movie.nfo / tvshow.nfo / episodedetails.nfo
    jobs/WsBroadcaster.kt   — fan-out JobEvent JSON to all WebSocket sessions
    server/
      Server.kt             — embeds Ktor, installs plugins, registers all routes
      routes/
        AuthRoutes.kt       — POST /api/auth/login, /api/auth/logout, /api/auth/me
        SetupRoutes.kt      — GET /api/setup/status, POST /api/setup/connect
        ConfigRoutes.kt     — GET/PATCH /api/config, POST /api/config/test-connection
        JellyfinRoutes.kt   — GET /api/jellyfin/libraries
        MediaRoutes.kt      — /api/media/** (list, detail, scan, nfo, artwork, tracks, episodes)
        TrackRoutes.kt      — /api/tracks/** (plan, set-default, set-language for movies)
        TriageRoutes.kt     — GET /api/triage, POST /api/triage/:id/assign-language
        LanguageRoutes.kt   — GET/PATCH /api/language/settings
    watcher/FolderWatcher.kt — inotify-based folder watcher (when watch_enabled = true)
    tmdb/TmdbClient.kt      — TMDB v3 REST calls (search, movie details, TV details, episodes)

  wasmJsMain/kotlin/dev/jellystructure/
    Main.kt                 — WASM entry, bootstraps auth check → Shell or Login
    App.kt                  — global navigate() + re-render
    Router.kt               — hash-based routing (window.location.hash)
    JsInterop.kt            — JS interop helpers
    api/
      ApiClient.kt          — shared fetch wrapper (JSON, credentials)
      AuthApi.kt            — login/logout/me calls
      ConfigApi.kt          — config read/write, connection test
      MediaApi.kt           — media list, detail, scan, nfo, artwork, tracks, triage count
    ui/
      Shell.kt              — sidebar nav, theme toggle, ambient scan dock, WS connection
      Login.kt              — /login — Jellyfin admin sign-in form
      Setup.kt              — /setup — first-run Jellyfin URL + token entry
      Dashboard.kt          — /dashboard — stats cards, recent activity, batch actions
      Library.kt            — /library — paginated media grid, filter/sort/search
      MediaDetail.kt        — /media/:id — full detail: metadata, tracks, artwork, NFO, resolver trace
      Triage.kt             — /triage — untagged track queue (movies)
      SeriesTriage.kt       — /triage/series/:id — multi-step series triage flow
      TrackOrder.kt         — /track-order — manual track default/order editor
      Language.kt           — /language — language resolver settings + live preview
      Settings.kt           — /settings — library mapping, API keys, behavior flags
      Activity.kt           — /activity — real-time scan console + operation audit log
```

---

## Data Models (commonMain)

### `MediaItem`
```kotlin
data class MediaItem(
    val id: String,              // slug: "movie-title-2024"
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val kind: MediaKind,         // MOVIE | TV_SHOW
    val path: String,            // local filesystem path
    val jellyfinId: String?,     // Jellyfin item UUID
    val tmdbId: Int?,
    val originalLanguage: String?,
    val resolvedLanguage: String?,   // winner from language-resolution algorithm
    val posterPath: String?,         // TMDB image path (not local)
    val backdropPath: String?,
    val overview: String?,
    val genres: List<String>,
    val tags: List<String>,
    val director: String?,
    val studio: String?,
    val network: String?,
    val tracks: List<Track>,         // movie: all tracks; TV: tracks of first episode
    val episodes: List<Episode>,     // TV only; empty on list responses
    val issueCount: Int,             // untagged audio/subtitle tracks
    val languageMix: Boolean,        // true = audio lang sets differ across episodes
    val scannedAt: Long,
)
```

### `Track`
```kotlin
data class Track(
    val streamIndex: Int,    // ffprobe stream index
    val specifier: String,   // e.g. "a:0", "s:1"
    val kind: TrackKind,     // VIDEO | AUDIO | SUBTITLE | DATA
    val codec: String,
    val language: String?,   // null = untagged → triage
    val title: String?,
    val default: Boolean,
    val forced: Boolean,
)
```

### `Episode`
```kotlin
data class Episode(
    val filename: String,
    val path: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val tracks: List<Track>,
    val issueCount: Int,
    val resolvedLanguage: String?,
    val title: String?,
    val overview: String?,
    val stillPath: String?,      // TMDB still image path
    val tmdbEpisodeId: Int?,
)
```

---

## Configuration Shape

```toml
[api_keys]
tmdb_v3_key   = ""
jellyfin_token = ""   # machine token for background jobs
jellyfin_url   = ""

[language_rules]
fallback_language = "en"

[behavior]
overwrite_nfo  = false
fetch_images   = true
watch_enabled  = false
tell_jellyfin  = true   # call Jellyfin refresh after writes

[[libraries]]
jellyfin_id      = "abc123"
name             = "Movies"
collection_type  = "movies"
jellyfin_path    = "/media/movies"    # path as Jellyfin sees it
local_path       = "/mnt/media/movies" # path as Jellystructure sees it
skip             = false
fallback_language = ""                # empty = inherit global
```

Library mappings are **not static**. They are auto-discovered from Jellyfin (`GET /Library/VirtualFolders`) and stored as `[[libraries]]` TOML array entries. The operator assigns `local_path`; all other fields come from Jellyfin.

---

## API Routes (all under `/api`)

### Auth
| Method | Path | Description |
|--------|------|-------------|
| POST | `/auth/login` | Jellyfin admin sign-in; sets HttpOnly session cookie |
| POST | `/auth/logout` | Clear session |
| GET | `/auth/me` | Current user profile |

### Setup
| Method | Path | Description |
|--------|------|-------------|
| GET | `/setup/status` | Returns `configured: bool`; 404s once config exists |
| POST | `/setup/connect` | Test Jellyfin URL + token and persist initial config |

### Config
| Method | Path | Description |
|--------|------|-------------|
| GET | `/config` | Full `AppConfig` as JSON |
| PATCH | `/config` | Partial update; writes to `config.toml` |
| POST | `/config/test-connection` | Test Jellyfin + TMDB connectivity |

### Jellyfin
| Method | Path | Description |
|--------|------|-------------|
| GET | `/jellyfin/libraries` | Discover libraries from Jellyfin API |

### Media
| Method | Path | Description |
|--------|------|-------------|
| GET | `/media` | Paginated list (`kind`, `filter`, `search`, `sort`, `page`, `pageSize`); episodes stripped |
| GET | `/media/{id}` | Full item including episodes |
| GET | `/media/{id}/history` | Audit log for item |
| GET | `/media/{id}/nfo` | Raw NFO XML |
| POST | `/media/{id}/nfo` | Write NFO (+ episodedetails for TV); trigger Jellyfin refresh |
| GET | `/media/{id}/artwork` | Check artwork existence |
| POST | `/media/{id}/artwork` | Fetch artwork from TMDB |
| POST | `/media/{id}/artwork/upload` | Multipart upload (poster/fanart/logo) |
| PATCH | `/media/{id}/metadata` | Edit title, overview, year, tags, director, studio, network |
| PATCH | `/media/{id}/language` | Override resolved language |
| POST | `/media/{id}/repull` | Re-fetch TMDB metadata without re-probing |
| GET | `/media/{id}/episodes/stills` | Check still existence per episode |
| POST | `/media/{id}/episodes/stills` | Fetch all missing episode stills |
| POST | `/media/{id}/episodes/nfo` | Write episodedetails.nfo for all episodes |
| POST | `/media/{id}/episodes/{epFilename}/still/upload` | Upload episode still |
| GET | `/media/{id}/episodes/{epFilename}/tracks/plan` | Preview mkvpropedit/ffmpeg command |
| POST | `/media/{id}/episodes/{epFilename}/tracks/default` | Set default track |
| POST | `/media/{id}/episodes/{epFilename}/tracks/language` | Set track language |
| PATCH | `/media/{id}/episodes/{epFilename}/metadata` | Edit episode title/overview |
| POST | `/scan` | Start background library scan |
| POST | `/scan/cancel` | Cancel running scan |
| GET | `/scan/status` | Running state + item count |
| GET | `/stats` | Movie/TV/episode counts, issue count, NFO coverage % |
| GET | `/activity/recent` | Recent audit log entries |
| POST | `/jellyfin/refresh` | Trigger Jellyfin library refresh |
| POST | `/media/batch/artwork` | Fetch missing artwork for all items |

### Tracks (movies)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/tracks/{id}/plan` | Preview mkvpropedit/ffmpeg command |
| POST | `/tracks/{id}/default` | Set default track |
| POST | `/tracks/{id}/language` | Set track language |

### Triage
| Method | Path | Description |
|--------|------|-------------|
| GET | `/triage` | Items with untagged tracks |
| GET | `/triage/count` | `{total: N}` for sidebar badge |
| POST | `/triage/{id}/assign-language` | Assign language to untagged track(s) |

### Language Settings
| Method | Path | Description |
|--------|------|-------------|
| GET | `/language/settings` | Current `fallback_language` |
| PATCH | `/language/settings` | Update `fallback_language` |

### WebSocket
| Path | Description |
|------|-------------|
| `/ws` | Bidirectional; backend pushes `JobEvent` JSON; client sends nothing |

---

## Language Resolution Algorithm

1. Run `ffprobe` → parse JSON → build `List<Track>`
2. Identify tracks with `language == null` → surface in Triage
3. Per-file: build priority list of audio languages in **physical track-index order** (track 0 first)
4. Query TMDB with each language in order; **first hit with non-blank overview wins**
5. If no hit → fall back to per-library `fallback_language` or global `fallback_language` (default `en`)
6. Track default flags and ordering are **never changed automatically** — only via explicit UI action

**TV series**: language resolution runs **per episode**. The series uses the majority language. Mixed-language series get `languageMix = true`, `resolvedLanguage = null`, and a distribution view in the UI.

---

## Subprocess Tool Selection

| Operation | Tool | Notes |
|-----------|------|-------|
| Set default/forced flags on `.mkv` | `mkvpropedit` | Header-only, milliseconds |
| Remove/reorder tracks, fix containers | `ffmpeg -c copy` | Remux, no re-encode |
| Re-encode | `ffmpeg` (full) | Last resort; never automatic |

`ffprobe` always called with `-print_format json -show_streams`. FFmpeg/mkvpropedit stdout+stderr streamed to frontend via WebSocket during long operations.

---

## UI Pages & Routes

| Route | File | Description |
|-------|------|-------------|
| `/login` | `Login.kt` | Jellyfin admin credentials sign-in |
| `/setup` | `Setup.kt` | First-run Jellyfin URL + machine token; disappears once configured |
| `/dashboard` | `Dashboard.kt` | Stats cards, recent activity, scan trigger, batch action chips |
| `/library` | `Library.kt` | Paginated media grid with filter/sort/search |
| `/media/:id` | `MediaDetail.kt` | Full item: metadata, tracks, artwork, NFO preview, resolver trace (FR-M5) |
| `/triage` | `Triage.kt` | Untagged track queue for movies |
| `/triage/series/:id` | `SeriesTriage.kt` | Multi-step series triage (series-level → per-episode) |
| `/track-order` | `TrackOrder.kt` | Manual track default/reorder editor; shows before/after diff |
| `/language` | `Language.kt` | Language resolver settings + live in-WASM preview |
| `/settings` | `Settings.kt` | Library mapping, API keys, behavior flags |
| `/activity` | `Activity.kt` | Real-time scan console + full operation audit log |

---

## WebSocket Protocol (JobEvent)

All events are JSON; `type` field is the discriminator:

```json
{"type":"started",    "jobId":"scan-1234", "total":-1}
{"type":"item_scanned","jobId":"scan-1234", "item":{...MediaItem...}}
{"type":"progress",   "jobId":"scan-1234", "file":"/path/ep.mkv", "current":3, "total":10}
{"type":"file_done",  "jobId":"scan-1234", "file":"/path/ep.mkv", "ok":true}
{"type":"finished",   "jobId":"scan-1234", "succeeded":42, "failed":0}
```

The Shell renders an **ambient dock** that appears when a scan is running and hides on the Activity page (which has its own full console view).

---

## Authentication

- No separate Jellystructure accounts — Jellyfin admin credentials only
- `POST /Users/AuthenticateByName` → Jellystructure rejects non-admins (`Policy.IsAdministrator == false`)
- Success → opaque session token (from `/dev/urandom`) stored in SQLite; set as `HttpOnly; SameSite=Lax` cookie
- Every `/api/**` route + WebSocket upgrade validates the cookie; missing/expired → 401
- Password is **never** persisted. Per-user Jellyfin access token stored only in the session row
- Machine token (`jellyfin_token`) used by background jobs (no user session needed)
- Before `jellyfin_url` is configured, `/setup` is served; it 404s once config exists

---

## NFO File Conventions

- Format: Kodi-compatible XML; always include `<lockdata>true</lockdata>`
- Movies: `<movie>` → `movie.nfo` beside the movie file
- Series: `<tvshow>` → `tvshow.nfo` in the series directory
- Episodes: `<episodedetails>` → `{S01E03}.nfo` beside each episode file
- Atomic write: write to `.tmp` then `rename()` (avoids partial reads by Jellyfin)
- Written via kotlinx-io streaming (no full XML tree in RAM)

### Artwork file conventions
- Series/Movie: `poster.jpg`, `fanart.jpg`, `clearlogo.png` in media directory
- Episodes: `{episode-filename-without-ext}-thumb.jpg` beside each episode file

---

## Scanner Logic

1. Call `GET /Items` on Jellyfin API → list of Movie/Series items with paths and provider IDs
2. Match each item's Jellyfin path to a configured `[[libraries]]` entry via prefix
3. Translate `jellyfin_path` → `local_path` for actual filesystem access
4. For movies: `ffprobe` → language resolution → TMDB movie details
5. For series: walk directory for episode files → `ffprobe` each → per-episode TMDB episode details → majority-language resolution → series TMDB details
6. For large series (>100 episodes): probe a spread sample of 100 files
7. Emit `ItemScanned` WS event per item; frontend appends/updates grid in real-time
8. On scan complete: call Jellyfin library refresh

---

## Design & Mockups

The `design/app/` directory contains HTML/CSS mockups that are the **visual target** for the Kotlin/WASM frontend. They use a custom CSS-variable system in `wf.css`/`app.css` — tokens map to Tailwind on implementation.

### Visual system (Aurora direction)
- Dark-primary with light toggle; persisted in `localStorage` as `js-theme`
- Jellyfin-style purple→blue gradient accent
- Type: Space Grotesk (display) · Sora (UI) · JetBrains Mono (code/IDs)
- All screens share `design/app/wf.css` (tokens + components) and `design/app/app.css` (shell)

The real frontend uses **Tailwind CSS**; mockups use CSS variables as the visual spec — they are not served directly.

---

## Development Phases

| Phase | Status | Focus |
|-------|--------|-------|
| 0 | ✓ Done | Scaffolding — Gradle KMP, Docker Compose, native binary + WASM bundle |
| 1 | ✓ Done | Config, DB, auth (Jellyfin sign-in, session cookie, admin gate), library mapping |
| 2 | ✓ Done | Jellyfin-driven discovery, ffprobe track data, TMDB match, Library + Media Detail UI |
| 3 | ✓ Done | NFO write (movie + tvshow), artwork download, language resolver, language settings UI |
| 4 | ✓ Done | Track editing (mkvpropedit/ffmpeg), triage queue, live WebSocket scan, Jellyfin refresh |
| 5 | ✓ Done | Folder watcher, Playwright E2E tests, fixture builder, series episode tab |
| 6 | ✓ Done | Per-episode TMDB metadata, episodedetails.nfo, episode stills, per-episode track editing |
| 7 | ✓ Done | Persistent scan state + resume — survive page refresh and backend restart (FR-S1) |
| 8 | ✓ Done | Three-way theme picker — Light / Dark / System with OS sync (FR-T1) |
| 9 | ✓ Done | Per-field dirty indicators + diff popup on Media Detail metadata editing (FR-D2) |
| 10 | Planned | External links on Media Detail — open item in Jellyfin and TMDB (FR-X1) |
| 11 | Planned | Language pickers — searchable dropdowns replacing all free-text language inputs (FR-L1) |
| 12 | Planned | Simplify TV series language UI — merge overview banner into compact left-rail card (FR-U1) |
| 13 | Planned | Sync single media item — targeted rescan for one item, season, or series (FR-S2) |
| 14 | Planned | Fix library path matching — diagnostics and path-check in Test connections (FR-B1) |
| 15 | Planned | Multi-worker scanner — configurable coroutine workers + thread pool (FR-W1) |
| 16 | Planned | Activity log backend — persistent log, category filter, active worker display (FR-A1) |
| 17 | Planned | Settings page cleanup — fix nav, section reorganisation, advanced options (FR-C1) |
| 18 | Planned | Studios, Networks, Genres & Tags metadata page + tags backend (FR-M1) |

---

## Testing

- **Playwright** in Docker against the full stack (mock TMDB + Jellyfin servers)
- Element selection via `data-tab`, `data-specifier` attributes and semantic text matchers
- Post-operation validation: `ffprobe` asserts the file actually changed
- Test fixtures: Big Buck Bunny (2 untagged audio tracks), Sintel (wrong default audio `fra`→`eng`), Tears of Steel (3-episode fake series), Babel Fish (mixed-language series)
- Build fixtures: `scripts/build-fixtures.sh`

---

## Key Invariants (do not break)

1. **Track flags are never changed automatically** — only via explicit user action in the UI
2. **Language resolution is per-file** (audio tracks in physical index order, first TMDB hit wins)
3. **NFO writes are atomic** (`.tmp` + `rename()`)
4. **Frontend renders server-pushed state only** — no derived/optimistic state; desync was a past bug
5. **No Compose for Web** — DOM manipulation only via `kotlinx.browser`
6. **Config source of truth** is `CONSTITUTION.md`; this file supersedes it only on the `[paths]` → `[[libraries]]` change

---

## Upcoming Requirements

### Phase 7 — Persistent Scan State & Resume (FR-S1)

#### Problem
`ScanTracker` is entirely in-memory. When the user refreshes the page mid-scan the dock counter resets to 0 because the frontend was accumulating `ItemScanned` WS events locally. When the backend restarts mid-scan, the scan state is gone entirely — no way to resume.

#### Current state (as-is)
- `ScanTracker`: three `var` fields (`running`, `lastCount`, `cancelRequested`) — no persistence
- `ScanStatus` DTO: `{ running: Boolean, lastCount: Int? }` — no state machine, no history
- Frontend dock: initialises `dockScanned = status.lastCount` on page load — correct when backend is alive, zero after a restart
- Cancel button exists; "resume" concept does not exist
- The scan iterates `jellyfinClient.getItems()` in the order Jellyfin returns items; processed item IDs are not recorded anywhere

#### Requirements

**Backend — scan state persistence**
1. Introduce a scan state file (`scan-state.json`) alongside the media cache. It is a single-entry file, not a log. Stored in the same config directory as `media-cache.json`.
2. The state machine has four statuses: `IDLE`, `RUNNING`, `CANCELLED`, `COMPLETE`.
3. The state file schema:
   ```json
   {
     "status": "CANCELLED",
     "jobId": "scan-1720000000",
     "startedAt": 1720000000,
     "updatedAt": 1720001234,
     "totalSeen": 142,
     "processedIds": ["abc123", "def456", ...]
   }
   ```
4. `processedIds` is the list of Jellyfin item IDs that were fully scanned and emitted in the current (or last) scan run. This is the resume checkpoint.
5. `ScanTracker` is rewritten to read/write this file atomically (`.tmp` + rename). It loads the file on startup.
6. On backend startup: if the persisted status is `RUNNING` (i.e. the process was killed mid-scan), transition it to `CANCELLED` — the scan was interrupted.
7. `GET /api/scan/status` returns the full state: `{ status, jobId, startedAt, updatedAt, totalSeen, processedCount }`. `processedIds` is not returned (can be large); only the count is.
8. `POST /api/scan` — starts a **new** scan: clears `processedIds`, sets status to `RUNNING`, resets count to 0.
9. `POST /api/scan/resume` — resumes from checkpoint: re-fetches the Jellyfin item list, skips any item whose `jellyfinId` is in `processedIds`, processes the rest. Status goes `RUNNING` again. If status is not `CANCELLED`, returns 409.
10. `POST /api/scan/cancel` — unchanged in surface; now also persists status `CANCELLED`.
11. On scan complete: status → `COMPLETE`, `processedIds` cleared (no checkpoint needed).
12. The scan loop records each successfully scanned Jellyfin ID into `processedIds` and persists after every item (or every N items for performance — batched flush every 10 items is acceptable).

**Frontend — richer scan UI**
1. `ScanStatus` model gains `status: String` (replaces / extends `running: Boolean`; `running` stays for backwards-compat as `status == "RUNNING"`).
2. The Dashboard scan section shows different UI depending on status:
   - `IDLE` / `COMPLETE`: single "Run scan" button
   - `RUNNING`: "Cancel" button + live count from dock
   - `CANCELLED`: two buttons: **"Continue scan"** (resumes, skips processed) and **"New scan"** (starts fresh). A note shows how many items were already processed: "Scan paused — 87 items done, N remaining."
3. The ambient dock reads `lastCount` / `totalSeen` from `GET /api/scan/status` on page load, so the count is correct even after a page refresh. It no longer increments solely from WS events — WS events update a local counter that is added to the server's base count.
4. On Shell initialisation: if scan status is `CANCELLED`, a dismissible inline banner appears in the sidebar status area: "Last scan paused — continue or restart from Dashboard."

**Invariants**
- A resume scan must never re-process an item that was already successfully scanned in this run.
- The `processedIds` list is scoped to one scan run. A new scan always clears it.
- Backend shutdown mid-scan (`RUNNING` → `CANCELLED` on next startup) must be handled without manual intervention.

---

### Phase 8 — Three-Way Theme Picker (FR-T1)

#### Problem
The current theme toggle is a wide button at the bottom of the sidebar showing a sun/moon icon and "Toggle theme" text. It is visually heavy, only supports two states (light/dark), and defaults to `dark` regardless of the user's OS preference.

#### Current state (as-is)
- `Shell.kt`: `<button id="theme-btn" class="theme-btn wide">` with `<span class="ico">` (sun/moon SVG) + "Toggle theme" text
- `localStorage` key: `js-theme`, values: `"light"` or `"dark"`
- Default when nothing is stored: `"dark"` (hardcoded in `renderShell`)
- `data-theme` attribute on `<html>` drives CSS variable resolution
- Design mockup (`app-shell.js`) has the same structure: `div.switcher > div.sw-row > button.theme-btn.wide`

#### Requirements

1. Replace the wide button with a compact three-segment pill control. The three segments are labelled **Light**, **Dark**, **System** (in that order, left to right). No icons required in the labels — keep it text-only for clarity.
2. **System** (sync with OS) is the default when nothing is stored in `localStorage`.
3. In System mode:
   - Read `window.matchMedia('(prefers-color-scheme: dark)').matches` to determine the initial theme.
   - Add a `change` listener on that media query; if the user's OS switches, the app switches immediately without a page reload.
   - `data-theme` on `<html>` is updated to `"dark"` or `"light"` accordingly — the CSS layer does not need a new token for "system".
4. `localStorage` stores `"light"`, `"dark"`, or `"system"`. Default (missing key) is treated as `"system"`.
5. The active segment is visually highlighted (filled background, full-contrast label). Inactive segments are muted.
6. The pill must fit inside the existing sidebar footer area without overflowing. Target width: fills the sidebar padding area, same as the current button. Height: compact (~28 px).
7. Apply the theme before the DOM is painted (existing `data-booting` pattern in `app-shell.js` / `Main.kt` bootstrap) to prevent flash of wrong theme. System mode at boot must resolve the OS preference synchronously.
8. The sun/moon SVG constants in `Shell.kt` are removed. The old `theme-btn` and its `click` handler are replaced by the segmented control and its handler.

---

### Phase 9 — Per-Field Dirty Indicators + Diff Popup (FR-D2)

#### Problem
On the Media Detail page, when the user edits metadata fields the only feedback is a "Save changes" button that appears at the top of the card. There is no indication of *which* fields changed, and no way to see the old vs new value before committing.

#### Current state (as-is)
- `checkDirty()` in `MediaDetail.kt`: compares current DOM values to `origValues` map (populated at render time from the server `MediaItem`). If any differ → shows `#save-metadata-btn`; if none differ → hides it.
- No per-field CSS change.
- Tracked fields: `edit-title`, `edit-year`, `edit-original-title`, `edit-overview`, `edit-director`, `edit-studio`.
- Tags are managed separately (add/remove chips); they trigger `saveBtn.style.display = "inline-flex"` directly but are not in `origValues`.
- "Save changes" → `PATCH /api/media/{id}/metadata` → server updates in-memory store.
- "Save → disk" / "Save & tell Jellyfin" → `POST /api/media/{id}/nfo` — these write NFO, independent of the in-memory store state.

#### Requirements

**Per-field dirty indicator**
1. When a field's current DOM value differs from its original server value, that `<input>` or `<textarea>` gets a CSS class `field-dirty` added. When it matches again, the class is removed.
2. The `field-dirty` class applies: orange/amber left border (3 px, `var(--warn)` color token) + a very subtle background tint (`var(--warn)` at ~6% opacity). The effect must work in both light and dark themes.
3. `checkDirty()` is extended to apply/remove `field-dirty` per element, in addition to showing/hiding the save button.
4. Tags: when the current tag set differs from the original tag set (by membership, order-insensitive), the tags container (`#tags-section`) gets `field-dirty` styling on its border/wrapper — not on individual chips. Added tags are shown with a subtle green tint chip; removed tags (present in original but removed from DOM) are not re-rendered but the dirty state is still captured.
5. The dirty indicator clears automatically when the page re-renders after a successful save (i.e. re-render from server state resets `origValues`).

**Diff popup**
1. Each dirty field shows a small diff trigger inline — a compact icon button (e.g. a `⟷` or `≠` symbol) placed to the right of the field label, visible only when the field is dirty.
2. Clicking the diff trigger opens a modal overlay (centred, max-width ~560 px, darkened backdrop) showing:
   - A header: field label + "Changes"
   - Two labelled sections: **Before** (original server value) and **After** (current edited value)
   - Word-level diff highlighting: removed words/substrings in red with strikethrough, added words/substrings in green — same convention as GitHub's inline diff
3. For numeric fields (year), show the raw before/after values without word diff.
4. For tags: show **Before** as a row of chips in red, **After** as a row of chips in green, with chips that exist in both shown in neutral colour.
5. The modal is dismissed by: clicking the backdrop, pressing Escape, or clicking a close button (×) in the modal header.
6. Only one diff popup can be open at a time. Opening a second one closes the first.
7. No backend changes. The diff is computed entirely in the frontend from `origValues` vs current DOM.

**Scope**
- This feature applies only to the metadata editing section of the Media Detail overview tab.
- Episode metadata editing (title/overview per episode in the episodes tab) is out of scope for this phase.
- The "Save → disk" buttons are not affected; they already operate independently of the in-memory dirty state.

---

### Phase 10 — External Links on Media Detail (FR-X1)

#### Problem
The media detail page has no direct links to the source systems. Users have to manually navigate to Jellyfin or TMDB to cross-reference.

#### Current state (as-is)
- `item.jellyfinId` is available but only used for internal API calls
- `item.tmdbId` is displayed in the Identity card as plain text
- The Jellyfin base URL is available via `ConfigApi.get()?.apiKeys.jellyfinUrl`
- The detail page already calls `ConfigApi.get()` once on load (for `fallbackLang`)

#### Requirements

1. In the Media Detail page `pagebar`, add two compact ghost icon-link buttons, placed to the left of "Save → disk":
   - **"Jellyfin ↗"** — visible only when `item.jellyfinId != null`. URL: `{jellyfinUrl}/web/index.html#!/details?id={jellyfinId}`. Opens in a new tab.
   - **"TMDB ↗"** — visible only when `item.tmdbId != null`. URL for movies: `https://www.themoviedb.org/movie/{tmdbId}`, for TV: `https://www.themoviedb.org/tv/{tmdbId}`. Opens in a new tab.
2. The Jellyfin URL is read from the already-fetched config (`ConfigApi.get()`). If `jellyfinUrl` is blank the Jellyfin button is omitted entirely.
3. Style: `btn sm ghost` with small text, consistent with the existing "Re-pull from TMDB" button.
4. No new API routes. All data already exists on `MediaItem` and config.

---

### Phase 11 — Language Pickers (FR-L1)

#### Problem
All language inputs are free-text fields. Users must know the exact BCP-47/ISO 639 code. There is no discovery, autocomplete, or validation.

#### Current state (as-is)
- `lang-override-input` in MediaDetail: `<input ... maxlength="10">`
- `fallback-language` in Settings: `<input type="text" placeholder="en">`
- Per-library `fallback_language` inputs in Settings library list
- Fallback language input in `Language.kt`

#### Requirements

**Language data**
1. A static lookup table compiled into the WASM bundle (`wasmJsMain`): an array of `(code: String, name: String)` entries covering all languages recognised by TMDB (~185+ entries). Codes are ISO 639-1 (2-letter) where available, falling back to ISO 639-2 (3-letter) for languages without a 2-letter code — matching what TMDB actually uses.
2. Display format in the picker: `"Language name (code)"`. Examples: `"English (en)"`, `"Faroese (fo)"`, `"Faroese (fao)"`, `"Japanese (ja)"`.
3. The stored/returned value is always the raw code string, unchanged from current behaviour.

**Picker component**
4. A reusable function `renderLanguagePicker(inputEl: HTMLInputElement)` that upgrades an existing `<input>` element into a searchable picker in-place:
   - The input displays the full `"Name (code)"` label for the current value when known; falls back to showing the raw code if not in the table.
   - On focus/click, a dropdown panel opens below the input, containing a search field pre-filled with the current text and a scrollable list of matching entries.
   - List filters live as the user types — matches by language name or code prefix/substring (case-insensitive).
   - Selecting an entry sets the underlying `<input>` value to the raw code and closes the dropdown. Fires an `input` event so existing change listeners still work.
   - Keyboard navigation: Arrow keys move selection, Enter confirms, Escape closes without selecting.
   - Clicking outside closes the dropdown.
   - Max visible rows: 8, with overflow scroll.
5. Apply the picker to every free-text language input in the app:
   - `lang-override-input` in `MediaDetail.kt`
   - `fallback-language` in `Settings.kt` → Language/Metadata section
   - Per-library `fallback_language` inputs in `Settings.kt` library list
   - The fallback language input in `Language.kt`
6. Picker width matches the original input width (`width: 100%`). Dropdown is `position: absolute`, z-indexed above other content.
7. No backend changes. Values stored and sent to the API are unchanged code strings.

---

### Phase 12 — Simplify TV Series Language UI (FR-U1)

#### Problem
The TV series overview tab has two redundant cards that both show language distribution:
1. A large `tvOverviewBanner` at the top of the main content area with a prose explanation, distribution bars, and the language override input.
2. A smaller `seriesLangCard` in the left rail that also shows distribution bars and the current NFO language — but without the override input.

These duplicate each other and the prose explanation is unnecessary.

#### Current state (as-is)
- `tvOverviewBanner`: shown for both uniform and languageMix series. Contains: status badge, prose paragraph, distribution bars, episode/track counts, triage link (for languageMix), and `tmdbLangHint` (the override input + save button).
- `seriesLangCard`: in the left rail under the poster. Contains: top-4 distribution bars, read-only "tvshow.nfo language" display. No override input.

#### Requirements

1. **Remove** `tvOverviewBanner` entirely from the main content area for TV shows.
2. **Replace** `seriesLangCard` with a combined card that absorbs all functionality from the removed banner while remaining compact enough for the 220 px left rail.
3. The new combined left-rail card must contain, in order:
   - `<h4>` "Series language" with an inline status badge: green `"Uniform"` or amber `"Mixed"`.
   - Subtitle line: `"{N} episodes · {M} tracks total"`.
   - All language distribution bars (not capped at 4), each showing language code, percentage bar, and track count. The primary/resolved language bar is highlighted in `var(--ok)`.
   - If untagged tracks exist (`votes["?"] > 0`): a small inline link to Series Triage.
   - Separator line.
   - Language override control using the **language picker** (Phase 11): label "NFO language", picker pre-filled with `item.resolvedLanguage`, and "Save" button. Same save/API logic as the existing `lang-override-btn`.
   - Small muted note: "Used for TMDB metadata and tvshow.nfo writes. Does not affect audio tracks."
4. No functionality removed. All existing event handlers for the language override save are preserved.
5. The "Uniform" state still shows the green badge, but no full-width prose banner in the main content area.

---

### Phase 13 — Sync Single Media Item (FR-S2)

#### Problem
The only targeted re-fetch is "Re-pull from TMDB" which re-fetches TMDB only (no ffprobe). A full library scan re-probes everything. There is no way to do a targeted full rescan (ffprobe + TMDB) for one item, one season, or one series.

#### Requirements

**Backend**

1. New endpoint: `POST /api/media/{id}/sync`
   - Accepts optional JSON body: `{ "scope": "series" | "episodes" }`. Default is `"episodes"`.
   - For movies: always runs full probe + TMDB fetch (body ignored).
   - For TV series with `scope: "episodes"`: re-probes all episode files with `ffprobe`, re-fetches TMDB episode details, re-runs language resolution. Updates all episodes and top-level series metadata.
   - For TV series with `scope: "series"`: re-fetches TMDB series-level metadata only, no `ffprobe`. Fast path.
   - Emits `ItemScanned` WS event on completion.
   - Returns the updated `MediaItem`.
   - Returns 409 if a scan is already running (`scanTracker.running`).

2. New endpoint: `POST /api/media/{id}/seasons/{seasonNumber}/sync`
   - Accepts optional JSON body: `{ "scope": "season" | "episodes" }`. Default is `"episodes"`.
   - `scope: "season"`: re-fetches TMDB season-level metadata only.
   - `scope: "episodes"`: re-probes all episode files in that season + re-fetches per-episode TMDB details.
   - Returns `{ "synced": N }`.
   - Returns 409 if a scan is already running.

3. Both endpoints reuse `Scanner.scanMovie` / `Scanner.scanSeries` internals (extract shared logic as needed). They do not go through `ScanTracker` start/complete state transitions — they are point operations that run synchronously in the request, with WS progress events.

**Frontend — Media Detail pagebar**

4. Add a **"Sync ↻"** button in the `pagebar`, to the left of "Re-pull from TMDB". Style: `btn sm ghost`.
5. For **movies**: clicking immediately calls `POST /api/media/{id}/sync`. Button shows "Syncing…" while in progress. On success, re-renders the detail view. On 409, shows "Scan already running".
6. For **TV series**: clicking opens a modal with:
   - Title: "Sync series"
   - Option 1 **"Series metadata only"** — description: "Re-fetches TMDB series info. Fast." — calls `scope: "series"`.
   - Option 2 **"Full sync"** — description: "Re-probes all episode files and re-fetches TMDB. May take several minutes for large series." — calls `scope: "episodes"`.
   - Cancel button.
7. For **seasons** (in the episodes tab, each season header row): add a small `↻` icon button at the right of the season header. Clicking opens a modal:
   - Option 1 **"Season metadata only"** — calls season-scope endpoint with `scope: "season"`.
   - Option 2 **"Season + all episodes"** — calls with `scope: "episodes"`.
   - Cancel button.
8. During any sync operation, the triggering button is disabled and shows "Syncing…". WS events stream to the Activity page in parallel.

---

### Phase 14 — Fix Library Path Matching for Movies (FR-B1)

#### Problem
Movies are not matched to any library during scans. Logs show: `[WARN] No matching library for '/media/movies/...'`.

#### Root cause (identified)
`Scanner.kt` uses:
```kotlin
val prefix = lib.jellyfinPath.ifBlank { lib.localPath }
prefix.isNotBlank() && jellyfinPath.startsWith(prefix)
```
When `jellyfinPath` is blank, the fallback is `localPath` (the Jellystructure-side mount path, e.g., `/mnt/data/movies`). But Jellyfin reports paths from its own container perspective (e.g., `/media/movies`). The `startsWith` check silently fails when these differ — which is the common case when Jellyfin and Jellystructure have different volume mount paths.

#### Requirements

**Backend diagnostics**

1. When no library matches a Jellyfin item path, the existing `[WARN]` log is augmented with all configured match prefixes: `[WARN] No matching library for '/media/movies/...' — configured prefixes: [/media/tvshows, /mnt/data/movies]`. This makes misconfiguration immediately diagnosable in logs.
2. New endpoint: `GET /api/config/path-check` — returns per-library diagnostics:
   ```json
   [
     {
       "name": "Movies",
       "jellyfinPath": "/media/movies",
       "localPath": "/mnt/data/movies",
       "matchPrefix": "/media/movies",
       "localExists": true
     }
   ]
   ```
   `matchPrefix` is `jellyfinPath` if non-blank, else `localPath`. `localExists` calls `SystemFileSystem.exists(Path(localPath))`.

**Settings UI — "Test connections" enhancement**

3. After clicking **"Test connections"**, if the Jellyfin connection succeeds, automatically call `GET /api/config/path-check` and render the results inline below the Jellyfin/TMDB badges.
4. Each library is shown as a compact row: library name, `matchPrefix` value, and one of:
   - Green badge `"Local path found ✓"` — `localExists == true`.
   - Red badge `"Local path not found ✗"` — `localExists == false`. Hint text: `"Check that localPath is mounted correctly in the Jellystructure container"`.
   - Amber badge `"jellyfinPath not set — using localPath as match prefix"` — when `jellyfinPath` is blank.
5. This path-check result section is also shown after clicking **"Save"** in Settings (call the endpoint after a successful config save).
6. In the library mapping card, each entry shows a read-only inline line: **"Matching prefix:"** `{matchPrefix}`. This updates live as the user edits the Jellyfin path field.

**Settings UI — clarity improvement**

7. In the library mapping card, add a `<details>` help element (collapsed by default) with the text: "Jellyfin path is the path as Jellyfin sees the library root inside its container. Local path is the same location from Jellystructure's perspective. If both containers share an identical volume mount, these are the same value. If they differ, set both correctly — Jellyfin path is used to match scanned items, local path is used to access files."

---

### Phase 15 — Multi-Worker Scanner (FR-W1)

#### Problem
The scanner processes items sequentially. Large libraries are slow. Users want configurable parallelism at two levels: the number of concurrent scan workers (coroutines) and the size of the thread pool they run on.

#### Confirmed threading model
Kotlin Native coroutines work identically to the JVM model when using multi-threaded dispatchers. `Dispatchers.Default.limitedParallelism(N)` is available and behaves as expected. Different dispatchers can be used for different parts of the application without issues.

#### Config

1. Add two new fields to the `[behavior]` section of `AppConfig` / `Behavior`:
   - `scan_workers = 1` — number of concurrent coroutine workers consuming scan items. Valid range: 1–32. Default: 1. **Hot-configurable**: changing this during an idle period takes effect on the next scan start.
   - `scan_threads = 4` — size of the `limitedParallelism` thread pool used by the scan dispatcher. Valid range: 1–32. Default: 4. **Requires application restart** to take effect — the dispatcher is created once at startup.
2. Both fields are persisted in `config.toml` under `[behavior]`.

**Backend — worker pool**

3. At application startup in `Main.kt`, create a dedicated scan dispatcher: `val scanDispatcher = Dispatchers.Default.limitedParallelism(config.behavior.scanThreads)`. This dispatcher is injected into `MediaRoutes` and used for all scan coroutines.
4. The `runScan` function is refactored to a producer/consumer pattern:
   - **Producer** (single coroutine): fetches Jellyfin items, filters skips, sends `JellyfinItem`s into a `Channel<JellyfinItem>(capacity = Channel.UNLIMITED)`, then closes the channel.
   - **Workers** (N coroutines, where N = `configStore.current.behavior.scanWorkers` at scan start): each reads from the channel, calls `scanner.scanMovie` or `scanner.scanSeries`, stores the result, records progress. Workers exit naturally when the channel is exhausted.
   - All worker coroutines are launched on `scanDispatcher`.
5. `ScanTracker.recordProcessed()` is called from multiple workers concurrently — guard `_processedIds` with a `Mutex` (from `kotlinx.coroutines.sync`).
6. `WsBroadcaster.broadcast()` is called from multiple workers concurrently — guard with a `Mutex` if not already thread-safe.
7. `GET /api/scan/status` response gains a field: `"activeWorkers": Int` — the number of worker coroutines currently running (tracked with an `AtomicInt` incremented on worker start, decremented on worker end).
8. The number of workers (`scan_workers`) is read once at scan start and fixed for the duration of that scan run. It is not dynamically changed mid-scan (see invariants).

**Dynamic worker reconfiguration (stretch — may defer)**

9. If implementing dynamic reconfiguration: when `scan_workers` is changed in Settings while a scan is running:
   - Increase: launch additional worker coroutines immediately against the same in-flight channel.
   - Decrease: set a `targetWorkers` atomic; workers check it after finishing each item and self-terminate if `workerIndex >= targetWorkers`.
10. If dynamic reconfiguration is deferred, a note is shown in the Settings UI: "Worker count change takes effect on the next scan start."

**Settings UI**

11. In Settings → Scanning section (Phase 17), add two new fields:

    **Scan workers**
    - Label: "Scan workers"
    - Control: numeric input `min=1 max=32`.
    - Hint: "Number of items processed concurrently during a scan. Takes effect on the next scan start."

    **Scan threads**
    - Label: "Scan thread pool size"
    - Control: numeric input `min=1 max=32`.
    - Hint: "Size of the thread pool used by scan workers. **Requires application restart to take effect.**"
    - **Restart required indicator**: when the current `scan_threads` value in the form differs from the value the running backend was started with (read from `GET /api/config` which returns the live effective value alongside the persisted value), show a prominent amber banner below this field:
      ```
      ⚠ Thread pool size has changed — restart the application for this to take effect.
      Current: 4 threads · Pending: 8 threads
      ```
    - The backend exposes the currently-active thread count via a new field `effectiveScanThreads: Int` in `GET /api/config`. This is the value read at startup, not the persisted value (they may differ if the user saved a new value without restarting).

**Invariants**
- `scan_workers` changing does not interrupt a running scan; it applies on next scan start.
- `scan_threads` always requires restart; the thread pool is immutable after creation.
- The UI must clearly distinguish between the two so users understand which requires a restart.

---

### Phase 16 — Activity Log Backend (FR-A1)

#### Problem
The Activity page only shows live WS events for the current browser session. There is no persistent log, no history from past scans, and no structured record of backend operations.

#### Current state (as-is)
- `Activity.kt`: renders live WS events into `#activity-console`. Resets to empty on every page load.
- `MediaHistory.kt`: exists for per-item audit (nfo writes, track changes) but not for scan/system events.
- All backend logging uses bare `println()`.

#### Requirements

**Backend — activity log store**

1. New `ActivityLog` service (`media/ActivityLog.kt`). Stores entries in SQLite in a new table `activity_log`:
   ```sql
   CREATE TABLE activity_log (
     id INTEGER PRIMARY KEY AUTOINCREMENT,
     ts INTEGER NOT NULL,
     level TEXT NOT NULL,      -- "info" | "warn" | "error"
     category TEXT NOT NULL,   -- "scan" | "nfo" | "artwork" | "track" | "system"
     message TEXT NOT NULL,
     media_id TEXT             -- nullable
   );
   ```
2. Bounded: keep the last 10,000 entries. Trim oldest entries when inserting beyond the limit.
3. Replace the key `println()` calls in `Scanner`, `NfoWriter`, `ArtworkDownloader`, `FfmpegRunner`, `MkvpropeditRunner` with `activityLog.log(level, category, message, mediaId?)`. The existing `println` is kept alongside (log to both stdout and the database).
4. New endpoint: `GET /api/activity/log?page=1&pageSize=100&category=scan&level=warn` — paginated log entries, newest first. Response: `{ entries: [...], total: Int }`.
5. New endpoint: `DELETE /api/activity/log` — truncates the `activity_log` table.
6. New WS event type `log_line` emitted for every `activityLog.log()` call: `{ "type": "log_line", "level": "info", "category": "scan", "message": "...", "mediaId": "..." }`. This feeds the Activity page console in real time.

**Frontend — Activity page**

7. On page load, call `GET /api/activity/log?pageSize=200` and populate the `#activity-console` with recent history. Newer live events are appended as they arrive via WS.
8. Add a filter bar above the console with category chips: **All** / **Scan** / **NFO** / **Artwork** / **Tracks** / **System**, plus an **"Errors only"** toggle. Filtering is client-side (hides non-matching lines).
9. **Active workers display**: in the pagebar, show a live badge: `"Workers: {activeWorkers}/{configuredWorkers}"` (e.g., `"Workers: 3/5"`). Reads from `GET /api/scan/status` on page load; updates on WS `started`/`finished` events. Shows `"Workers: —"` when idle.
10. The existing **"Clear"** button clears the in-memory display only (does not touch the database).
11. A new **"Clear log"** button (ghost, smaller, separate from "Clear") calls `DELETE /api/activity/log` after a `confirm()` dialog. Re-fetches and re-renders the (now empty) log.

---

### Phase 17 — Settings Page Cleanup (FR-C1)

#### Problem
1. The sidebar nav links in Settings use `href="#sect-xxx"` anchor links. The app Router intercepts all `hashchange` events and tries to render pages, causing broken navigation.
2. The section structure is flat and missing logical groupings for new settings (scan workers, thread pool from Phase 15).
3. The "Advanced" section is empty placeholder text.

#### Current state (as-is)
- Nav links: `<a href="#sect-connections">`, etc. — trigger Router on click
- Sections: Connections, Library mapping, Language, Behaviour, Advanced
- Behaviour contains: overwrite NFO, fetch images, watch enabled, tell Jellyfin toggles
- Advanced: placeholder text only

#### Requirements

**Navigation fix**

1. Replace the `<a href="#sect-xxx">` nav items with `<button>` or `<span>` elements that call `document.getElementById("sect-xxx")?.scrollIntoView(js("({behavior:'smooth'})"))`. No hash changes → Router not triggered.
2. Use an `IntersectionObserver` to highlight the active nav item as the user scrolls: when a section header enters the viewport, add `active` styling to the corresponding nav button.

**Section reorganisation**

3. Rename, reorder, and restructure the settings sections as follows:

   **Connections** (unchanged)
   - Jellyfin URL, machine token, TMDB key
   - "Test connections" button (now also runs path-check per Phase 14)

   **Library mapping** (unchanged except for Phase 14 additions: match prefix line per library, `<details>` help text)

   **Scanning** (new section)
   - Watch library folders toggle (moved from Behaviour)
   - Scan workers numeric input (Phase 15)
   - Scan thread pool size input with restart-required indicator (Phase 15)

   **Metadata** (replaces old "Language" and "Behaviour" sections)
   - Fallback language picker (Phase 11, moved from Language)
   - Overwrite existing NFO fields toggle
   - Fetch artwork automatically toggle
   - Auto-tell Jellyfin to refresh toggle

   **Advanced**
   - "Danger zone" card with a **"Clear all scanned data"** button: `confirm()` dialog → `DELETE /api/media/all` (new endpoint that wipes the SQLite media store and scan state). The button is styled in red/destructive styling.

4. Remove the standalone "Language" nav section (its content moved to Metadata above).
5. The per-library `fallbackLanguage` inputs use the language picker component (Phase 11).
6. `readForm()` in `Settings.kt` is updated to include `scanWorkers` and `scanThreads` from the new Scanning section.

---

### Phase 18 — Studios, Networks, Genres & Tags (FR-M1)

#### Problem
There is no way to browse media by studio, network, genre, or tag. The tags section on Media Detail is partially wired (chips render) but cannot be reliably added or removed. There is no structured tag system.

#### Requirements

**New route and nav entry**

1. New top-level route `/metadata` → new file `wasmJsMain/.../ui/Metadata.kt`. Add a sidebar nav entry **"Metadata"** with a tag/label icon, positioned between "Language" and "Settings" in the NAV list in `Shell.kt`.
2. The page has a top tab bar with four tabs: **Studios** · **Networks** · **Genres** · **Tags**. Active tab is driven by a `?tab=` query parameter (`/metadata?tab=networks`, etc.). Default tab: Studios.

**Backend aggregation**

3. New endpoint group under `/api/metadata`:
   - `GET /api/metadata/studios?sort=name|count` → `[{ name: String, count: Int }]` — aggregated from `MediaItem.studio` across all items, sorted as requested. Items with `studio == null` are excluded.
   - `GET /api/metadata/networks?sort=name|count` → same shape from `MediaItem.network`. TV shows only.
   - `GET /api/metadata/genres?sort=name|count` → `[{ name: String, count: Int }]` — from `MediaItem.genres` (list field; each genre counted once per item containing it).
   - `GET /api/metadata/tags?sort=name|count` → `{ jsTags: [{ name, color, description, count }], otherTags: [{ name, count }] }` — `jsTags` are from the `js_tags` table with their structured metadata; `otherTags` are all tags from `MediaItem.tags` that are not in `js_tags`.
   - All aggregation is computed by iterating the media store at query time (no separate aggregation table needed).

4. New library filter params: `GET /api/media?studio=Warner`, `GET /api/media?network=HBO`, `GET /api/media?genre=Action`. Backend `MediaStore.list()` is extended to support these filters.

**Studios tab**

5. Grid of cards (2–4 per row depending on viewport), each showing: studio name (prominent), item count badge. Sort control: "A–Z" / "Most items". Clicking a card navigates to `/library?studio={name}` (encoded).

**Networks tab**

6. Identical layout to Studios, filtered to networks. Clicking a card navigates to `/library?network={name}`.

**Genres tab**

7. Compact chip-style layout (not full cards). Each chip shows: genre name + count badge. Sort: A–Z / Most items. Clicking a chip navigates to `/library?genre={name}`.

**Tags tab**

8. Page has an explanatory paragraph between the tab bar and the content (always visible):
   > "Jellystructure tags are structured labels you define here with a color and description. They survive metadata re-syncs — when pulling fresh data from TMDB, Jellystructure tags on an item are always preserved. All other tags (below) come from TMDB or were added manually and may be overwritten on resync."

9. **Section 1 — Jellystructure Tags**
   - Header: "Jellystructure Tags" with a "+ New tag" button.
   - Each tag displayed as a card: colored dot (tag color), name, description (truncated to 1 line), usage count.
   - "+ New tag" opens a modal: name input, color picker (8 preset palette swatches), description textarea. Save → `POST /api/tags`.
   - Clicking a tag card opens an edit modal with same fields + "Delete tag" (ghost red button, `confirm()` → `DELETE /api/tags/{name}`). Save edits → `PATCH /api/tags/{name}`.

10. **Section 2 — All other tags**
    - Header: "And the rest"
    - Compact chip list. Each chip: tag name + count. Read-only. No editing.
    - This list may be large (TMDB tags are numerous and unstructured).

**Tags backend**

11. New SQLite table `js_tags`:
    ```sql
    CREATE TABLE js_tags (
      name TEXT PRIMARY KEY,
      color TEXT NOT NULL DEFAULT '#6b7280',
      description TEXT NOT NULL DEFAULT ''
    );
    ```
12. New routes:
    - `GET /api/tags` → list all Jellystructure tags (without counts; counts come from `/api/metadata/tags`)
    - `POST /api/tags` → `{ name, color, description }` — create; 409 if name already exists
    - `PATCH /api/tags/{name}` → `{ color?, description? }` — update
    - `DELETE /api/tags/{name}` → delete the structured definition (does NOT remove the tag string from any `MediaItem.tags` lists)

**Media Detail — tags fix**

13. The tags section in `MediaDetail.kt` is currently only partially functional. Fix:
    - On Media Detail page load, fetch `GET /api/tags` once (alongside the existing `ConfigApi.get()` call). Cache in a local variable.
    - When the tag input is focused, show a dropdown of matching Jellystructure tag names (substring filter). Non-matching free text is still accepted.
    - Jellystructure tags in the chip list are rendered with a small colored dot: `<span class="tag-dot" style="background:{color}"></span>` prepended inside the chip.
    - Non-Jellystructure tags render as plain chips.
    - Tag add/remove functionality already works — no logic changes, only the above UI enhancements.

**Sync invariant — tags**

14. When `POST /api/media/{id}/sync` (Phase 13) or `POST /api/media/{id}/repull` is called:
    - Load the current Jellystructure tag name set from `GET /api/tags`.
    - After fetching updated metadata, merge tags: `newTags = jellystructureTagsOnItem + tmdbSourcedTags` where `jellystructureTagsOnItem = item.tags.filter { it in jsTagNames }`.
    - Result: Jellystructure-defined tags survive; non-JS tags are replaced by whatever TMDB returns (or cleared if TMDB returns none).
    - Note: TMDB v3 movie/TV details do not currently return a tags field — `MediaItem.tags` is populated from manual edits only. This merge logic is future-proof and applies correctly when tags are absent (TMDB returns nothing → preserve only JS tags).
