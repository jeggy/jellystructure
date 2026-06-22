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
      JsTagStore.kt         — JSON CRUD for Jellystructure tag definitions (Phase 19)
      ActivityLog.kt        — persistent audit/activity log (Phase 17)
    log/Logger.kt           — unified stdout + ActivityLog logger
    db/Database.kt          — SQLDelight native SQLite driver wiring
    torrent/
      QBittorrentClient.kt  — qBittorrent Web API client (Phase 26)
      SeedingGuard.kt       — blocks mkvpropedit on actively-seeded files (fail-closed)
    nfo/NfoWriter.kt        — write movie.nfo / tvshow.nfo / episodedetails.nfo
    jobs/WsBroadcaster.kt   — fan-out JobEvent JSON to all WebSocket sessions
    server/
      Server.kt             — embeds Ktor, installs plugins, registers all routes
      routes/
        AuthRoutes.kt       — POST /api/auth/login, /api/auth/logout, /api/auth/me
        SetupRoutes.kt      — GET /api/setup/status, POST /api/setup/connect
        ConfigRoutes.kt     — GET/PUT /api/config, GET /api/config/path-check
        JellyfinRoutes.kt   — GET /api/jellyfin/libraries
        MediaRoutes.kt      — /api/media/** (list, meta-facets, track-facets, detail, nfo, artwork,
                              episodes, scan, stats, batch, tmdb-id, repull, repull-jellyfin, jellyfin-locks)
        TrackRoutes.kt      — /api/media/{id}/tracks/** (plan, default, language, reorder, delete, jellyfin-refresh)
        TriageRoutes.kt     — GET /api/triage, /count, /{id}/suggest, POST language-assign (movie + episode)
        MetadataRoutes.kt   — GET /api/metadata/{studios,networks,genres,tags}; /api/tags CRUD (Phase 19)
        ActivityRoutes.kt   — GET/DELETE /api/activity/log — paged, category/level filters (Phase 17)
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
      MediaApi.kt           — media list, detail, scan, nfo, artwork, tracks, triage list/count, facets
      MetadataApi.kt        — metadata aggregates + JS-tag CRUD
    ui/
      Shell.kt              — sidebar nav, three-way theme picker, ambient scan dock + floating Triage dock, WS
      Login.kt              — /login — Jellyfin admin sign-in form
      Setup.kt              — /setup — first-run Jellyfin URL + token entry
      Dashboard.kt          — /dashboard — stats cards, recent activity, batch actions
      Library.kt            — /library — media grid; multi-axis filters (studio/network/genre/tags + audio-track) + search/sort, URL-addressable
      MediaDetail.kt        — /media/:id — single editing surface (metadata, tracks, artwork, NFO, resolver trace, lock banner, ?tab=)
      Metadata.kt           — /metadata — Studios · Networks · Genres · Tags (?tab=)
      LanguagePicker.kt     — reusable searchable language-code picker component (Phase 11)
      Settings.kt           — /settings — Connections · Library mapping · Scanning · Metadata · Advanced
      Activity.kt           — /activity — real-time scan console + audit log (filters + workers)
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
    val studioTmdbId: Int?,          // Phase 19 — captured at scan for logo lookup
    val studioLogoPath: String?,
    val network: String?,
    val networkTmdbId: Int?,
    val networkLogoPath: String?,
    val tracks: List<Track>,         // movie: all tracks; TV: tracks of first episode
    val episodes: List<Episode>,     // TV only; empty on list responses
    val issueCount: Int,             // untagged audio/subtitle tracks
    val languageMix: Boolean,        // true = audio lang sets differ across episodes
    val scannedAt: Long,
    val jellyfinLockData: Boolean = false,               // Phase 22 — Jellyfin-side lock state
    val jellyfinLockedFields: List<String> = emptyList(),
    val titlesByLang: Map<String, String> = emptyMap(),  // Phase 29 — every title ever seen, per language code
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
| GET | `/config` | Full `AppConfig` as JSON (plus `effectiveScanThreads`) |
| PUT | `/config` | Replace the full `AppConfig`; writes to `config.toml` |
| GET | `/config/path-check` | Per-library path diagnostics (Phase 15) |

### Jellyfin
| Method | Path | Description |
|--------|------|-------------|
| GET | `/jellyfin/libraries` | Discover libraries from Jellyfin API |

> Per-item Jellyfin refresh is `POST /media/{id}/jellyfin-refresh`; batch refresh is
> `POST /media/batch/jellyfin-push`. There is no library-wide `/jellyfin/refresh` route.

### Media
| Method | Path | Description |
|--------|------|-------------|
| GET | `/media` | Paginated list. Filters (combinable, AND across categories): `kind`, `filter` (`attention`\|`missing_artwork`), `search` (matches `title` + `originalTitle` + all `titlesByLang`), `sort`, `page`, `pageSize`; **multi-value (comma-separated)** `studios`, `networks`, `genres`, `tags`; audio-track filters `audioLang`, `trackTitle`, `audioCodec`, `untaggedAudio`. Episodes stripped from list items. |
| GET | `/media/meta-facets` | Distinct studios / networks / genres / tags + item counts (powers the Library filter dropdowns) |
| GET | `/media/track-facets` | Distinct audio languages / codecs / track-titles + counts (Phase 20) |
| DELETE | `/media/all` | Wipe media store + scan state (Phase 18 danger zone); 409 while a scan runs |
| GET | `/media/{id}` | Full item including episodes |
| GET | `/media/{id}/history` | Audit log for item |
| GET | `/media/{id}/nfo` | Raw NFO XML |
| POST | `/media/{id}/nfo` | Write NFO (+ episodedetails for TV); trigger Jellyfin refresh |
| GET | `/media/{id}/artwork` | Check artwork existence |
| POST | `/media/{id}/artwork` | Fetch artwork from TMDB |
| POST | `/media/{id}/artwork/upload` | Multipart upload (poster/fanart/logo) |
| PATCH | `/media/{id}/metadata` | Edit title, overview, year, tags, director, studio, network |
| PATCH | `/media/{id}/tmdb-id` | Set/clear the TMDB id; does not auto-fetch (Phase 24) |
| PATCH | `/media/{id}/language` | Override resolved language |
| GET | `/media/{id}/nfo/writable` | Write-access / "Diagnose" check for the item's directory |
| GET | `/media/{id}/jellyfin-locks` | Live `{lockData, lockedFields}` from Jellyfin (Phase 22 re-check) |
| GET | `/media/{id}/tmdb-languages` | Language codes TMDB has translations for (per item) |
| POST | `/media/{id}/repull` | Re-fetch TMDB metadata without re-probing |
| POST | `/media/{id}/repull-jellyfin` | Re-discover the item from Jellyfin + full rescan (Phase 25) |
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
| POST | `/media/batch/jellyfin-push` | Write every item's NFO + trigger a Jellyfin refresh for each |

### Tracks (movie tracks under the media item; episode tracks under `/media/{id}/episodes/{epFilename}/tracks/*`)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/media/{id}/tracks/plan?specifier=a:0` | Dry-run: returns the mkvpropedit/ffmpeg command without executing |
| POST | `/media/{id}/tracks/default` | Set a track as default for its type (clears siblings) |
| POST | `/media/{id}/tracks/language` | Write a language tag to one track |
| POST | `/media/{id}/tracks/reorder` | Reorder tracks of a type (ffmpeg `-c copy` remux) |
| DELETE | `/media/{id}/tracks/{specifier}` | Remove a track (ffmpeg `-c copy` remux) |
| POST | `/media/{id}/jellyfin-refresh` | Trigger Jellyfin to reload this item |

> All five mkvpropedit/ffmpeg call sites run the qBittorrent `SeedingGuard` first (Phase 26): a
> seeded file yields **409** (`Blocked`) or **503** (`Unreachable`); unconfigured guard passes through.

### Triage (data source for the floating Triage dock — Phase 27; triage is no longer a page)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/triage` | Items needing attention (untagged tracks, cascade mismatch, multiple-default audio, or episode issues) as `TriageItem`s |
| GET | `/triage/count` | `{untagged, mismatch, multiDefault, total}` for the sidebar badge + dock |
| GET | `/triage/{id}/suggest` | TMDB language suggestions for an untagged item |
| POST | `/triage/{id}/tracks/{specifier}/language` | Assign a language to one movie track |
| POST | `/triage/{id}/episodes/{epFilename}/tracks/{specifier}/language` | Assign a language to one episode track |

### Metadata & Tags (Phase 19)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/metadata/studios?sort=name\|count` | `[{name, count, tmdbId?, logoPath?}]` |
| GET | `/metadata/networks?sort=…` | Same shape; TV only |
| GET | `/metadata/genres?sort=…` | `[{name, count}]` |
| GET | `/metadata/tags?sort=…` | `{jsTags:[{name,color,description,count}], otherTags:[{name,count}]}` |
| GET | `/tags` | All JS-tag definitions |
| POST | `/tags` | Create a JS tag (`{name,color,description}`); 409 if it exists |
| PATCH | `/tags/{name}` | Update color / description |
| DELETE | `/tags/{name}` | Delete the tag definition (does not strip it from items) |

> The global **fallback language** is edited via `PUT /config` (Settings → Metadata). The standalone
> `/api/language/settings` routes and the Language page were removed in Phase 23.

### Activity (Phase 17)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/activity/log?page&pageSize&category&level` | Paged audit log with category + level filters |
| DELETE | `/activity/log` | Clear the log |

### WebSocket
| Path | Description |
|------|-------------|
| `/ws` | Bidirectional; backend pushes `JobEvent` JSON; client sends nothing |

> A `GET /api/health` liveness route (`{status:"ok"}`) also sits under `/api`.

---

## UI Pages & Routes

| Route | File | Description |
|-------|------|-------------|
| `/login` | `Login.kt` | Jellyfin admin credentials sign-in |
| `/setup` | `Setup.kt` | First-run Jellyfin URL + machine token; disappears once configured |
| `/dashboard` | `Dashboard.kt` | Stats cards, recent activity, scan trigger, batch action chips |
| `/library` | `Library.kt` | Media grid; multi-axis filters (studio/network/genre/tags + audio-track), search, sort — all URL-addressable |
| `/media/:id` | `MediaDetail.kt` | Single editing surface: metadata (dirty + diff), **tracks & order** (Phase 41 — was `/track-order`), artwork, NFO, lock banner, history; tabs via `?tab=` |
| `/metadata` | `Metadata.kt` | Studios · Networks · Genres · Tags (`?tab=`) |
| `/settings` | `Settings.kt` | Connections · Library mapping · Scanning · Metadata · Advanced (`?sect=`) |
| `/activity` | `Activity.kt` | Real-time scan console + audit log (category/level filters, workers chip) |

> **Triage is not a page** (Phase 27). Editing happens on media detail; a floating **Triage dock**
> (navigation-only) steps through items needing attention. Removed: `/triage`, `/triage/series/:id`,
> `/language`, `/track-order` (merged into MediaDetail's "Tracks & order" tab, Phase 41).

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

The Shell renders an **ambient scan dock** (appears while a scan runs) and a **floating Triage dock**
(navigation-only — steps through items needing attention, Phase 27); both live on `document.body` and
persist across route changes. A `log_line` event type streams activity-log lines to the console (Phase 17).

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

Scan state persistence and the resume checkpoint were introduced in Phase 7
(see [`requirements/archive/phase-07-persistent-scan-state.md`](requirements/archive/phase-07-persistent-scan-state.md))
and now live in **SQLite** (`scan_state` / `scan_processed` tables) since Phase 14
(see [`requirements/archive/phase-14-persistence-sqlite.md`](requirements/archive/phase-14-persistence-sqlite.md)).
