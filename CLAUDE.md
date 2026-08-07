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
- **Export (here → repo):** export the whole project; `specs/` and `CLAUDE.md` go to the
  **repo root**, and *everything else* (`app/`, `ravilo/`, `flags/`,
  `wireframes/`, `flags.css`, `scraps/`, `uploads/`, the standalone logo HTML…) goes
  under the repo's **`design/`** folder. Then commit + push.
  **⚠ The export must LEAVE ALONE:** the repo's **`STATUS.md`** (code-owned — a 2026-07-04
  export overwrote it with our stale mirror and reverted ~14 phases; the dev team restored it
  and asked us to never ship it again), **`specs/research-reports/`** (code-owned; re-add
  nothing, delete nothing), and **`scripts/`**. Never delete repo-side spec files our mirror
  lacks — re-pull first instead. After a push the dev team runs `scripts/check-phases.sh` and
  `scripts/check-mobile-css.sh`; keep both green.
- **`STATUS.md` here is a read-only mirror** (single source of truth for phase status lives at
  the repo root, maintained by the dev team). **Re-pull it (ref `main`) whenever you need
  current status** — never edit locally, never export it.

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
- **All admin phases through 151 and Ravilo through R183 are ✓ Done** in code (per repo
  `STATUS.md`, ref `main`, synced 2026-07-31). Everything we designed has now shipped:
  **149/R179** (multi-episode files/combined card), **R180/R181** (flag-forward Audio &
  Subtitles picker + default/remembered tracks), and **150/R182** (intro & credits segment
  detection + Skip Intro / Skip Credits, implemented 2026-07-13 incl. cross-episode Chromaprint
  fingerprinting once `fpcalc` landed on the backend).
- **New since our last sync — 8 dev-authored specs pulled (not design work):**
  - **151** — manually-selected images never auto-overwritten (closes the Phase-133 hole on the
    Sync / Re-pull `updateOne` path; extends the lock to clearlogo, season posters, episode stills). ✓ Done.
  - **152** — scanner falls back to filename `(season, episode)` when Jellyfin has no `IndexNumber`. Implemented.
  - **153** — scheduled scan actively repairs an unmatched episode's numbering (writes corrective
    episode NFO + triggers Jellyfin refresh). Implemented.
  - **154** — pre-run dialog to untick slow pipeline steps (e.g. `detect_segments`) **for one run only**,
    nothing written to config. Implemented.
  - **R183** — Dolby Vision playback + decodable transcode fallback (extends R56/R173; DV was R173's
    non-goal and every DV title was force-transcoded to an undecodable Baseline/L4.1 4K stream). ✓ Done.
  - **R184** — auto-advance no longer starts the next episode minutes in (stale position leak). Implemented.
  - **R185** — Continue Watching hides fully-watched titles (Jellyfin `Played`/`PlaybackPositionTicks`
    desync, 62/115 rows corrupted live). Implemented.
  - **R186** — Continue Watching fetch window widened past the global top-20 so channel rows (e.g. DanskTV)
    aren't starved. Implemented.
- Recent landings: Live TV (147 + R177), Seerr pivot (136/137 + R170/R171), request-language
  steering (139 + R172), Workbench query blocks (140), HDR tone-map fix (R173), grid-columns
  config (R174), cover-as-video (144), event-driven pipeline (145).
