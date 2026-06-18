# Plan — Architecture & Implementation Reference

The **"how"**: how the system is built today — source layout, data models, API surface, UI pages,
and the runtime flows. For the non-negotiable rules behind these choices, see
[`constitution.md`](constitution.md). For features being built, see [`requirements/`](requirements/).

---

## Tech Stack (summary)

- **Backend** — Kotlin Native (`linuxX64`, `linuxArm64`), single native binary, Ktor CIO server, kotlinx-io, ktoml, SQLDelight + native SQLite, Ktor WebSockets.
- **Frontend** — Kotlin WASM (`wasmJs`, WasmGC), `kotlinx.browser` DOM manipulation, Tailwind via Webpack/PostCSS, hash routing, `StateFlow`.
- **Infra** — Docker Compose; config TOML at `/config/config.toml`; media volume at `/media`.

### Build commands
- `./gradlew linkDebugExecutableLinuxX64` — build backend binary
- `./gradlew wasmJsBrowserDevelopmentWebpack` — build frontend bundle
- `./gradlew runDev` — build both + start backend (port 9505) + webpack dev server (port 8081); dev config/sessions written to `./config/`

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
| GET | `/config/path-check` | Per-library path diagnostics (Phase 15) |

### Jellyfin
| Method | Path | Description |
|--------|------|-------------|
| GET | `/jellyfin/libraries` | Discover libraries from Jellyfin API |
| POST | `/jellyfin/refresh` | Trigger Jellyfin library refresh |

### Media
| Method | Path | Description |
|--------|------|-------------|
| GET | `/media` | Paginated list (`kind`, `filter`, `search`, `sort`, `page`, `pageSize`, `studio`, `network`, `genre`); episodes stripped |
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
| POST | `/media/{id}/sync` | Targeted full rescan (Phase 13) |
| POST | `/media/{id}/seasons/{seasonNumber}/sync` | Targeted season rescan (Phase 13) |
| GET | `/media/{id}/episodes/stills` | Check still existence per episode |
| POST | `/media/{id}/episodes/stills` | Fetch all missing episode stills |
| POST | `/media/{id}/episodes/nfo` | Write episodedetails.nfo for all episodes |
| POST | `/media/{id}/episodes/{epFilename}/still/upload` | Upload episode still |
| GET | `/media/{id}/episodes/{epFilename}/tracks/plan` | Preview mkvpropedit/ffmpeg command |
| POST | `/media/{id}/episodes/{epFilename}/tracks/default` | Set default track |
| POST | `/media/{id}/episodes/{epFilename}/tracks/language` | Set track language |
| PATCH | `/media/{id}/episodes/{epFilename}/metadata` | Edit episode title/overview |
| POST | `/scan` | Start background library scan |
| POST | `/scan/resume` | Resume from checkpoint (Phase 7) |
| POST | `/scan/cancel` | Cancel running scan |
| GET | `/scan/status` | Full scan state |
| GET | `/stats` | Movie/TV/episode counts, issue count, NFO coverage % |
| GET | `/activity/recent` | Recent audit log entries |
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

> Planned routes for active phases (`/api/activity/log`, `/api/metadata/*`, `/api/tags/*`,
> `DELETE /api/media/all`) are specified in [`requirements/`](requirements/).

---

## UI Pages & Routes

| Route | File | Description |
|-------|------|-------------|
| `/login` | `Login.kt` | Jellyfin admin credentials sign-in |
| `/setup` | `Setup.kt` | First-run Jellyfin URL + machine token; disappears once configured |
| `/dashboard` | `Dashboard.kt` | Stats cards, recent activity, scan trigger, batch action chips |
| `/library` | `Library.kt` | Paginated media grid with filter/sort/search |
| `/media/:id` | `MediaDetail.kt` | Full item: metadata, tracks, artwork, NFO preview, resolver trace |
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
{"type":"started",     "jobId":"scan-1234", "total":-1}
{"type":"item_scanned","jobId":"scan-1234", "item":{...MediaItem...}}
{"type":"progress",    "jobId":"scan-1234", "file":"/path/ep.mkv", "current":3, "total":10}
{"type":"file_done",   "jobId":"scan-1234", "file":"/path/ep.mkv", "ok":true}
{"type":"finished",    "jobId":"scan-1234", "succeeded":42, "failed":0}
```

The Shell renders an **ambient dock** that appears when a scan is running and hides on the Activity
page (which has its own full console view). A `log_line` event type is planned in Phase 17.

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

Scan state persistence and the resume checkpoint (`scan-state.json`) are specified in Phase 7
(see [`requirements/archive/phase-07-persistent-scan-state.md`](requirements/archive/phase-07-persistent-scan-state.md)).
Note: the persistence mechanism is moving to SQLite in
[`requirements/phase-14-persistence-sqlite.md`](requirements/phase-14-persistence-sqlite.md).
