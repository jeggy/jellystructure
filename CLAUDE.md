# CLAUDE.md — design project notes

This Cosmos project holds the **HTML/CSS design mockups** for **jellystructure**
(`app/`), the **Ravilo** TV companion (`ravilo/`), plus low-fi wireframes
(`wireframes/`). These mirror the `design/` folder of the repo
**github.com/jeggy/jellystructure**.

## How this project syncs with the repo (2-way)
GitHub is the **source of truth**; we layer designs on top of it.

- **Pull (repo → here):** the canonical specs live in the repo under `specs/`. This
  project keeps an exact mirror of `specs/` at its root, plus `CLAUDE.md`. Re-pull
  with the GitHub tools (ref `main`) whenever the specs move.
- **Export (here → repo):** export the whole project; `specs/`, `CLAUDE.md` **and**
  `STATUS.md` go to the **repo root**, and *everything else* (`app/`, `ravilo/`, `flags/`,
  `wireframes/`, `flags.css`, `scraps/`, `uploads/`, the standalone logo HTML…) goes
  under the repo's **`design/`** folder. Then commit + push.
- **`STATUS.md` is maintained two-way** (single source of truth for phase status,
  admin + Ravilo in one file). It lives at the repo root **and** is mirrored at this
  project's root; **both** the repo team and this project edit it. **Re-pull it (ref
  `main`) before editing** to absorb the other team's changes, then export it back to the
  repo root alongside `specs/` + `CLAUDE.md`.
- **Watch the loop:** the export overwrites the repo's `CLAUDE.md`, `STATUS.md` and
  `design/**` with this project's copies, so edits made *only* in the repo get reverted
  on the next export. Re-pull `specs/`, `CLAUDE.md` **and** `STATUS.md` before editing;
  keep the design-side source of truth here.

### Spec layout (mirrored at `specs/`)
- `specs/constitution.md` — non-negotiable architecture, language-resolution
  algorithm, config shape, visual system. **Wins on conflict.**
