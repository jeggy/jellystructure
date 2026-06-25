# CLAUDE.md — jellystructure repo notes

This is the **full jellystructure code repo** — Ktor/Kotlin Native backend + Kotlin/WASM admin
frontend + the Ravilo Compose-Multiplatform apps (`ravilo-ui`/`ravilo-android`/`ravilo-phone`/
`ravilo-web`) — **plus** the design mockups under `design/` and the specs under `specs/`. (It is not
just a design project, despite this file's history.)

## Source of truth — read this first

**Phase status: [`STATUS.md`](STATUS.md) at the repo root is the ONE overview** for both products
(admin numeric phases + Ravilo `R…` phases), with a single status column each. Read it first each
session. **Nothing else tracks status** — not spec headers, not directory location, not the
`requirements/README.md` files.

Architecture & content (the "what" / "how"):
- `specs/constitution.md` — non-negotiable architecture, the **language-resolution algorithm**,
  **config shape**, visual system. `specs/plan.md` — source layout, data models, API routes, WS.
- `specs/requirements/*.md` (admin) and `specs/ravilo/requirements/*.md` (Ravilo) — one flat file
  per phase, **content only** (no status, no `archive/` split).
- Ravilo has its own `specs/ravilo/constitution.md` + `specs/ravilo/plan.md`.

**Sync & the never-forget rule:** the user's "updated designs" commits overwrite everything under
`design/` and `specs/`, but **never** `STATUS.md`, `CLAUDE.md`, or `scripts/`. So status lives at the
root where the sync can't revert it. **After every sync, run `scripts/check-phases.sh`** — it flags
any spec file with no row in `STATUS.md` (newly added → add a row) and any row whose spec file went
missing (deleted by the sync → restore from git). That is how phases/specs stop getting forgotten.

The old top-level `CONSTITUTION.md` and `wireframes/Requirements & Phases.html` are **superseded** by
`specs/`.

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