- **⚠ Repo-side STATUS gap (dev team's to fix, not us):** `STATUS.md` on `main` has no rows for
  admin **152/153/154** or Ravilo **R184/R185/R186** though their spec files exist and read
  *Implemented* — `scripts/check-phases.sh` will flag them. `STATUS.md` is a read-only mirror here.
- **Next unassigned numbers: 158 / R191.** 155 (age-rating normalization) and R187 (browse page)
  **shipped in code** (Implemented, pulled from repo `main` 2026-08-02). New dev specs pulled this sync:
  156 (Seerr per-user request attribution, Implemented), R188 (Upcoming-calendar per-device
  visibility, Implemented), R189 (Samsung Tizen TV client, M1+M2 build-verified). R190 =
  filter-by-person + Seerr overflow row + admin workbench Cast-or-crew facet (design-authored, was
  drafted as R188 but the dev team took R188/R189 — renumbered to R190). **Now `Implemented`
  (2026-08-02): the dev team adopted our design, dev-reviewed it, and built §A–§D + i18n end to end
  across backend, admin WASM workbench, ravilo-ui/Compose and ravilo-tizen — compile-clean, not yet
  live-tested; one deliberate Tizen omission (§C Seerr overflow row, since Discover is out of the
  Tizen build). Pulled the canonical Implemented spec over our stale `Planned` draft 2026-08-07.**
- **Bazarr subtitle integration — design-authored `phase-157-bazarr-subtitles.md` (`Planned`, 2026-08-07).**
  Optional Bazarr connection so subtitles never need Bazarr's own UI. Principle: **JS stores nothing**
  — reads Bazarr live and issues commands; Bazarr keeps owning providers, scoring and language
  profiles (mirrored read-only). Surfaces: Settings → Download tools **Bazarr** card (`[bazarr]` TOML,
  path-matched like *arr); a new sidebar **Subtitles** page (`app/subtitles.html` — live queue, wanted
  list, history, providers, read-only profiles) + a dashboard summary card; per-title Bazarr sections
  on the movie **Tracks & subtitles** tab (renamed from "Tracks & order") and the series **Seasons &
  episodes** tab. Actions driven through Bazarr: manual search/download, auto-search, sync-to-audio,
  upgrade, delete, full scan. Not yet dev-reviewed — open question is the exact Bazarr command API +
  how a JS item resolves to a Bazarr radarrId/sonarrId (confirm live before build).
- **2026-07-31 sync:** re-pulled the entire `specs/` tree (68 files) + `STATUS.md` from repo `main`;
  repo was well ahead. Wrote `github.md` as the sync receipt. Design now matches shipped code across
  admin 0–154 / Ravilo R01–R186. See `github.md` for the screen map and details.

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
- Shared `app/wf.css` (tokens + components) + `app/app.css` (shell) + per-page
  `app/detail.css` (media/series) and `app/metadata.css`. `app/app-shell.js`
  injects the sidebar, mobile drawer, ambient scan dock, floating **Triage dock**,
  and ⌘K command palette.

## Admin screen set (current)
- **Subtitles** (`subtitles.html`, Phase 157 design) — global **Bazarr** overview (**no left-nav item**;
  reached from the dashboard summary card): live queue/tasks, wanted list (filter by kind + language),
  download/sync/
  upgrade/remove history, provider health, read-only language profiles. Companion: a dashboard summary
  card + per-title Bazarr sections on `media.html` (Tracks & subtitles tab) and `series.html` (Seasons
  & episodes). Bazarr connection lives in Settings → Download tools. JS persists no subtitle state.
- **Dashboard** (`index.html`) · **Library** (`library.html` — audio-track filter,
  multi-axis filters, multi-language search, infinite scroll, shared filter
  workbench) · **Activity** (`activity.html`).
- **Metadata** (`metadata.html`) — Studios · Networks · Genres · Tags (JS-tag color
  **swatches** + dotted chips, Phase 82) · **Age ratings** (design-complete 2026-07-31, no spec
  yet): maps every raw certification (G, TV-MA, “Från 15 år”, Btl…) to a normalized age 0–18 —
  ladder summary, per-cert stepper (write-through pulse), unmapped-cert triage (NR/Btl → treated
  as 18 in filters/kids gating — never shown as an 18+ badge — and listed when no range is set).
  Feeds Ravilo’s Maturity filter, which shows only numbers
  (`0+ · 7+ · 13+…`, `normAge()` in `ravilo-browse.js`). **Spec: `phase-155-age-rating-normalization.md`
  (shipped/Implemented in code, synced 2026-08-02).**
- **Settings** (`settings.html`) — URL-addressable **tabs** (Phase 55): Connections ·
  Libraries · Metadata · **Download tools** (Radarr/Sonarr + Seerr + **Bazarr** subtitles + cross-seed) ·
  Notifications · Advanced · **Users & devices** (Phase 143 design: per-user Ravilo
  devices + admin web sessions, revoke / sign-out-everywhere).
- **Live TV** (`livetv.html`, Phase 147) — surfaces Jellyfin's Live TV into Ravilo: master
  enable toggle, connection/status badge + Test connection + Refresh from Jellyfin, a channel
  lineup (show/hide · number · logo · order · category, keyed by Jellyfin **channel id**),
  lineup-change diffing (new→hidden · removed→unavailable), and EPG source + refresh cadence.
  **Read-only** against Jellyfin (never touches the tuner). Lives in the sidebar's **Ravilo**
  group (Phase 148 nav restructure, which renamed "Apps" → **Ravilo** and split the config editor).
- **Movie detail** (`media.html`) / **Series detail** (`series.html`) — the single
  editing surface. **Write-through** editing (Phases 71/74): edits commit to disk
  immediately; the split button is **Save → NFO / Sync Jellyfin / Save & Sync** (the
  old staged "Save changes" + amber dirty borders are gone). Tabs: Tracks & order /
  Seasons & episodes · **Artwork** (manager + lightbox, Phases 47/48/71/81) ·
  **Cast & crew** (Phases 75/76/79/80; `Season Episode Cast.html`, `series-cast.js`) ·
  NFO raw viewer (Phase 44) · History. Pagebar shows **audio-language flags** (Phase
  87, via `flags/` + `flags.css`), TMDB-id edit, external links, drift banner,
  Jellyfin field-lock banner, **★ Feature in Ravilo**. **Login** (`login.html`).
  **Multi-episode files** (Phase 149, design-complete / awaiting code): Seasons & episodes
  renders **one combined row per file** (`S01E01–E03` · combined runtime · "3 in 1 file" badge ·
  triptych thumbnail, expandable to per-episode chapter offsets), and the Artwork tab picks
  stills **per episode** with a file switcher — target `series-johnnybravo.html`.

## Ravilo companion app (`ravilo/`)
- **Ravilo TV** (`ravilo/Ravilo TV.html` → `ravilo-app.js` + `ravilo.css`) — the TV UI:
  Home (hero carousel, channel rail, content rows), Movies/Series/My List grids,
  Search, Movie/Series **detail** (merged audio+subtitle flag line R75/R78/R134, cast &
  crew R81), **Discover** (profile-hub nav R170; Coming Soon + Seerr **Request** tab
  R171), Player. The in-player **Audio &amp; Subtitles** picker is **flag-forward** (Direction 2):
  a flat list where a country flag + plain native language name anchor every track, with
  jargon-free badges only — **Default · Surround 5.1 / Stereo · Signs only** (forced) ·
  **Sound described** (SDH) · **Describes action** (audio-description) · **Commentary** — no
  codec names and no delivery-method cues (`ravilo-player.js` `renderPicker`/`PL_KIND`/`plFlag`/
  `plBadges` + `ravilo-player.css`; track data in `ravilo-app.js` `tracksFor()`; exploration in
  `Audio &amp; Subtitles Picker.html`). **Browse page** (**shipped/Implemented in code**, spec
  `specs/ravilo/requirements/phase-R187-browse-page.md`; admin half `phase-155`, also shipped):
  Movies/Series nav + an end-of-row **→ See all** tile (rows with &gt;8
  items, incl. Continue Watching + channel-scoped rows) open a shared browse page
  (`ravilo-browse.js`, Direction A: top facet bar → checklist popover). A **cast/crew face** on a media detail is now a link into this page seeded to that person's
  filmography (R190, `phase-R190-people-filter.md`, Planned): the browse facets narrow within it, and
  when Seerr is enabled a single **⚡ Seerr overflow row** ("More with {name} · request on Seerr")
  sits below all library results, opening the normal Seerr request flow (Back returns to the person
  page). The same person filter is a **Cast or crew** facet (People group) in the admin
  workbench (`ravilo-builders.js`, R190 §D). Facets Genre · Type ·
  Maturity · Year · Watched · Audio · Channel · Quality — multi-select OR within a facet, AND
  across facets, stacking on the row seed (breadcrumb + "Úr …" subtitle, no seed chip); popover
  values sort by count desc then A–Z. **Maturity is a D-pad range picker**, not a checklist:
  From / Up-to rows over the normalized age ladder (◂ ▸ adjusts, OK confirms, ladder viz
  highlights the span) expressing ≤7, 7–12, 15+, 4–14 — any integer bounds 0–18; chip shows the range
  label ("≤ 7", "7–12", "15+"). No/unmapped rating ⇒ treated as 18 (never badged). Sort defaults to Recently added (A–Z · Z–A · Year ·
  Maturity · IMDb). Popover owns the D-pad via capture keys (langPicker pattern); exploration in
  `Ravilo Browse - Filter UI Directions.html`. **Live TV** (`Ravilo Live TV.html` → `livetv-app.jsx` · `livetv.css` ·
  `livetv-data.js`, R177) is woven into Home — an **“On now”** row → full EPG guide, channel
  zapping + number entry, Now/Next overlay, and a live player; **never a top-nav tab**
  (placement configured in `ravilo-config.html`, Phase 147 §F). Three skins
  (Aurora/Midnight/Noir). `ravilo-i18n.js`
  (en/da/fo). `Ravilo Mobile.html` + `mobile/` = the Android **phone** target (R60).
  Series detail groups **multi-episode files** into one combined **triptych** card (R179,
  Option B, design-complete) on both TV and phone — the phone detail gains an Episodes section.
- **Ravilo config editor** (`app/ravilo-config.html`) — the Jellystructure-side editor
  for a viewer's TV layout. Has: **Global vs per-user scope switcher** (R51), Home
  hero carousel (single global height 40–100% + auto-advance, R58), Channels &
  collections, Content rows (system rows = Continue + Newly Added, R54/R61),
  **Top 10 / Discover** lists (R50), Behaviour (skin, tile shape, ui-lang),
  **Pair-a-TV** modal + **sticky** action navbar (R57), live preview iframe. TV sign-in
  is now the R175 **username/password login** (pairing code removed from the TV mockup).
- **Shared filter workbench** powers filters everywhere: `app/ravilo-builders.js`
  (+ `ravilo-builders.css`) exposes `window.RaviloBuilders` and is loaded by **both**
  `ravilo-config.html` and `library.html` (R32). Facets = **Studio · Network · Genre ·
  Tag · Age rating · Audio track · Cast or crew** (People group, R190 §D) plus the contextual
  Ravilo-layout facets (Hero, Content row). Library ↔ Ravilo
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