- `specs/plan.md` — source layout, data models, API routes, UI pages, WS protocol.
- `specs/requirements/phase-NN-*.md` — one flat file per admin phase (content only;
  status lives in `STATUS.md`, mirrored at this project's root).
- `specs/ravilo/` — Ravilo's own tree: `constitution.md`, `plan.md`,
  `requirements/phase-R*.md`, plus `SYNC-AUDIT-2026-06.md`.
- `specs/research-reports/` — dated deep-dives (research, not spec; may go stale).

## Where the work stands (read the repo `STATUS.md` for the live table)
- **Admin phases 0–90 ✓ Done.** **Ravilo R01–R86 ✓ Done.** No `⚠ Partial` left.
- **15 phases Planned (not built):** admin 82–85 (premise-corrected CSS/UX fixes —
  several already reflected in these mockups); Ravilo R63–R74 (TV image-pipeline
  perf, channel-list copy/preview polish, focus + player-chrome tweaks).
- Recent landings: backend-performance sprint (88/89/90 + R86), R62 brand recolor,
  R75–R81 TV detail (audio/subtitle flags, top navbar, cast & crew), R51 global vs
  per-user config, R48–R50 Discover / Top 10.

## Design constraints to respect
- `design/app/` + `design/ravilo/` mockups are the **visual target** for the
  Kotlin/WASM admin frontend and the **Compose Multiplatform** Ravilo app
  (Android TV · phone · web). Real frontend styling is **Tailwind** (admin, scanned
  from Kotlin) / Compose tokens (Ravilo); our CSS-variable system is the *visual* spec.
- Admin frontend is **DOM-based** (kotlinx.browser) — no Canvas, no Compose for Web.
- **Frontend renders server-pushed state only** — no derived/optimistic state.

## Admin visual system
- Dark-primary with a three-way **Light / Dark / System** picker (default System).
  Single **Aurora** direction (cinematic, glassy, gradient); theme persisted in
  localStorage `js-theme` by `app/app-shell.js`. Purple→blue accent. Phase 58 retuned
  the dark surfaces ("Soft Charcoal").
- Type: **Space Grotesk** (display) · **Sora** (UI) · **JetBrains Mono** (code/IDs).
- Tokens: `--ok` resolved/success · `--warn` dirty/mixed · `--bad` error.
- Shared `app/wf.css` (tokens + components) + `app/app.css` (shell). `app/app-shell.js`
  injects the sidebar, mobile drawer, ambient scan dock, floating **Triage dock**,
  and ⌘K command palette.

## Admin screen set (current)
- **Dashboard** (`index.html`) · **Library** (`library.html` — audio-track filter,
  multi-axis filters, multi-language search, infinite scroll, shared filter
  workbench) · **Activity** (`activity.html`).
- **Metadata** (`metadata.html`) — Studios · Networks · Genres · Tags (JS-tag color
  **swatches** + dotted chips, Phase 82).
- **Settings** (`settings.html`) — URL-addressable **tabs** (Phase 55): Connections ·
  Libraries · Metadata · **Download tools** (Radarr/Sonarr + cross-seed) ·
  Notifications · Advanced.
- **Movie detail** (`media.html`) / **Series detail** (`series.html`) — the single
  editing surface. **Write-through** editing (Phases 71/74): edits commit to disk
  immediately; the split button is **Save → NFO / Sync Jellyfin / Save & Sync** (the
  old staged "Save changes" + amber dirty borders are gone). Tabs: Tracks & order /
  Seasons & episodes · **Artwork** (manager + lightbox, Phases 47/48/71/81) ·
  **Cast & crew** (Phases 75/76/79/80; `Season Episode Cast.html`, `series-cast.js`) ·
  NFO raw viewer (Phase 44) · History. Pagebar shows **audio-language flags** (Phase
  87, via `flags/` + `flags.css`), TMDB-id edit, external links, drift banner,
  Jellyfin field-lock banner, **★ Feature in Ravilo**. **Login** (`login.html`).

## Ravilo companion app (`ravilo/`)
- **Ravilo TV** (`ravilo/Ravilo TV.html` → `ravilo-app.js` + `ravilo.css`) — the TV UI:
  Home (hero carousel, channel rail, content rows), Movies/Series/My List grids,
  Search, Movie/Series **detail** (audio flags R75, cast & crew R81; subtitle flags
  R78 are the open gap), **Top 10 / Discover** screen + detail with live request
  status (R48/R49), Player. Three skins (Aurora/Midnight/Noir). `ravilo-i18n.js`
  (en/da/fo). `Ravilo Mobile.html` + `mobile/` = the Android **phone** target (R60).
- **Ravilo config editor** (`app/ravilo-config.html`) — the Jellystructure-side editor
  for a viewer's TV layout. Has: **Global vs per-user scope switcher** (R51), Home
  hero carousel (single global height 40–100% + auto-advance, R58), Channels &
  collections, Content rows (system rows = Continue + Newly Added, R54/R61),
  **Top 10 / Discover** lists (R50), Behaviour (skin, tile shape, ui-lang),
  **Pair-a-TV** modal + **sticky** action navbar (R57), live preview iframe.
- **Shared filter workbench** powers filters everywhere: `app/ravilo-builders.js`
  (+ `ravilo-builders.css`) exposes `window.RaviloBuilders` and is loaded by **both**
  `ravilo-config.html` and `library.html` (R32). Facets = **Studio · Network · Genre ·
  Tag** only (reuse Phase-30 facets; no parallel taxonomy). Library ↔ Ravilo
  round-trip: `⚙ Add filter` opens the builder; `Save filter as… → Channel / Content
  row`; per-poster `★ Save as hero item`.
- **Brand (R62):** the Ravilo **brand** mark/asset pack recolors to a
  **Jellyfin-inspired palette** — gradient `#AA5CC3 → #00A4DC` on `#000B25` navy,
  "lit-mark" treatment (`ravilo/assets/brand/*.svg` masters → `assets/android/**` +
  `store/**`; `Ravilo - Android TV Assets.html`). The **in-app Aurora UI accent**
  (focus rings/buttons in `ravilo.css`, still purple `#7b6ef0`) is a **separate token
  set and is intentionally unchanged** by R62.

## Config shape
`[[libraries]]` are auto-discovered from the Jellyfin API (no static `[paths]`).
Optional `[qbittorrent]` (cross-seed guard), `[radarr]`/`[sonarr]` (read-only
root-folder import + best-effort rescan), and per-user Ravilo layout/discover blocks.
