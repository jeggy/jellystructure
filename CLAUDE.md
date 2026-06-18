# CLAUDE.md

## What is Jellystructure?

**jellystructure** is a self-hosted web application that fully replaces Jellyfin's built-in metadata
scraper. Once running, users never need to use "Refresh Metadata" in Jellyfin. The system owns:

- **NFO files** (Kodi/Jellyfin-compatible XML): `movie.nfo`, `tvshow.nfo`, `episodedetails.nfo`
- **Artwork** (poster, fanart, logo, per-episode stills)
- **Media track management** (audio/subtitle default flags and language tags via `mkvpropedit`/`ffmpeg`)
- **Config** (editable from the web UI, stored as TOML)

The metadata language is driven by the actual audio tracks in each file — not a global default —
using a language-resolution algorithm backed by TMDB.

**Stack at a glance:** Kotlin Native backend (`linuxX64`/`linuxArm64`, single native binary, Ktor CIO,
kotlinx-io, ktoml, SQLDelight) + Kotlin WASM frontend (`wasmJs`, `kotlinx.browser` DOM, Tailwind).
Deployed via Docker Compose. No JVM, no Compose-for-Web.

---

## Spec-driven development

This project is developed **spec-first**. The full picture — rules, requirements, architecture, and
current state — lives in [`specs/`](specs/). Read the relevant spec before changing code; update the
spec when behaviour changes.

| File | Purpose |
|------|---------|
| [`specs/constitution.md`](specs/constitution.md) | Core rules, tech mandates, architectural invariants, visual system. **Source of truth** — wins on conflict. |
| [`specs/requirements/`](specs/requirements/) | The **"what"** — one file per phase. Active phases at top level, completed ones under `archive/`. Start at the [README index](specs/requirements/README.md). |
| [`specs/plan.md`](specs/plan.md) | The **"how"** — source layout, data models, API routes, UI pages, WebSocket protocol, scanner flow. |
| [`specs/tasks.md`](specs/tasks.md) | Step-by-step TODO for the active phase. |
| [`specs/STATUS.md`](specs/STATUS.md) | Where work currently stands; read this first each session. |

**Workflow:** `STATUS.md` → relevant `requirements/` spec → `plan.md` for code locations → implement,
keeping `tasks.md` current → on completion, move the phase spec to `requirements/archive/` and update
`STATUS.md`.

---

## Build & run

- `./gradlew linkDebugExecutableLinuxX64` — build backend binary
- `./gradlew wasmJsBrowserDevelopmentWebpack` — build frontend bundle
- `./gradlew runDev` — build both + start backend (port 9505) + webpack dev server (port 8081); dev config/sessions written to `./config/`

---

## Key invariants (do not break)

1. **Track flags are never changed automatically** — only via explicit user action in the UI.
2. **Language resolution is per-file** — audio tracks in physical index order, first TMDB hit wins.
3. **NFO writes are atomic** — `.tmp` + `rename()`.
4. **Frontend renders server-pushed state only** — no derived/optimistic state (past desync bug).
5. **No Compose for Web** — DOM manipulation only via `kotlinx.browser`.
6. **Jellystructure tags survive re-syncs** — `js_tags`-defined tags are always preserved on sync/repull.

Full rationale and the complete rule set are in [`specs/constitution.md`](specs/constitution.md).
