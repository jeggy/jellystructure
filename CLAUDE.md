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

**Ravilo (the TV companion app) has its OWN spec tree** at `specs/ravilo/` — read it
for anything in `ravilo/` or the `ravilo-config.html` editor:
- `specs/ravilo/constitution.md` · `specs/ravilo/plan.md` · `specs/ravilo/STATUS.md`
- `specs/ravilo/requirements/` (phases R01–R31 done; R32 unified filter workbench planned).

The admin app is at **phase 46 complete** (Phase 47 artwork manager planned); Ravilo at **R31
complete** (R32 planned). The old
top-level `CONSTITUTION.md` and `wireframes/Requirements & Phases.html` are
**superseded** by `specs/`.

## Design constraints to respect
- `design/app/` mockups are **the visual target for the Kotlin/WASM frontend**.
- Real frontend styling **ships `design/app/wf.css` + `app.css` verbatim** — the Gradle
  `syncDesignAssets` task copies them into the dist and `index.html` links them. There is
  **no Tailwind pipeline**; the mockup CSS *is* the production CSS, so new component classes
  go into `wf.css` (the mockups' per-page `<style>` blocks do not reach the app).
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
  editing surface** for everything (Phase 27). Track/subtitle **language, order, default &
  forced** are all managed inline on the media detail **Tracks & order** tab (movie) and via a
  per-episode editor modal (series) — the old standalone `track-order.html` page is **removed**
  (merged in). **Login** (`login.html`).

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

## Ravilo companion app + shared filter builder (this project)
- `ravilo-config.html` is the **Jellystructure-side editor** for a viewer's TV layout
  (spec: Ravilo **R16**) — Hero carousel, Channels, Content rows, Behaviour, per-user
  banner, live `Ravilo TV.html` preview iframe.
- **One shared workbench builder** powers filters everywhere: `app/ravilo-builders.js`
  (+ `ravilo-builders.css`). It exposes `window.RaviloBuilders` ({ openFilter, openHero,
  evaluate, grad, TITLES, FACETS }) and is loaded by **both** `ravilo-config.html` and
  `library.html`. AND/ANY condition stack, live match counts, result preview.
- **Filter facets = the Phase-30 set only: Studio · Network · Genre · Tag** (R16
  invariant: *reuse Phase-30 facets, no parallel taxonomy*). Do NOT add Year/Rating or
  other axes the `/api/media` filter can't serve.
- **Library ↔ Ravilo round-trip:** Library's `⚙ Add filter` opens the same builder;
  `Save filter as… → Channel / Content row` (viewer-targeted) and a per-poster
  `★ Save as hero item` push into the per-user layout.
- Layout params follow R27/R28: **hero height 30–100%**, **auto-advance default 7s**,
  tile shapes **Standard poster / Wide landscape / Square**.
- **Artwork manager** lives on the Movie detail **Artwork tab** (`media.html`) — asset
  rail + inline TMDB gallery; language defaults to the title's resolved language then
  falls back, and TMDB **no-language (`xx`) is its own bucket, distinct from "All"**.
  (Aligns with Phase 31 logo cache / Phase 32 TMDB match picker.)

## Config shape
`[[libraries]]` are auto-discovered from the Jellyfin API (no static `[paths]`).
Optional `[qbittorrent]` seeding-guard section is config-only (no UI, Phase 26).
