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
| 7 | Planned | Persistent scan state + resume — survive page refresh and backend restart (FR-S1) |
| 8 | Planned | Three-way theme picker — Light / Dark / System with OS sync (FR-T1) |
| 9 | Planned | Per-field dirty indicators + diff popup on Media Detail metadata editing (FR-D2) |

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
