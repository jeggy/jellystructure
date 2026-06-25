# Status

Living record of where work currently stands. Update whenever a phase completes or direction changes.
The **[requirements/README.md](requirements/README.md)** is the single source of truth for which phases
exist and their done/planned status. This file tracks _current focus_, recent context, and open issues.

_Last updated: 2026-06-23_

## Current focus

**Phases 0–53 complete.** [Phase 53](requirements/archive/phase-53-scanner-data-quality.md)
(2026-06-23) fixed scanner data-quality issues found in a post-DB-reset full-sync review (296 scanned
vs 303 in Jellyfin): full-scan `year` was null on 295/296 — now `searchYear = name ?? Jellyfin
`ProductionYear`` drives the TMDB search + slug and the stored year prefers TMDB
(`releaseDate`/`firstAirDate`); `slugify` produced empty/colliding ids that **silently dropped items**
(non-Latin titles → `""` — The Bad Guys + an Idiocracy dup were lost) → new `itemId` falls back to
`jf-<jellyfinId>` and `MediaStore.disambiguateIds` de-dupes same-id collisions deterministically; the
`SxxExx` season regex was widened to 4 digits (`S2025E01`/Operation X parses, numbers only — TMDB has
no year-seasons); and the scan now logs a **skip summary** (`N stored, M skipped (reason=count…)` +
per-item warns for unexpected drops) so silent skips are visible. Builds; verify by re-syncing the DB.

[Phase 52](requirements/archive/phase-52-tag-ux-design-sync.md)
(2026-06-23) brought tag UX in line with the design and surfaced the JS/normal distinction in filters:
the media-detail tags section is now its own card matching `design/app/media.html` (manage-tags link,
dashed rule, dotted JS chips, outline dirty ring), and the filter workbench + Library tag pickers dot
and group Jellystructure tags above the rest (the tag facet is color-enriched server-side via
`jsTagStore`; `tags=` OR-filtering is unchanged). The Metadata Tags tab moved onto the shared
`.tag-card`/`.swatch` classes too. Along the way, corrected a stale architecture note: the admin app is
**not** Tailwind — it ships `design/app/wf.css` + `app.css` **verbatim** via the `syncDesignAssets`
Gradle task, so new component classes are added to `wf.css` (FR-TG2).

