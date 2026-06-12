# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Key Documents

- **`CONSTITUTION.md`** — Non-negotiable architectural decisions and technology mandates. Read this before making any structural changes.
- **`design/wireframes/Requirements & Phases.html`** — Full functional requirements (FR-C, FR-M, FR-A, FR-T, FR-L, FR-R, FR-J, FR-U, FR-AUTH) and out-of-scope decisions.
- **`design/wireframes/Implementation Plan.html`** — Detailed phase breakdown with exit criteria for each phase.
- **`design/wireframes/`** — HTML wireframes for every screen (01-triage through 06-scan-progress).
- **`design/app/`** — High-fidelity HTML/CSS design mockups for all pages (the visual target for the WASM frontend).
- **`initial-idea.md`** — Original product vision: what the system should do and why (short, readable).
- **`initial-research.md`** — Deep technical research (in Danish) covering Kotlin Native internals, Ktor CIO, POSIX I/O, FFmpeg/mkvpropedit orchestration, TMDB cascade algorithm, and Playwright test strategy. Reference this before implementing any new domain logic.

## Project Overview

Jellystructure is a Kotlin Multiplatform project that replaces Jellyfin's built-in metadata scraper. It produces a **native Linux binary** (backend) and a **Kotlin WASM bundle** (frontend) — no JVM at runtime.

## Build & Run Commands

```bash
# Build native backend binary
./gradlew linkReleaseExecutableLinuxX64

# Build frontend WASM bundle
./gradlew wasmJsBrowserDistribution

# Full Docker build and run
docker-compose build
docker-compose up
```

The server runs on port `9505` (overridable via `SERVER_PORT` env var).

## Testing

No test suite exists yet. Future tests will use Playwright against the full Docker stack (see CONSTITUTION.md for test strategy).

## Architecture

### Dual-target Kotlin Multiplatform

| Target | Source dir | Output |
|--------|-----------|--------|
| `linuxX64` | `src/linuxX64Main/` | Native binary (`jellystructure.kexe`) |
| `wasmJs` | `src/wasmJsMain/` | Webpack WASM bundle served as SPA |

### Backend (`linuxX64Main/`)

**Entry:** `Main.kt` reads env vars and starts the Ktor CIO server.

**Server.kt** wires all routes and plugins:
- `AuthPlugin` — intercepts every request, validates `js_session` cookie via `SessionService`, attaches `SessionData` to call attributes. Open paths: `/api/auth/login`, `/api/setup`, `/api/health`.
- Static SPA fallback: all unmatched routes serve frontend files or `index.html`.

**Route groups:**

| Prefix | File | Purpose |
|--------|------|---------|
| `/api/auth/` | `AuthRoutes.kt` | login, logout, /me |
| `/api/setup` | `SetupRoutes.kt` | first-run setup, connection test |
| `/api/config` | `ConfigRoutes.kt` | read/write TOML config |

**Key services:**

- `SessionService` — generates tokens from `/dev/urandom`, persists sessions to `sessions.json` with atomic POSIX `rename()`, enforces 7-day TTL.
- `ConfigStore` — reads/writes TOML config via `ktoml`, mutex-protected, atomic file writes.
- `JellyfinClient` — authenticates against Jellyfin's `/Users/AuthenticateByName`; only allows admin accounts.

### Frontend (`wasmJsMain/`)

Single-page app using **direct DOM manipulation** via `kotlinx.browser` — no framework, no Canvas, no Compose for Web. Client-side routing via `Router.kt`. API calls go through `AuthApi` and `ConfigApi` (Ktor HTTP client with JS engine). Styling is **Tailwind CSS** (Webpack + PostCSS, scanned from Kotlin source at build time).

### File I/O

All file I/O uses `kotlinx-io` (POSIX-compatible). `java.io` and `java.nio` do not exist in Kotlin Native — never use them.

### Configuration

Config is a TOML file (`config.toml`) with four sections: `[api_keys]`, `[paths]`, `[language_rules]`, `[behavior]`. See `config/config.example.toml` for the schema.

