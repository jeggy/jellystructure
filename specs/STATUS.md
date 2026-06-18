# Status

Living record of where the project stands. Update this whenever a phase completes or direction
changes, so the next session can pick up without re-deriving context.

_Last updated: 2026-06-18_

## Snapshot

- **Phases 0–14: ✓ Done.** Core system complete: Jellyfin-driven discovery, ffprobe track data,
  TMDB matching, NFO writing, artwork, per-file/per-episode language resolution, track editing,
  triage, live WebSocket scans, persistent/resumable scan state, theme picker, dirty-indicator diff
  popups, external links, language pickers, simplified series language UI, targeted single-item
  sync, and **SQLite persistence via SQLDelight** (Phase 14 — all stores on SQLite; no JSON files).
- **Phases 15–25: Planned.** See [`requirements/`](requirements/).
- **Next up: Phase 15** — Fix library path matching for movies (FR-B1): movies fail to match a
  library during scans (`[WARN] No matching library for '/media/movies/...'`) because the
  `jellyfinPath` vs `localPath` prefix check fails when the two containers mount media at different
  paths.

### Key cross-cutting findings (2026-06-18) — see [`requirements/_investigation-findings.md`](requirements/_investigation-findings.md)
- **SQLite/SQLDelight now live.** All four stores (MediaStore, SessionService, ScanTracker,
  MediaHistory) run against a real SQLite DB (`DB_FILE` env, default `./data/jellystructure.db`).
  No migration from legacy JSON — new installs start fresh.
- **`studio`/`network` are never populated from TMDB** — prerequisite work for the Metadata page (P1/P2 in Phase 19).
- **TMDB has no network search** — network logos must be captured from TV details at scan time.
- **Frontend router exact-matches routes** — query-param navigation (`/library?studio=…`,
  `/metadata?tab=…`) needs router work.
- **The "Sync ↻" button fetches TMDB, not Jellyfin** — Phase 25 introduces real Jellyfin re-pull.

## Recent work (git)

- **Phase 14 complete:** SQLite persistence via SQLDelight 2.0.2 + NativeSqliteDriver. Four
  stores reimplemented (MediaStore, SessionService, ScanTracker, MediaHistory). One-time JSON
  migration dropped — fresh installs only.
- Commented out `<lockdata>` writes in NFO files. Decision made: lockdata is dropped for good.
- Adjusted config file paths and improved Jellyfin API error logging.

## Known issues / open threads

- **Library path matching** — the Phase 15 root cause; blocks movie scans on mismatched mounts.
- **`<lockdata>` removal** — Phase 22 drops it entirely (code cleanup) and adds Jellyfin lock
  *detection*. The constitution §"NFO Files" still mandates lockdata and must be corrected.
- **Port free-check logic** — unresolved `TODO` in `Main.kt` (`checkPortFree` "doesn't work"); the
  TODO suggests catching the bind exception instead. Revisit.

## How to work here (spec-driven flow)

1. Read [`constitution.md`](constitution.md) — the rules that always hold.
2. Read the relevant phase spec in [`requirements/`](requirements/).
3. Check [`plan.md`](plan.md) for where the code lives and the current API/UI surface.
4. Break the phase into steps in [`tasks.md`](tasks.md), implement, and keep that file current.
5. On completion: move the phase spec to `requirements/archive/`, flip its status, and update this file.
