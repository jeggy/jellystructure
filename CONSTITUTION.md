# CONSTITUTION.md

This document defines the non-negotiable architectural decisions, core principles, and design constraints for **jellystructure**. All development — human or AI-assisted — must adhere to these rules. If a decision conflicts with this document, the document wins unless it is formally updated here first.

---

## Vision

jellystructure is a self-hosted web system that **fully replaces Jellyfin's built-in metadata scraper**. Once this system is running, users should never need to use "Refresh Metadata" inside Jellyfin. The system owns:

- NFO files (Kodi/Jellyfin-compatible XML)
- All image assets (posters, backdrops, logos, etc.)
- Media file track ordering (audio and subtitle default flags)
- Config management (editable from the web UI)

The metadata language is driven by the actual audio tracks present in each file — not a global default — using a cascading priority system backed by TMDB.

---

## Technology Mandates

These choices are fixed. Do not introduce alternatives without updating this document.

### Backend — Kotlin Native
- Target: `linuxX64` / `linuxArm64`, producing a native binary (no JVM)
- HTTP server: **Ktor with CIO engine** — the only async engine available outside the JVM; no Netty, no Tomcat
- File I/O: **kotlinx-io** (`org.jetbrains.kotlinx:kotlinx-io-core`) — `java.io` and `java.nio` do not exist in Kotlin Native; raw POSIX I/O is error-prone; kotlinx-io is the JetBrains-maintained KMP I/O library
- Config: **ktoml + kotlinx.serialization** — TOML format, `@Serializable` data classes, no runtime reflection
- Real-time: **Ktor WebSockets** — push status updates from backend to frontend; no HTTP polling

### Frontend — Kotlin WASM
- Compiled to WebAssembly (WasmGC)
- DOM interaction via **kotlinx.browser** — direct manipulation of the browser's native HTML tree
- **No Compose Multiplatform for Web** — canvas-based rendering is explicitly rejected (breaks accessibility, SEO, and CSS integration)
- Styling: **Tailwind CSS** via Webpack + PostCSS in the Gradle build pipeline; class names in Kotlin source are scanned at build time

### Infrastructure
- Deployment: **Docker Compose** only
- Config persistence: TOML file mounted as a Docker volume at `/config`
- Media access: direct read/write volume mount at `/media` (same UID/GID as the Jellyfin container)
- Network: shared Docker bridge network with Jellyfin for low-latency API calls

---

## Architectural Invariants

### NFO Files
- Format: Kodi-compatible XML (`<movie>`, `<tvshow>`, `<episodedetails>`)
- **Always include `<lockdata>true</lockdata>`** — this instructs Jellyfin to never overwrite these files
- Written via kotlinx-io streaming (no full XML tree in RAM)
- Images named per convention: `poster.jpg`, `backdrop.jpg`, `logo.png`

### Language Cascade Logic
This is the core domain logic. The cascade determines which audio/subtitle track is set as default:

1. Run `ffprobe` → parse JSON → build a track map (index, codec, language code)
2. Identify tracks with no language tag → surface them in the UI for manual correction before cascade runs
3. Call TMDB `/movie/{id}` or `/tv/{id}` to get `original_language`
4. Apply user-configured cascade (e.g. `["fo", "da", "en", "original"]`) against the track map
5. If the current default track does not match the cascade winner → apply modification

### Subprocess Hierarchy
Choose the **least destructive tool** sufficient for the operation:

| Operation | Tool | Rationale |
|---|---|---|
| Change default/forced flags on `.mkv` | `mkvpropedit` | Header-only edit, no bitstream rewrite, milliseconds |
| Remove/reorder tracks, fix incompatible containers | `ffmpeg -c copy` | Remux without re-encoding; use only when mkvpropedit is insufficient |
| Re-encode | `ffmpeg` (full) | Last resort only; never triggered automatically |

`ffprobe` output is always parsed as JSON (`-print_format json -show_streams`).

FFmpeg/mkvpropedit stdout+stderr is streamed to the frontend via WebSocket during long operations.

### Jellyfin API Integration
After completing metadata or file modifications, the system calls Jellyfin's REST API to trigger a refresh — the user never has to do this manually:
- `POST /Items/{itemId}/Refresh` with `MetadataRefreshMode=FullRefresh`
- HTTP 204 response is forwarded to the frontend over WebSocket as a completion event

### Configuration Shape
TOML sections and their purpose:

```toml
[api_keys]
tmdb_v3_key = ""
jellyfin_token = ""

[paths]
movies_dir = ""
tv_dir = ""

[language_rules]
audio_cascade = ["fo", "da", "en"]
sub_cascade = ["fo", "da", "en"]

[behavior]
overwrite_nfo = false
fetch_images = true
```

Config is readable and writable from the web UI. Changes are posted as JSON to Ktor, converted to TOML, and written to `/config/config.toml`.

---

## Real-Time UI Contract

- The frontend establishes a WebSocket connection on load
- All long-running backend operations (scans, downloads, ffmpeg jobs) emit granular JSON progress events over this connection
- The frontend updates specific DOM nodes reactively — no full page reloads
- UI state changes (e.g. a job starting) are reflected immediately, before the backend confirms

---

## Testing Strategy

### Integration Tests
- **Playwright** in Docker (`mcr.microsoft.com/playwright`) against the full stack
- Element selection via accessibility selectors (aria-labels), not CSS class names
- Visual regression via `expect(page).toHaveScreenshot()` on stable UI states
- Docker IPC configured with `--ipc=host` to prevent Chromium shared-memory crashes

### Test Data
A setup script (bash or Kotlin CLI) downloads and prepares test media before tests run:
- Source films: **Big Buck Bunny**, **Sintel**, **Tears of Steel** (Blender Foundation, open license)
- Script injects alternative audio tracks with `ffmpeg -map 0:v -map 1:a -c copy` and then uses `mkvpropedit` to set a wrong default flag — creating a "broken" state the test suite must repair
- Files are placed in `/media/movies/Sintel (2010)/Sintel (2010).mkv` etc. to satisfy scraper naming conventions
- Post-test validation runs `ffprobe` and asserts the correct track is now flagged as default

---

## Development Phases

| Phase | Focus | Key Deliverables |
|---|---|---|
| 1 | Infrastructure + Backend Foundation | Docker Compose, Ktor Native (CIO), ktoml config read/write |
| 2 | Frontend + API Layer | Kotlin WASM + Standard DOM, Tailwind build pipeline, WebSocket + REST wiring |
| 3 | Domain Engine | Okio NFO XML writer, subprocess abstraction (ffprobe/ffmpeg/mkvpropedit), TMDB client |
| 4 | Jellyfin Integration + Language Cascade | Full cascade algorithm, mkvpropedit orchestration, Jellyfin `/Refresh` calls, image downloads |
| 5 | Test Regime + Automation | Test data script, Playwright suite, visual snapshots, CI pipeline |

Phases are sequential. Do not begin a phase until the prior phase's core deliverables are working end-to-end.
