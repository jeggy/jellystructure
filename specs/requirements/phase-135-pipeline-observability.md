# Phase 135 — Pipeline observability: per-step progress, real worker counts & run descriptors (FR-OPS3)

> A library scan/pipeline runs `Scan → TMDB → Artwork → IMDb → *arr → NFO → Jellyfin`, but **only the
> first step (`scan_files`) is observable**: it uses a worker pool and streams rich "Now processing /
> Overall N/M / Workers N/M" progress. The moment it finishes, all of that goes dark — the Activity page
> freezes on the last scan item, shows **"Workers: 0/32"**, and the operator can't tell which phase is
> running, how far along it is, or even that anything is still happening. Make **every** step first-class:
> show the whole step list, which phase is active, and per-phase **Overall / Now processing / Workers**
> (real counts) — and label the run's **trigger** (manual vs scheduled), **scope** (library-only vs full
> pipeline), and **type** (normal vs full/freshness-ignoring).

**Status:** ✓ Done — see `STATUS.md`, which is authoritative. (Header as originally written: Planned). Admin (jellystructure) phase. Builds directly on this-cycle's pipeline work (the
scanTracker-completion fix, the `?full=true` "Run pipeline now (full)" flag). Two halves: **processing**
(the later steps run through a bounded worker pool like `scan_files`, so they're faster *and* have real
worker counts) and **observability** (a step-aware progress protocol + an Activity page that renders the
whole pipeline).

## Problem
`executePipeline` (`Main.kt:466-604`) runs `scan_files` and then each subsequent step in a plain
**sequential `for (item in …)` loop** that only logs a start + summary line. Concretely, during a
7-step pipeline over 334 items the operator sees:
```
23:24:16 Pipeline scan_files complete: 334 items in working set
23:24:16 Pipeline step: pull_tmdb
23:24:16 pull_tmdb: 334 items (scope=all)
```
…and then nothing but "Workers: 0/32" for minutes while 334 TMDB pulls run one at a time. There is no
"Now processing", no "Overall N/M", no phase indicator, and the worker count is a lie (that step uses no
workers at all). The user's ask: **show all the steps, show which phase we're in, and in each phase show
Overall + Now processing + Workers (proper count)** — plus badge the trigger, scope, and full-ness.

## Current state (verified in code)

### Only `scan_files` is instrumented
- **Worker pool + counters:** `runScan` (`server/routes/MediaRoutes.kt:1885-1966`) drives a
  `Channel`-fed worker pool, sizing it from `behavior.scanWorkers` and reflecting it in
  `ScanTracker.activeWorkers`/`targetWorkers` (incremented `:1905`, decremented `:1941`). Every later
  step iterates sequentially and **never touches `activeWorkers`/`targetWorkers`**, so after `scan_files`
  returns, `activeWorkers` is `0` for the rest of the run — the "Workers: 0/32" the operator sees.
- **Progress broadcast:** `runScan` broadcasts `JobEvent.Started(total)` (`:1881`),
  `ItemScanned(item)` (`:1919`), `FileProgress(current,total)` (`:1933`), `Finished(counts)`. The later
  steps **broadcast nothing** — the Activity page's "Now processing"/"Overall"/chips are all driven by
  those `scan_files`-only events (`ui/Activity.kt`: overall `:529-544`, now-card `:546-563`, chips
  `:554/571`), so they **freeze** after `scan_files`.

### The protocol has no notion of a step/phase
- `JobEvent` (`src/commonMain/.../jobs/JobEvent.kt:7-32`): `Started`, `FileProgress`, `FileDone`,
  `ItemScanned`, `Finished`, `LogLine`, `MediaJobUpdate`. **No variant carries a step/phase name; no
  "step started/finished" event; `Started`/`Finished` are whole-run only.**
- `ScanStatusResponse` (`media/ScanTracker.kt:12-22`): `running, status, jobId, startedAt,
  processedCount, activeWorkers, configuredWorkers, nextScheduledRun`. **No trigger, scope, full, active
  step, or step list.**
- The Activity page models a **single flat scan**, not an ordered pipeline. The breadcrumb is the literal
  string `"Scanning"` (`ui/Activity.kt:254/523/551`); there is no step list, no phase highlight.

### The step tag is 90% plumbed but never surfaces
- `RunContext(runId, step)` (`log/Logger.kt:15-17`) already tags **every** log line with both `runId`
  **and `step`** (`Logger.kt:52`), and `executePipeline` wraps each step in
  `withContext(RunContext(jobId, step.step))` (`Main.kt:445/468`). `ActivityEntry.step` is persisted
  (`ActivityLog.kt:29/75`). **But**: the live `LogLine` event **drops `step`** (`ActivityLog.kt:81`;
  `JobEvent.LogLine` has no such field), `GET /api/activity/log`'s `list()` can't filter by step
  (`ActivityLog.kt:110-121`), and the client's `ActivityEntryDto.step` is declared-but-unused
  (`Activity.kt:54`). The tagging stops right before the wire and the UI.

