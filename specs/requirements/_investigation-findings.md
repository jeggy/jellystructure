# Investigation Findings (2026-06-18)

Cross-cutting facts discovered while scoping phases 14–25. Several specs reference these. Verify
against code before implementing — they were true at the commit noted in [`../STATUS.md`](../STATUS.md).

## Persistence is JSON files + in-memory — NOT SQLite

> **Now owned by [`phase-14`](phase-14-persistence-sqlite.md)** (foundational, first to be picked up):
> introduce SQLDelight + native SQLite and migrate the stores. After Phase 14 lands, the activity log
> ([`phase-17`](phase-17-activity-log-backend.md)) and JS tags ([`phase-19`](phase-19-metadata-page.md))
> should use SQLite tables, not the JSON pattern those specs currently describe.

The constitution and earlier specs say "SQLDelight with native SQLite driver." **No `.sq` files exist
and no SQLite is wired.** Actual persistence:

| Data | Mechanism | File / location |
|------|-----------|-----------------|
| Media cache | JSON, atomic `.tmp`+`rename` | `MediaStore` → `./data/media.json` (env `MEDIA_FILE`) |
| Scan state | JSON, atomic | `ScanTracker` → `<config dir>/scan-state.json` |
| Config | TOML | `ConfigStore` → `./data/config.toml` (env `CONFIG_FILE`) |
| Sessions | JSON | `SessionService` → `./data/sessions.json` |
| Audit history | **in-memory only** (`ArrayDeque`, cap 2000) | `MediaHistory` — not persisted, lost on restart |

**Implication:** new persistent stores (activity log, JS tags) should follow the same JSON-file +
atomic-write pattern (or a small SQLite introduction if deliberately chosen — but that is a new
dependency, not "as the constitution already says"). The constitution should be corrected.

## `studio` / `network` are never populated from TMDB

- `Scanner.scanMovie`/`scanSeries` map only `genres` from TMDB details. `studio` and `network` are
  always `null` unless set via `PATCH /api/media/{id}/metadata` (manual edit).
- `TmdbMovieDetails` / `TmdbTvDetails` in `TmdbClient.kt` don't even model `production_companies` /
  `networks`.
- **Therefore the Studios/Networks metadata page has no data until the scanner is extended.** This is
  a prerequisite, specced in [`phase-19`](phase-19-metadata-page.md).

## TMDB network/studio artwork

- TMDB v3 has **no `/search/network`** endpoint. Networks only appear inside TV details
  (`networks: [{ id, name, logo_path }]`).
- Production companies appear in movie/TV details (`production_companies: [{ id, name, logo_path }]`)
  and are also searchable via `/search/company`.
- **Cleanest approach:** capture `{ id, name, logoPath }` for studio/network at scan time from the
  details payload, rather than resolving by name later. Logos are PNGs on transparent background;
  TMDB image base `https://image.tmdb.org/t/p/original`.
- Studio/network logos have **no media directory** to live in (unlike `poster.jpg`). They need a
  dedicated artwork cache dir served by the backend (e.g. `<data>/artwork/studios/<id>.png`).

## NFO `<lockdata>`

- Already commented out in all three builders (`buildMovieXml`, `buildTvShowXml`, `buildEpisodeXml`)
  and in the Media Detail "lockdata=true" UI block. Removal is dead-code cleanup; the new work is
  *detecting* Jellyfin-side locks — see [`phase-22`](phase-22-remove-lockdata-detect-locks.md).
- Jellyfin exposes per-item `LockData` (bool) and `LockedFields` (list) — fetchable by adding to the
  `Fields=` query on `/Items` or via a single-item fetch.

## Routing constraints (frontend)

- `Router` is hash-based and `App.handleRoute` uses **exact** matches (`route == "/library"`). Query
  forms like `/library?studio=Warner` and `/metadata?tab=networks` will currently fall through to the
  dashboard. Navigation that carries query params needs `startsWith` handling + a query parser.
- Settings sub-nav uses real `<a href="#sect-...">` anchors → fires `hashchange` → router renders
  dashboard. This is the "redirects to dashboard" bug ([`phase-18`](phase-18-settings-page-cleanup.md)).

## Track model & the "Synstolkning" case

- `Track` has both `language` (e.g. `dan`) and `title` (e.g. `Dansk Synstolkning`). ffprobe maps
  `tags.title`. Audio-description tracks are identified by their **title** text, not language.
- The display string the user sees ("Dansk Synstolkning - Danish - AAC - Stereo - Default") is a
  Jellyfin-composed label; locally we have the component parts (`title`, `language`, `codec`,
  `default`). Filtering must operate on these parts — see [`phase-20`](phase-20-library-audio-track-filter.md).

## Scan loop is single sequential coroutine

- `runScan` calls `scanner.scan { … }` which iterates items sequentially with `delay(100)` between
  items. No channel/worker pool yet. Multi-worker is [`phase-16`](phase-16-multi-worker-scanner.md).
- `WsBroadcaster.broadcast` is already `Mutex`-guarded and concurrency-safe.
- `ScanTracker.recordProcessed` is **not** synchronized (plain `MutableSet`) — needs a `Mutex` for
  concurrent workers.

## Sync vs Re-pull (current behaviour)

- `POST /api/media/{id}/sync` → `Scanner.syncMovie` (ffprobe + **TMDB**) for movies; for TV,
  `rescanMetadata` (TMDB only) or `syncSeriesEpisodes` (ffprobe + TMDB).
- `POST /api/media/{id}/repull` → `Scanner.rescanMetadata` (TMDB only).
- **Neither re-fetches the item from Jellyfin.** The "Re-pull from Jellyfin" button is new behaviour —
  see [`phase-25`](phase-25-repull-from-jellyfin.md).
