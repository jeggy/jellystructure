# Status

Living record of where work currently stands. Update whenever a phase completes or direction changes.
The **[requirements/README.md](requirements/README.md)** is the single source of truth for which phases
exist and their done/planned status. This file tracks _current focus_, recent context, and open issues.

_Last updated: 2026-06-18_

## Current focus

**Next up: Phase 19** — Studios, Networks, Genres & Tags metadata page (FR-M1).

See [`requirements/README.md`](requirements/README.md) for the full phase index.

## Key cross-cutting findings — see [`requirements/_investigation-findings.md`](requirements/_investigation-findings.md)

- **SQLite/SQLDelight live.** All four stores (MediaStore, SessionService, ScanTracker, MediaHistory)
  run against a real SQLite DB (`DB_FILE` env, default `./data/jellystructure.db`). No migration from
  legacy JSON — new installs start fresh.
- **`studio`/`network` are never populated from TMDB** — prerequisite work for the Metadata page (Phase 19).
- **TMDB has no network search** — network logos must be captured from TV details at scan time.
- **Frontend router exact-matches routes** — query-param navigation needs router work (Phase 28).
- **The "Sync ↻" button fetches TMDB, not Jellyfin** — Phase 25 introduces real Jellyfin re-pull.

## Recent work (git)

- **Phase 18 complete:** Settings page cleanup — nav links changed from `<a href="#sect-…">` to
  smooth-scroll buttons with IntersectionObserver highlight; sections reorganised (Scanning, Metadata,
  Advanced danger zone with "Clear all scanned data"); `DELETE /api/media/all` backend endpoint.
- **Phase 17 complete:** Activity log backend + live runners. Unified Logger (stdout + ActivityLog
  delegation), `log_line` WS broadcast, `GET/DELETE /api/activity/log`, persistent JSON snapshot,
  frontend filter bar, workers chip, runBlocking main, all `*Sync` Logger variants removed.
- **Phase 16 complete:** Multi-worker scanner. `scan_workers` / `scan_threads` config, Channel-based
  producer/consumer, `limitedParallelism` dispatcher, live scale-up/down, Settings Scanning section.
- **Phase 15 complete:** Library path-match diagnostics. `GET /api/config/path-check`, Settings
  path-check panel, per-library "Match prefix" row.
- **Phase 14 complete:** SQLite persistence via SQLDelight 2.0.2 + NativeSqliteDriver.

## Known issues / open threads

- **`<lockdata>` removal** — Phase 22 drops it and adds Jellyfin lock detection. The constitution
  §"NFO Files" still mandates lockdata and must be corrected when Phase 22 lands.
- **Port free-check logic** — unresolved `TODO` in `Main.kt` (`checkPortFree` "doesn't work");
  suggestion: catch the bind exception instead.

## How to work here (spec-driven flow)

1. Read [`constitution.md`](constitution.md) — the rules that always hold.
2. Read the relevant phase spec in [`requirements/`](requirements/).
3. Check [`plan.md`](plan.md) for where the code lives and the current API/UI surface.
4. Break the phase into steps in [`tasks.md`](tasks.md), implement, and keep that file current.
5. On completion: move the phase spec to `requirements/archive/`, update README.md phase table, and
   update the "Current focus" section above.
