# Investigation Findings (2026-06-18)

Cross-cutting facts discovered while scoping phases 14–25. Several specs reference these. Verify
against code before implementing — they were true at the commit noted in [`../STATUS.md`](../STATUS.md).

## Persistence — SQLite/SQLDelight (Phase 14 ✓ Done)

> **Phase 14 is complete.** All four stores now run against a real SQLite DB. The activity log
> ([`phase-17`](phase-17-activity-log-backend.md)) and JS tags ([`phase-19`](phase-19-metadata-page.md))
> should use SQLite tables when implemented, not a new JSON-file pattern.

Persistence as of Phase 14:

| Data | Mechanism | Location |
|------|-----------|----------|
| Media cache | SQLite — `media` table (blob + indexed scalars) | `DB_FILE` env, default `./data/jellystructure.db` |
| Scan state | SQLite — `scan_state` + `scan_processed` tables | same DB |
| Config | TOML (user-editable, intentionally not in DB) | `CONFIG_FILE` env, default `./data/config.toml` |
| Sessions | SQLite — `session` table | same DB |
| Audit history | SQLite — `media_history` table (cap 2000, trimmed on insert) | same DB; **survives restart** |

No migration from legacy JSON files — new installs start fresh.

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
