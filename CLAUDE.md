# CLAUDE.md — design project notes

This Cosmos project holds the **HTML/CSS design mockups** for **jellystructure**
(`app/`) plus the low-fi wireframes (`wireframes/`). These mirror the `design/`
folder of the repo **github.com/jeggy/jellystructure**.

## Source of truth: the repo now uses spec-driven development
The product is developed **spec-first**. Read the relevant spec before touching a
mockup; the specs win over these designs on any conflict. Pull with the GitHub
tools (ref `main`):
- `specs/constitution.md` — non-negotiable architecture, the **language-resolution
  algorithm**, **config shape**, and the visual system. **Source of truth.**
- `specs/requirements/README.md` — the phase index (single source of truth for which
  phases exist + done/planned). Completed phases live in `requirements/archive/`.
- `specs/plan.md` — source layout, data models, API routes, UI pages, WS protocol.
- `specs/STATUS.md` — where work currently stands; read first each session.
- `CLAUDE.md` (repo root) — quick orientation + key invariants.

The repo is at **phase 29 complete**. The old top-level `CONSTITUTION.md` and the
`wireframes/Requirements & Phases.html` doc are **superseded** by `specs/`.

## Design constraints to respect
- `design/app/` mockups are **the visual target for the Kotlin/WASM frontend**.
- Real frontend styling is **Tailwind CSS** (classes scanned from Kotlin source at
  build time). Our mockups use a custom CSS-variable system in `wf.css`/`app.css`
  — treat them as the *visual* spec; tokens map to Tailwind on implementation.
- Frontend is **DOM-based** (kotlinx.browser) — no Canvas, no Compose for Web.
- **Frontend renders server-pushed state only** — no derived/optimistic state.

## Current visual system
- Dark-primary with a **three-way Light / Dark / System** picker (default System,
  follows OS). Single **Aurora** direction (cinematic, glassy, gradient); theme
  persisted in localStorage `js-theme` by `app/app-shell.js`. Purple→blue accent.
- Type: Space Grotesk (display) · Sora (UI) · JetBrains Mono (code/IDs).
- Color tokens: `--ok` resolved/success · `--warn` dirty/mixed · `--bad` error.
- All screens share `app/wf.css` (tokens + components) and `app/app.css` (shell).
  `app/app-shell.js` injects the sidebar, mobile drawer, ambient **scan dock**, and
  the floating **Triage dock**.

## Screen set (current, post phase-27 sync)
- **Dashboard** (`index.html`) · **Library** (`library.html`, audio-track filter +
  multi-language search) · **Activity** (`activity.html`, log filters + workers).
- **Metadata** (`metadata.html`, route `/metadata`) — Studios · Networks · Genres ·
  Tags (Jellystructure tags survive re-syncs).
- **Settings** (`settings.html`) — Connections · Library mapping (path-check) ·
  Scanning (workers/threads) · Metadata (fallback language, NFO/artwork/refresh
  toggles) · Advanced (danger zone "Clear all scanned data").
- **Movie detail** (`media.html`) and **Series detail** (`series.html`) — the **single
  editing surface** for everything (Phase 27). **Track editor** (`track-order.html`)
  stays as a linked sub-page. **Login** (`login.html`).

## Key spec changes folded into the mockups (this sync)
- **Phase 27 — Triage is no longer a page.** `triage.html` / `series-triage.html`
  deleted. All editing (assign track language, fix default, multi-default fix, fetch
  stills, edit title/overview) happens on **media/series detail**. Triage is a
  **floating navigation dock** that steps Next/Prev through items needing attention
  and opens each one's detail page.
- **Phase 23 — Language page removed** (`language.html` deleted). The global
  **fallback language** now lives in Settings → Metadata (Phase 11 language picker).
- **Phase 22 — No lockdata.** NFOs are written **unlocked**; never write `<lockdata>`.
  Instead, detect **Jellyfin-side field locks** and show an error banner on detail
  pages with remediation steps.
- **Phase 24 — TMDB id is editable** on detail (input + Save + "Search TMDB ↗").
- **Phase 25 — "Re-pull from Jellyfin…"** (confirmation modal) re-discovers the item
  from Jellyfin; distinct from "Re-pull from TMDB". Replaces the old "Sync ↻".
- **Phase 10 — External links** "Jellyfin ↗" / "TMDB ↗" in the detail pagebar.
- **Phase 9 — Per-field dirty indicators** (amber border) + word-level **diff popup**.
- **Phase 21 — Multiple default audio** flagged on movies/episodes with a "keep one"
  fix.
- **Phase 19 — Metadata page** (Studios/Networks/Genres/Tags) + JS-tag colored dots
  and a tag dropdown on detail.
- **Phase 20 — Library audio-track filter** (track title / language / codec / untagged).
- **Phase 29 — Multi-language search** matches every title an item has ever had.
- **Phase 28 — URL-addressable tabs** (`#tab=…`) on detail + metadata pages.
- **Phase 16/17/18 — Scanner workers, activity log filters, settings reorg.**
- **Series language** (Phase 12): one compact left-rail card — Uniform/Mixed badge,
  distribution bars, NFO-language picker. Episodes resolve their own language; the
  series uses the **majority**; mixed series surface a distribution + override and are
  **never** blocked from writes.

## Config shape
`[[libraries]]` are auto-discovered from the Jellyfin API (no static `[paths]`).
Optional `[qbittorrent]` seeding-guard section is config-only (no UI, Phase 26).