### Trigger/scope/full are known but never structured
- `runTagged(jobId, trigger, …)` records a `RunRecord(runId, trigger, startedAt)`
  (`Main.kt:618-631`; `ActivityLog.kt:37/85-91`). Trigger strings: **`"scan"`** (`/api/scan`, resume,
  `SCAN_ON_START` — library-only file discovery), **`"manual"`** (`/pipeline/run`; `" (full)"` appended
  only to the free-text log line), **`"scheduled"`** (scheduler). This vocabulary **conflates source ×
  scope** and encodes full-ness only in prose. The `full` flag (`MediaRoutes.kt:1632` →
  `executePipeline(fullRun=…)`, `Main.kt:386`) is **never persisted or emitted** to the client. The run
  picker's `RunSummary` carries `trigger` only (`ActivityLog.kt:40-44`; client `RunSummaryDto`,
  `Activity.kt:60-64`).

## Requirements

### FR-135-1 — Every step runs through a bounded worker pool (processing + real counts)
1. Each pipeline step's per-item work (`pull_tmdb`, `download_artwork`, `sync_imdb_ratings`,
   `rescan_arr`, `write_nfo`, `sync_jellyfin`, `detect_drift`) runs through a **bounded worker pool**,
   reusing `scan_files`' pattern, so the work is concurrent (faster) and `ScanTracker.activeWorkers` /
   `targetWorkers` reflect the **active step's** live pool — the "proper count" the operator expects.
2. **Honor each step's real ceiling**, don't just crank concurrency: outbound-HTTP steps stay under the
   app-wide `OutboundHttp` gate (Phase 129/134); `download_artwork`'s ffmpeg stays under `ProcessGate`;
   `sync_imdb_ratings` keeps its imdbapi.dev throttle (small pool / preserved inter-call delay);
   `write_nfo` respects the Phase 134 FD-hygiene rule (`FileIo`, no leaked handles). The pool **target**
   is derived from `behavior.scanWorkers` but clamped to each step's safe maximum.
3. A step with no meaningful per-item fan-out (a summary-only step) may report a pool of 1 — but it still
   emits phase + progress events (FR-135-2) so the operator sees it run and complete, never a silent gap.

### FR-135-2 — A step-aware progress protocol
4. Extend the progress protocol so the client can render the **whole pipeline and the active phase**:
   - At run start, emit the **ordered step plan** (the enabled steps, in order) plus the run descriptors
     (FR-135-4) — so the client draws all step chips up front (`Scan → TMDB → Artwork → IMDb → *arr → NFO
     → Jellyfin`) before any of them run.
   - Per step, emit **step-scoped** progress: a step-start (step id, item total for that step), per-item
     progress (index/total + the item being processed, i.e. "Now processing"), and a step-finished
     (counts) — so **Overall N/M** and **Now processing** update *within each phase*, not just
     `scan_files`. Add a `step` field to the relevant `JobEvent`s (or introduce `StepStarted` /
     `StepProgress` / `StepFinished` variants) — today no event carries a step.
   - Keep a single run-level **Finished** at the very end (after the last step), as the pipeline-complete
     signal the Dashboard/Activity already key off.
5. `ScanStatusResponse` gains **`activeStep`**, the **ordered step plan**, and the FR-135-4 descriptors,
   so a late-joining/polling client (and the Workers poll) can reconstruct where the run is without having
   seen the event stream. `activeWorkers`/`configuredWorkers` continue to mean "the active step's pool".

### FR-135-3 — Activity page shows the full pipeline
6. The Activity page renders the **ordered step list** as phase chips, **highlights the active phase**,
   and marks completed/pending phases. The **Overall**, **Now processing**, and **Workers: N/M** widgets
   are driven by the active step's step-scoped events (FR-135-2), so they stay live and accurate through
   **every** phase, not just `scan_files`. When a phase completes, its chip shows its result summary
   (e.g. "NFO · 12 written, 322 unchanged"). The hard-coded `"Scanning"` breadcrumb becomes the active
   step's label.
7. Log lines render their **`step`** tag (a phase badge/column) and the log view can **filter by step**
   in addition to by run — the persisted `ActivityEntry.step` and `RunContext` tagging already exist;
   this exposes them (broadcast `step` on the live `LogLine`, add a `step` param to
   `GET /api/activity/log`, render + filter it client-side).

