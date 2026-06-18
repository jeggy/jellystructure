# Status

Living record of where the project stands. Update this whenever a phase completes or direction
changes, so the next session can pick up without re-deriving context.

_Last updated: 2026-06-18_

## Snapshot

- **Phases 0–16: ✓ Done.** Core system complete: Jellyfin-driven discovery, ffprobe track data,
  TMDB matching, NFO writing, artwork, per-file/per-episode language resolution, track editing,
  triage, live WebSocket scans, persistent/resumable scan state, theme picker, dirty-indicator diff
  popups, external links, language pickers, simplified series language UI, targeted single-item
  sync, SQLite persistence via SQLDelight (Phase 14), and **library path-match diagnostics** (Phase 15), and **multi-worker scanner with dynamic scaling**
  (Phase 16 — producer/consumer Channel, `limitedParallelism` dispatcher, `AtomicInt` worker
  counters, live scale-up/down, `scan_workers`/`scan_threads` config, Settings Scanning section).
- **Phases 17–25: Planned.** See [`requirements/`](requirements/).
- **Next up: Phase 17** — Activity log backend + live runners.

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

- **Phase 16 complete:** Multi-worker scanner with dynamic scaling. `scan_workers` (hot-configurable)
  and `scan_threads` (restart required) added to `[behavior]`. `runScan` uses Channel + N workers on
  `Dispatchers.Default.limitedParallelism(scanThreads)`. `ScanTracker` now exposes `activeWorkers` /
  `configuredWorkers` in status. Settings Scanning section shows restart banner when `scanThreads`
  differs from the running effective value.
- **Phase 15 complete:** Library path-match diagnostics. Augmented `[WARN]` log now includes all
  configured prefixes. New `GET /api/config/path-check` endpoint returns per-library
  `{ name, jellyfinPath, localPath, matchPrefix, localExists }`. Settings page shows path-check
  panel after "Test connections" (Jellyfin success) and after "Save"; library cards show live
  "Match prefix:" row and a `<details>` help block.
- **Phase 14 complete:** SQLite persistence via SQLDelight 2.0.2 + NativeSqliteDriver. Four
  stores reimplemented (MediaStore, SessionService, ScanTracker, MediaHistory). One-time JSON
  migration dropped — fresh installs only.

## Known issues / open threads

- **Library path matching** — Phase 15 adds diagnostics and UI; the actual path translation in
  Scanner.kt already worked once `jellyfinPath` is set correctly. Operators use the path-check
  panel to verify their mount configuration.
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
