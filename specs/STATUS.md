# Status

Living record of where the project stands. Update this whenever a phase completes or direction
changes, so the next session can pick up without re-deriving context.

_Last updated: 2026-06-18_

## Snapshot

- **Phases 0–13: ✓ Done.** Core system complete: Jellyfin-driven discovery, ffprobe track data,
  TMDB matching, NFO writing, artwork, per-file/per-episode language resolution, track editing,
  triage, live WebSocket scans, persistent/resumable scan state, theme picker, dirty-indicator diff
  popups, external links, language pickers, simplified series language UI, and targeted single-item
  sync.
- **Phases 14–25: Planned.** Not started. See [`requirements/`](requirements/). Phase 14 (SQLite
  persistence) is **new and foundational** — it must be picked up first. Phases 16/17/19 were revised
  on 2026-06-18 with deeper detail (dynamic scaling, live runners, studio/network artwork); phases
  20–25 are new (audio-track filter, multi-default triage, lockdata removal + lock detection,
  Language-page removal, TMDB-id editing, Re-pull-from-Jellyfin).
- **Next up: Phase 14** — Persistence layer (SQLite/SQLDelight). The constitution mandates it but it
  was never wired (stores are JSON files + in-memory). Doing it first means the activity log, JS
  tags, and the metadata/track filters build on real queryable storage from the start.
- **Then Phase 15** — Fix library path matching for movies (FR-B1): movies fail to match a library
  during scans (`[WARN] No matching library for '/media/movies/...'`) because the `jellyfinPath` vs
  `localPath` prefix check fails when the two containers mount media at different paths.

### Key cross-cutting findings (2026-06-18) — see [`requirements/_investigation-findings.md`](requirements/_investigation-findings.md)
- **No SQLite/SQLDelight.** Persistence is JSON files + in-memory; the constitution claims SQLite but
  none is wired. **Phase 14 fixes this** — after it lands, new stores use SQLite tables.
- **`studio`/`network` are never populated from TMDB** — prerequisite work for the Metadata page (P1/P2 in Phase 19).
- **TMDB has no network search** — network logos must be captured from TV details at scan time.
- **Frontend router exact-matches routes** — query-param navigation (`/library?studio=…`,
  `/metadata?tab=…`) needs router work.
- **The "Sync ↻" button fetches TMDB, not Jellyfin** — Phase 25 introduces real Jellyfin re-pull.

## Recent work (git)

- Commented out `<lockdata>` writes in NFO files. **Decision made:** lockdata is dropped for good —
  NFOs stay unlocked so Jellyfin can re-read them. Formalised in [Phase 22](requirements/phase-22-remove-lockdata-detect-locks.md);
  the constitution mandate must be removed.
- Adjusted config file paths and improved Jellyfin API error logging.
- Added diagnostic tools, batch Jellyfin sync, improved permission handling.

## Known issues / open threads

- **Library path matching** — the Phase 15 root cause; blocks movie scans on mismatched mounts.
- **`<lockdata>` removal** — Phase 22 drops it entirely (code cleanup) and adds Jellyfin lock
  *detection*. The constitution §"NFO Files" still mandates lockdata and must be corrected.
- **Constitution says SQLite** — but none exists. Correct §"Backend — Kotlin Native" / DB claims.
- **Port free-check logic** — unresolved `TODO` in `Main.kt` (`checkPortFree` "doesn't work"); the
  TODO suggests catching the bind exception instead. Revisit.

## How to work here (spec-driven flow)

1. Read [`constitution.md`](constitution.md) — the rules that always hold.
2. Read the relevant phase spec in [`requirements/`](requirements/).
3. Check [`plan.md`](plan.md) for where the code lives and the current API/UI surface.
4. Break the phase into steps in [`tasks.md`](tasks.md), implement, and keep that file current.
5. On completion: move the phase spec to `requirements/archive/`, flip its status, and update this file.