### FR-135-4 — Show the run's trigger, scope, and type
8. Every run surfaces three **structured, orthogonal** descriptors (not conflated free-text), shown on
   the Activity page (and stored on the run record for the run picker):
   - **Trigger** — `manual` vs `scheduled` (vs `startup` for `SCAN_ON_START`).
   - **Scope** — `library` (a plain `/api/scan` file-discovery run) vs `pipeline` (a full
     `/api/pipeline/run`).
   - **Type** (pipeline only) — `normal` vs **`full`** (the `?full=true` freshness-ignoring run, "runs
     everything regardless of freshness").
   Replace / augment the conflated `"scan"|"manual"|"scheduled"` trigger vocabulary with these three
   fields on the `RunRecord`/`RunSummary` and `ScanStatusResponse`, and badge them in the Activity header
   and the run picker (e.g. "Scheduled · Full pipeline · ignoring freshness" vs "Manual · Library scan").
   The backend already knows all three at the call sites (`Main.kt:239-243`, `MediaRoutes.kt:1618/1632-1638`);
   this only propagates them into structured fields.

## Invariants
- **No behavioural change to what the pipeline does** — same steps, same order, same results; this phase
  changes *how the work is scheduled* (bounded parallel vs sequential) and *how it's reported*, not the
  outcomes. Freshness filtering, the `full` semantics, write-through, and NFO/Jellyfin logic are unchanged.
- **Concurrency stays bounded by the existing gates** — `OutboundHttp` (Phase 129/134), `ProcessGate`,
  the imdbapi.dev throttle, and FD hygiene (Phase 134) are all respected; per-step pools never exceed a
  step's safe ceiling. This must not reintroduce the FD-pressure class of incident.
- **`activeWorkers`/`configuredWorkers` always reflect the *currently active* step's pool** — never a
  stale value from a finished step (the "0/32" bug), never a count a step doesn't actually run.
- **Server-authoritative progress** — the client renders the step plan/progress/descriptors the server
  emits; it never infers the pipeline shape or derives progress itself.
- **The run's step tagging already exists** (`RunContext(runId, step)`) — reuse it; don't invent a
  parallel tagging path.

## Out of scope
- Changing the **set** of pipeline steps, their order, or their per-step logic (only their scheduling +
  reporting change).
- A full **job-history / analytics** dashboard beyond the existing run picker gaining the new descriptors
  + per-phase summaries.
- The **Ravilo**-side or media-remux (`MediaJobUpdate`) queues — this is the admin scan/pipeline only.
- Persisting **per-item** progress across a restart (progress is live/ephemeral; the run record keeps
  descriptors + per-phase summaries, not every item).
- Reworking `scan_files` itself — it already works; this brings the *other* steps up to its standard and
  adds the cross-phase framing around all of them.

## Source references / anchors
- Backend pipeline: `src/linuxX64Main/kotlin/dev/jellystructure/Main.kt` — `executePipeline` step loop
  `:466-604` (sequential per-step `for` loops), scheduler trigger `:239-243`, `runTagged`/`RunContext`
  `:618-631`, step tagging `:445/468`, `fullRun` `:386`.
- `server/routes/MediaRoutes.kt` — `runScan` worker pool + `JobEvent` broadcast `:1885-1966`
  (`activeWorkers` `:1905/1941`, `Started` `:1881`, `ItemScanned` `:1919`, `FileProgress` `:1933`),
  `POST /scan` (library scope) `:1610-1619`, `POST /pipeline/run` (pipeline scope + `full`) `:1624-1640`,
  `GET /scan/status` `:1678-1684`.
- `media/ScanTracker.kt:12-22` (`ScanStatusResponse`), `31-32` (`targetWorkers`/`activeWorkers`),
  `136-145` (`status()`).
- Protocol: `src/commonMain/kotlin/dev/jellystructure/jobs/JobEvent.kt:7-32` (no step field),
  `jobs/WsBroadcaster.kt:11/25-33`.
- Run history + logging: `media/ActivityLog.kt` (`RunRecord` `:37`, `RunSummary` `:40-44`, `list()`
  `:110-121`, live `LogLine` drops `step` `:81`), `log/Logger.kt:9-17/46-52` (`RunContext`/`WorkerId`
  tagging).
- Frontend: `src/wasmJsMain/kotlin/dev/jellystructure/ui/Activity.kt` — WS handling `:506-600`, overall
  `:529-544`, now-card `:546-563`, chips `:554/571`, workers poll `:602-613`, breadcrumb `:254/523/551`,
  run picker `:266-283`, `ActivityEntryDto.step` unused `:54`, `RunSummaryDto` `:60-64`;
  `ui/Dashboard.kt:267-310` (scan-progress banner); `api/MediaApi.kt:182-191` (client `ScanStatus`),
  `:298-316` (`startScan`/`runPipeline(full)`).
- Related: **this cycle's** scanTracker-completion fix + "Run pipeline now (full)" flag; **Phase 91**
  (scan pipeline), **Phase 129/134** (`OutboundHttp`/`ProcessGate`/FD budget — the bounds to respect),
  **Phase 131** (`sync_imdb_ratings` throttle).