[Phase 51](requirements/archive/phase-51-tag-population-lifecycle.md)
(2026-06-23) fixed tag population end-to-end: item tags were never read from Jellyfin (the `Tags` field
was unrequested, `JellyfinItem` had no slot, and the scanner left `tags=[]`), and the Phase 19 §15
tag-merge was never implemented — a full re-scan even **wiped JS tags**. Now the scan reads Jellyfin
`Tags`, TMDB **keywords** (`/movie|tv/{id}/keywords`) are the `tmdbSourcedTags`, and the three-way
lifecycle holds: **full scan** = Jellyfin `Tags` + JS · **re-pull from TMDB** = keywords + JS (drops
stale Jellyfin-only) · **re-pull from Jellyfin** = union. JS-defined tags survive every path (constitution
invariant #6), preserved in both `MediaStore.addOrUpdate` and the post-scan `update()` (FR-TG1).

[Phase 50](requirements/archive/phase-50-jellyfin-refresh-auth-fix.md)
(2026-06-23) fixed a single-item Jellyfin refresh/re-pull bug surfaced from on-device logs:
`JellyfinClient.refreshItem` built a **malformed `Authorization` header** (missing `, Token=`) →
**401** on every targeted item refresh (broke `pushToJellyfin`, batch push, and the artwork/track/
triage refreshes), and `getItem` hit the unreliable non-user-scoped `/Items/{id}` endpoint with **no
response validator**, so a non-2xx `text/plain` body threw `NoTransformationFoundException` (**400**).
Now every authenticated call goes through one `jellyfinAuth(token)` helper, a `bodyOrNull<T>()` helper
deserializes only on 2xx (logs + returns null otherwise), `getItem` re-fetches via the proven
`/Items?Ids=` list shape, and `pushToJellyfin` returns the refresh outcome (FR-JR1).

Phase 47 (artwork manager) shipped: the
full artwork-editing surface on Movie & Series detail — asset rail + inline TMDB gallery,
resolved-language-first candidate filtering with a never-empty fallback (**no-language `xx` is its own
bucket, distinct from "All"**), drag-drop/upload/URL replace, and series season-poster +
per-episode-still management (`MediaDetail.kt` artwork tab + `MediaRoutes.kt` / `TmdbClient.kt`
candidate-gallery routes). Built on Phase 31 (artwork fetch/cache) + Phase 32 (TMDB match picker).

**[Phase 49](requirements/archive/phase-49-full-episode-coverage.md) complete (2026-06-22):** large
series (e.g. ~260-episode shows) were missing episodes everywhere because the scan capped probing at a
spread of 100. Made the cap configurable (`scan_episode_cap`, default **0 = unlimited**) so full scans
probe every episode, and wired an on-demand uncapped **Re-scan all episodes** button on the Series detail
(via the existing `syncSeriesEpisodes` `/sync` path).

**[Phase 48](requirements/archive/phase-48-artwork-textless-filter.md) complete (2026-06-23):** the
artwork gallery's Textless / With-text pills only re-sorted (a no-op within a language bucket) and
overlapped the language chips. Unified them into **one single-select filter** on TMDB `iso_639_1` —
`All · Textless · With text · <languages>`, each a real filter in `filteredCandidates()`; removed the
`artPrefer` sort; default ladder resolves resolved-lang → textless → All. `design/app/media.html` mockup
synced to the same model.

No planned admin phases remain. Other active development is on the **Ravilo** side — see
[`ravilo/STATUS.md`](ravilo/STATUS.md).

## Newly planned (admin) — 2026-06-23

Two admin phases were drafted from a Settings design pass and are **planned, not yet built**
(mockups in `design/app/settings.html`):

- **[Phase 54](requirements/phase-54-configure-radarr-sonarr.md) — Configure Radarr & Sonarr**
  (FR-AR1). Opt-in `[radarr]`/`[sonarr]` config sections (default **off**), mirroring the
  Phase 40 qBittorrent opt-in pattern: read each app's **root folders** to import as
  `[[libraries]]`, and fire a best-effort `RescanMovie`/`RescanSeries` after a Jellystructure
  write. **Read + rescan only** — no acquisition/mutation, never blocks a write; api-key masked
  with the `##KEEP##` sentinel like the qBittorrent password.
- **[Phase 55](requirements/phase-55-settings-tabbed-navigation.md) — Settings as
  URL-addressable tabs** (FR-ST1). The growing Settings page (now incl. Radarr/Sonarr) becomes
  6 `?tab=` panels via the Phase 28 `replaceState` Router (Connections · Libraries · Metadata ·
  Download tools · Notifications · Advanced); the scroll-spy is dropped and health-check
  failures aggregate to per-tab badges + switch-to-tab.

## Newly planned — Radarr/Sonarr acquisition + Discover (2026-06-23)

A second design pass turned "request a title we don't have, and show its download progress" into a
small spec set. The status indicator is deliberately **more than a percentage** — a request can be
`requested` (not yet handed to a download client), `queued` (in the client queue), `downloading`
(% with `stalled`/`metadata` flags), `importing`, then `available`.

- **[Phase 56](requirements/phase-56-arr-acquisition-pipeline.md) — \*arr acquisition pipeline +
  status state machine** (FR-AQ1). The engine: add+search via Radarr/Sonarr, a reconciler that
  merges the \*arr **queue** (source of truth) with optional qBittorrent enrichment into one shared
  `AcquisitionStatus` enum, persisted, with `acquisition_changed` WS events. Request + track only;
  builds on Phase 54.
- **[Phase 57](requirements/phase-57-chart-discover-ingestion.md) — Chart/Discover feed ingestion**
  (FR-CH1). A vendor-abstracted `ChartProvider` (Netflix via Tudum first) + normalized `ChartEntry`;
  country movies/TV (rank-only), global, non-English, all-time (views); weekly history → trend
  badges; TMDB-resolve + library-match at ingest.
- Ravilo-side surfacing is tracked under Ravilo: **R48** (`/api/tv/discover` + per-user config + live
  status), **R49** (TV Top 10 tab/detail/request), **R50** (config-editor list selection). See
  [`ravilo/STATUS.md`](ravilo/STATUS.md).

See [`requirements/README.md`](requirements/README.md) for the full index.

## Sibling product — Ravilo (Android TV + Web)

**Ravilo** specs now live under [`ravilo/`](ravilo/) — a Compose Multiplatform streaming front-end
(Android TV **and** browser/WASM canvas, one shared codebase) for jellystructure-managed libraries.
It adds a **`/api/tv/**`** namespace + a per-Jellyfin-user config store to *this* backend and a shared
**`:shared`** KMP module (DTOs + Ktor client) that the admin frontend reuses too. Control plane =
jellystructure only; data plane (video/images) = Jellyfin directly. Ravilo's phase state is tracked
separately — see [`ravilo/STATUS.md`](ravilo/STATUS.md) and
[`ravilo/requirements/README.md`](ravilo/requirements/README.md).
The jellystructure admin frontend stays DOM + the shipped `wf.css`/`app.css` (no Tailwind); Ravilo's web build is a
**separate** canvas bundle (the "no Compose for Web" rule is scoped to the admin app).

## Key cross-cutting findings — see [`requirements/_investigation-findings.md`](requirements/_investigation-findings.md)

- **SQLite/SQLDelight live.** All four stores (MediaStore, SessionService, ScanTracker, MediaHistory)
  run against a real SQLite DB (`DB_FILE` env, default `./data/jellystructure.db`). No migration from
  legacy JSON — new installs start fresh.
- **`studio`/`network` are populated from TMDB** at scan time (Phase 19 P1), incl. `tmdbId`/`logoPath`
  (P2) — but those logo paths are not yet downloaded/served (Phase 31).
- **TMDB has no network search** — network logos must be captured from TV details at scan time.
- **The "Sync ↻" button fetched TMDB, not Jellyfin** — Phase 25 added the real Jellyfin re-pull.

## Recent work (git)

- **Spec sync (2026-06-18):** brought `plan.md` + `constitution.md` in line with the source after a
  code-vs-spec audit. `plan.md` now documents the real `GET /api/media` filter set (multi-value
  `studios`/`networks`/`genres`/**`tags`** + audio-track filters), `meta-facets`/`track-facets`,
  `DELETE /media/all`, `nfo/writable`, `jellyfin-locks`, `tmdb-languages`, `tmdb-id`,
  `repull-jellyfin`, `batch/jellyfin-push`, `/health`, the corrected `/media/{id}/tracks/*` +
  `reorder`/`delete`/`jellyfin-refresh` routes, the real triage routes, and the expanded `MediaItem`
  model. `constitution.md` config shape gained `scan_workers`/`scan_threads` + the `[qbittorrent]`
  section. Added **Phase 30** (Library multi-axis filters — retroactive) and **Phase 31** (planned).
- **Phase 29 complete:** Multi-language library search — `titlesByLang` (merge-only), search across
  every title ever pulled + original title.
- **Phase 27 complete:** Triage merged into media detail; triage is now a floating navigation dock.
- **Phase 19 complete:** Studios/Networks/Genres/Tags metadata page — TMDB studio/network fields
  populated at scan time; JsTagStore (JSON CRUD); `/api/metadata/*` + `/api/tags` routes; Metadata.kt
  UI with tabbed glassmorphism cards, client-side filter, sort; Jellyfin ID-based media URLs.
- **Phase 17 complete:** Activity log backend — Logger, `log_line` WS broadcast,
  `GET/DELETE /api/activity/log`, frontend filter bar + workers chip.
- **Phase 16 complete:** Multi-worker scanner — `scan_workers`/`scan_threads`, Channel-based
  producer/consumer, live scale-up/down.

## Known issues / open threads

- **Reverse-drift resolved in specs:** `plan.md` previously claimed `PATCH /config`,
  `POST /config/test-connection`, and `POST /jellyfin/refresh` — none exist in source (it's `PUT
  /config`, no test-connection route, and per-item `/media/{id}/jellyfin-refresh`). The spec now
  matches source. If a Test-connections action is still wanted in Settings, it needs a real endpoint.
- **Port free-check logic** — unresolved `TODO` in `Main.kt` (`checkPortFree` "doesn't work");
  suggestion: catch the bind exception instead.

## How to work here (spec-driven flow)

1. Read [`constitution.md`](constitution.md) — the rules that always hold.
2. Read the relevant phase spec in [`requirements/`](requirements/).
3. Check [`plan.md`](plan.md) for where the code lives and the current API/UI surface.
4. Break the phase into steps in [`tasks.md`](tasks.md), implement, and keep that file current.
5. On completion: move the phase spec to `requirements/archive/`, update README.md phase table, and
   update the "Current focus" section above.
