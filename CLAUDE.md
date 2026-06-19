# CLAUDE.md — design project notes

This repo holds the **HTML/CSS design mockups** for **jellystructure** (`design/app/`) plus
the low-fi wireframes (`design/wireframes/`) and the **Ravilo** TV-client designs (`design/ravilo/`).

## Source of truth: spec-driven development

The product is developed **spec-first**. Read the relevant spec before touching a mockup; specs win
over designs on any conflict. All specs live in `specs/`:

- `specs/constitution.md` — non-negotiable architecture, language-resolution algorithm,
  config shape, visual system. **Source of truth.**
- `specs/requirements/README.md` — the jellystructure phase index (done/planned). Completed
  phases live in `requirements/archive/`.
- `specs/plan.md` — source layout, data models, API routes, UI pages, WS protocol.
- `specs/STATUS.md` — where work currently stands; read first each session.
- `specs/ravilo/` — Ravilo constitution, plan, STATUS, and phase index (R01–R17).

**jellystructure is at phase 30 complete** (phases 31–40 planned).
**Ravilo phases R01–R17 are all planned; nothing implemented yet.**

## Design constraints to respect

- `design/app/` mockups are **the visual target for the Kotlin/WASM (admin) frontend**.
- Real frontend styling is **Tailwind CSS** (classes scanned from Kotlin source at build time).
  Mockups use a custom CSS-variable system in `wf.css`/`app.css` — visual spec; tokens map to
  Tailwind on implementation.
- **Admin frontend is DOM-based** (kotlinx.browser) — no Canvas, no Compose for Web.
- **Frontend renders server-pushed state only** — no derived/optimistic state.
- **Ravilo** (`design/ravilo/`) uses **Compose Multiplatform** (shared Android TV + WASM canvas
  bundle). The "no Compose for Web" rule applies only to the admin app; both WASM bundles coexist.

## Current visual system (admin app)

- Dark-primary with a **three-way Light / Dark / System** picker (default System, follows OS).
  Single **Aurora** direction (cinematic, glassy, gradient); theme persisted in `localStorage`
  `js-theme` by `app-shell.js`. Purple→blue accent.
- Type: Space Grotesk (display) · Sora (UI) · JetBrains Mono (code/IDs).
- Color tokens: `--ok` resolved/success · `--warn` dirty/mixed · `--bad` error.
- All screens share `design/app/wf.css` (tokens + components) and `design/app/app.css` (shell).
  `app-shell.js` injects the sidebar, mobile drawer, ambient **scan dock**, and the floating
  **Triage dock**.

## Screen set (design/app/, post phase-30)

- **Dashboard** (`index.html`) · **Library** (`library.html`, multi-axis filters + multi-language
  search) · **Activity** (`activity.html`, log filters + workers).
- **Metadata** (`metadata.html`) — Studios · Networks · Genres · Tags.
- **Settings** (`settings.html`) — Connections · Library mapping · Scanning · Metadata · Advanced.
- **Movie detail** (`media.html`) and **Series detail** (`series.html`) — single editing surface.
  **Track editor** (`track-order.html`) stays as a linked sub-page. **Login** (`login.html`).
- **Ravilo config** (`ravilo-config.html`) — per-user home-feed / channels / skin config (Phase R16).

Removed: `triage.html`, `series-triage.html` (Phase 27), `language.html` (Phase 23).

## Key spec changes in the current backlog (phases 31–40)

- **31** — Studio/network logo artwork: fetch, cache, serve; "Fetch missing logos" batch on Metadata page.
- **32** — In-app TMDB match picker: search modal on Media Detail; replaces leaving for themoviedb.org.
- **33** — Jellyfin ⇄ NFO drift detection: amber banner + per-field re-assert / accept modal.
- **34** — Undo/revert from History: extend history entries with `before` snapshot; Revert button.
- **35** — System health panel: `GET /api/health/full`; inline status in Settings (no separate panel).
- **36** — Operator controls: per-library scan/push, scheduled rescans (`scan_schedule` cron), webhook notifications.
- **37** — Surface qBittorrent guard: guard chip + "seeded — edits blocked" badge on Tracks tab.
- **38** — Command palette ⌘K + `n`/`p`/`o` keyboard nav through attention queue.
- **39** — Subtitle management: forced/default flags on subtitle tracks; fetch missing subs from provider.
- **40** — Configure qBittorrent in Settings: Cross-seed safety section; explicit `enabled` toggle.

## Config shape

`[[libraries]]` are auto-discovered from the Jellyfin API (no static `[paths]`).
`[qbittorrent]` seeding-guard section gains a UI in Phase 40 (currently config-only, Phase 26).
`[notifications]` (webhook) planned for Phase 36.

## Sibling product — Ravilo

**Ravilo** is a Compose Multiplatform streaming client (Android TV + browser/WASM canvas) for
jellystructure-managed libraries. It adds a `/api/tv/**` namespace to the jellystructure backend
and a shared `:shared` KMP module (DTOs + `TvApiClient`). Control plane = jellystructure;
data/video = Jellyfin directly. Phases R01–R17 fully planned; see `design/specs/ravilo/`.
