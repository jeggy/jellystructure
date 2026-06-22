# Status

Living record of where work currently stands. Update whenever a phase completes or direction changes.
The **[requirements/README.md](requirements/README.md)** is the single source of truth for which phases
exist and their done/planned status. This file tracks _current focus_, recent context, and open issues.

_Last updated: 2026-06-22_

## Current focus

**Phases 0–47 complete — the admin app is feature-complete.** Phase 47 (artwork manager) shipped: the
full artwork-editing surface on Movie & Series detail — asset rail + inline TMDB gallery,
resolved-language-first candidate filtering with a never-empty fallback (**no-language `xx` is its own
bucket, distinct from "All"**), drag-drop/upload/URL replace, and series season-poster +
per-episode-still management (`MediaDetail.kt` artwork tab + `MediaRoutes.kt` / `TmdbClient.kt`
candidate-gallery routes). Built on Phase 31 (artwork fetch/cache) + Phase 32 (TMDB match picker).

**Planned: [Phase 48](requirements/phase-48-artwork-textless-filter.md)** — the artwork gallery's
Textless / With-text pills don't work (they only re-sort, a no-op within a language bucket, and overlap
the language chips); unify both into one real single-select filter on TMDB `iso_639_1`. Investigated
2026-06-22; spec written, not yet implemented. Other active development is on the **Ravilo** side — see
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
The jellystructure admin frontend stays DOM/Tailwind; Ravilo's web build is a
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
