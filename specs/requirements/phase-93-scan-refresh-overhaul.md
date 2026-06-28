# Phase 93 — Scan / refresh subsystem overhaul (admin clarity) (FR-SC1)

> Builds on **[Phase 91](phase-91-scheduled-scan-pipeline.md)** (the composable scan pipeline) and the
> Phase-27 Activity/Triage surfaces. This phase does not add new scanning *capability* — it makes the
> existing machinery **correct, observable, and understandable to an admin**: one scan path, a schedule
> that fires when it says, an on-demand pipeline run, a real next-run indicator, run-tagged logging you
> can filter, and one consistent vocabulary.

---

## Problem (what the review found)

jellystructure had **four** overlapping "make the library current" mechanisms with colliding names,
and several didn't do what the admin thought:

1. **Folder watcher (30s poll) was dead code** — `FolderWatcher.start()` was never called; the
   `watch_enabled` toggle did nothing.
2. **The schedule ignored the time.** The Settings UI emits **cron** (`0 11 * * *`) but the backend
   `scheduleDelayMs()` couldn't parse cron → it fell back to **every 24h from process start**. "Daily at
   11:00" actually meant "24h after each restart."
3. **"Run now" didn't run the pipeline.** Both "Scan library" and the pipeline editor's "Run now" called
   `runScan` (file discovery). `executePipeline()` was only ever invoked by the scheduled loop — the
   composed steps (pull/artwork/nfo/sync) could *never* be triggered on demand.
4. **No next-run indicator** — the "next · 03:00" badge was hardcoded client text.
5. **Two scan code paths** — `runScan()` (multi-worker) and `Scanner.scan()` (single-threaded, used only
   by the dead watcher).
6. **Terminology collided** — scan / rescan / pull / re-pull / sync / refresh used inconsistently.
7. **Runs weren't observable** — activity entries carried no run identity, so you couldn't ask "what did
   the 11:00 run do?"; steps logged thinly.

## Target model (what an admin should understand)

Three concepts, named consistently everywhere; the folder watcher is gone:

- **Scan** — find new/changed media files from Jellyfin and add/update the library. (Fast. The pipeline's
  `scan_files` step is the same operation.)
- **The Automation** (the pipeline) — a composed sequence that starts with Scan and chains optional
  follow-ups (Pull TMDB · Download artwork · Write NFO · Sync Jellyfin · Rescan *arr · Notify). Runs **on a
  wall-clock schedule** *and* **on demand**.
- **Per-item actions** — on a title's page: Re-pull (TMDB / Jellyfin), Save & sync. Same engines, one title.

And it is **observable**: every run (scheduled or manual) is tagged with an id, logs start → per-step →
finish-summary to the Activity page and is filterable there; the next scheduled run shows as a real time.

---

## Requirements

### 93a — One scan path; folder watcher removed ✓
- Delete `FolderWatcher` and `Scanner.scan()` (the single-threaded path the watcher used). `runScan()` is the
  **only** scan entry point. Remove `behavior.watch_enabled` from config + the Settings toggle.

### 93b — Wall-clock scheduling ✓
- The schedule fires at the **local wall-clock time** the admin set. `nextRunDelayMs(cron, now)` (Main.kt)
  parses the three cron patterns the UI emits — `0 H * * *` (daily H:00), `0 H * * 0` (weekly Sun H:00),
  every-N-hours — and returns ms to the **next local occurrence** via POSIX `localtime_r`/`mktime`
  (`tm_isdst=-1`). Correct across restarts (computed from "now", never "now + interval"). The scheduler
  loop re-reads config and recomputes every ≤60s, so edits apply within a minute; the next-run epoch is
  published to `ScanTracker.nextScheduledRunSec` for the indicator (93e). Unparseable schedule → `Logger.warn`
  and the scheduler idles (no silent 24h). Legacy `scan_interval_hours` kept as a fallback when the schedule
  is blank.

### 93c — On-demand pipeline run *(planned)*
- A title can run the **composed pipeline** on demand (not just a file scan). "Scan library" = file
  discovery only; "Run pipeline now" = every enabled step. Shared `executePipeline` between the route and
  the scheduler.

### 93d — One vocabulary *(planned)*
- Canonical labels across Settings/Dashboard/Library/MediaDetail/Activity: **Scan library** (find files),
  **Pull from TMDB** / **Re-pull from TMDB** (same engine), **Sync to Jellyfin** (our NFO → Jellyfin
  re-reads), **Jellyfin: rescan its own library** (distinct), **Re-probe episode files** (was "Re-scan all
  episodes"), **Refresh unchanged titles on a schedule** (was "Re-check…").

### 93e — Next-run indicator + explainer *(planned)*
- Backend exposes the real `nextScheduledRun` (epoch); Settings + Dashboard show it ("next · today 11:00" /
  "scheduling off"). A short "How scanning works" blurb states the three concepts + that the schedule is
  wall-clock.

### 93g — Run-tagged, filterable logging *(planned)*
- Every run is tagged with a run id (+ trigger: scheduled / manual / pipeline / item). All log lines during
  the run carry it (via a `RunContext` coroutine element, mirroring `WorkerId`). The run logs start →
  per-step (with counts) → finish-summary. The Activity page gains a **run picker** to scope the log to one
  run; `GET /api/activity?run=<id>` and `GET /api/activity/runs` back it.

## Out of scope

- New scan *capabilities* (the pipeline step set is unchanged except as noted).
- Real-time push of worker counts (still polled), Jellyfin-side scheduling.

## Files

- Backend: `media/Scanner.kt`, `Main.kt`, `server/Server.kt`, `server/routes/MediaRoutes.kt`,
  `config/AppConfig.kt`, `config/config.toml`, `log/Logger.kt`, `media/ActivityLog.kt`, `media/RunIndex.kt`
  (new), the activity + scan-status routes. (Deleted: `watcher/FolderWatcher.kt`.)
- Frontend: `ui/Settings.kt`, `ui/Dashboard.kt`, `ui/Library.kt`, `ui/MediaDetail.kt`, `ui/Activity.kt`,
  `api/MediaApi.kt`, `api/ConfigApi.kt`.