## UI Pages

The app is a sidebar-nav SPA. See `design/app/` for pixel-accurate mockups and `design/wireframes/` for annotated wireframes.

| Page | Route | Purpose |
|------|-------|---------|
| Login | `/login` | Jellyfin credentials only; admin-only |
| Dashboard | `/` | Stat tiles, attention queue, live activity log |
| Library | `/library` | Poster grid with issue flags, filterable/pageable |
| Media Detail | `/media/:id` | Metadata form, artwork rail, track table, NFO raw view |
| Triage | `/triage` | Focus queue of untagged tracks; keyboard nav |
| Language Settings | `/language` | Fallback language config, live resolver preview (enter track languages → see TMDB fetch language; runs in WASM) |
| Track Order | `/track-order` | Before/after diff, exact command preview, apply button |
| Activity | `/activity` | Live job console fed by WebSocket |
| Settings | `/settings` | Config form + live TOML mirror |

## Development Phases

Current status is approximately end of **P1**. Phases are:

| Phase | Focus | Status |
|-------|-------|--------|
| P0 | Scaffolding, Docker, healthz, WASM page | Done |
| P1 | Config, auth, session, setup routes, login + settings UI | Done |
| P2 | Directory scan, ffprobe, TMDB client, library + media detail UI | Next |
| P3 | NFO writer, artwork downloader, LanguageResolver (shared), language resolution UI | Upcoming |
| P4 | mkvpropedit/ffmpeg track editing, triage, job runner, WebSocket progress, Jellyfin refresh | Upcoming |
| P5 | Folder watcher, Blender film fixtures, Playwright CI | Upcoming |

## Key Domain Concepts

**Language Resolution** — the core feature for TMDB metadata fetching. Per file: query TMDB for each language in the file's audio tracks, in physical track-index order (track 0 first); use the first language that returns a result. If no track language yields a TMDB result, fall back to the single global `fallback_language` (config default `en`). This resolved language governs metadata only — titles, plot, genres written to NFO. It **never** automatically changes any track flag or ordering. `LanguageResolver` is shared code (pure, no I/O) that runs identically on backend and in WASM (live preview with no round-trip).

**Track ordering** — audio and subtitle track default flags and physical order are changed **only by explicit manual operator action** from the UI (Track Order page). mkvpropedit is used for MKV (header-only, no re-encode); ffmpeg -c copy for MP4 remux as fallback.

**Triage queue** — tracks with no language tag (language resolver cannot act on these until fixed). Operators assign languages inline; the fix writes back to the container.

**Subprocess chain** — ffprobe (read, JSON) → mkvpropedit (write header for MKV, <100ms, no re-encode) → ffmpeg `-c copy` (fallback for MP4 or track removal). Always run blocking subprocess calls on `Dispatchers.IO`, not the CIO event loop.

**Machine token** — a separate `jellyfin_token` in `config.toml` used for background jobs (scan, Jellyfin refresh) so they work without a logged-in user session.

## Out of Scope

Music, TVDB/MusicBrainz, undo/change history, multi-tenant or cloud deployment.

## Hard Constraints (from CONSTITUTION.md)

- **HTTP engine must be CIO** — it is the only async engine available outside the JVM.
- **No JVM at runtime** — the output is a native binary; avoid any JVM-only APIs.
- **No Canvas or Compose for Web** — frontend must stay DOM-based for accessibility and SEO.
- **Docker Compose is the only supported deployment** — no bare-metal, no Kubernetes.
- **Subprocess order for media edits:** ffprobe (JSON) → mkvpropedit (header-only) → ffmpeg (remux, last resort).
- **NFO files must include `<lockdata>true</lockdata>`** so Jellyfin never overwrites managed metadata.

## Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `CONFIG_FILE` | `/config/config.toml` | TOML config path |
| `SESSIONS_FILE` | `/config/sessions.json` | Session store path |
| `FRONTEND_DIR` | `/app/frontend` | Served SPA assets |
| `SERVER_PORT` | `9505` | HTTP listen port |