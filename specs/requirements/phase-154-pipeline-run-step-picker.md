# Phase 154 — Pre-run dialog to skip slow pipeline steps for one run (FR-PIPE1)

> A full pipeline run takes many hours, and `detect_segments` (intro/credits detection) is by far the
> slowest step while being purely optional — it only powers Skip Intro / Skip Credits. Today the only way
> to run the pipeline without it is to edit the persisted config in Settings, run, then remember to turn it
> back on. This phase adds a confirmation dialog on the manual run actions that shows what is about to run
> and lets the operator untick steps **for that run only**, with nothing written to config.

**Status:** Planned.

## Problem
`▷ Run pipeline now` (and its `Run pipeline now (full)` menu twin) start immediately with no preview and no
per-run control. The operator's only lever is the persisted per-step enable toggle in the Settings pipeline
editor — a config edit that outlives the run and is easy to forget to revert.

## Current behaviour (traced)
- Trigger: `src/wasmJsMain/kotlin/dev/jellystructure/ui/Settings.kt:174-180` (split button `pipe-run` /
  `pipe-run-full`), wired at `:2260-2265` → `triggerPipelineRun(full)` (`:1936-1949`) → `MediaApi.runPipeline`
  (`src/wasmJsMain/kotlin/dev/jellystructure/api/MediaApi.kt:374-381`).
- Route: `POST /api/pipeline/run?full=true` (`server/routes/MediaRoutes.kt:1856-1888`). **No request body
  today** — the only per-run input is the `full` query param.
- The enabled-step list is **snapshotted in the route**: `val pipeline = configStore.current.scan.pipeline
  .filter { it.enabled }` (`MediaRoutes.kt:1865`), passed by value into `executePipeline`
  (`Main.kt:405`), which iterates that snapshot (`Main.kt:517`) and never re-reads config. So a per-run
  filter needs **no change to `executePipeline`** — and `scanTracker.setStepPlan(orderedSteps)`
  (`Main.kt:491-492`) derives Activity's step chips from the same list, so a filtered list makes the
  Activity view correct for free.
- Step labels/descriptions already exist as `PIPE_BLOCKS` (`Settings.kt:1840-1852`, e.g. `detect_segments`
  → "Detect intro & credits" / "Chapter-title match + ffmpeg black-frame / silence for Skip Intro / Skip
  Credits") and short chip labels as `PIPE_SHORT` (`:1854`) — reuse, don't duplicate.

## Requirements

### FR-PIPE1-1 — A pre-run dialog on both manual run actions
Both `Run pipeline now` and `Run pipeline now (full)` open a confirmation dialog instead of starting
immediately. (Both are full-library, multi-hour runs — `full` only drops the freshness filter — so the
choice is equally relevant to each.) The dialog states which run type was chosen, lists every **enabled**
step in execution order using `PIPE_BLOCKS`' existing name + subtitle, and has `Cancel` / `Start run`.
Uses the canonical `.modal-back` / `.modal` pattern (`design/app/detail.css:127-134`; idiomatic Kotlin
usage at `ui/Metadata.kt:511-563` — build with `innerHTML`, query off the backdrop element, close by
`.remove()`, backdrop-click and Escape to close).

### FR-PIPE1-2 — Per-step tick boxes, defaulting to "run"
Each listed step carries a checkbox, ticked by default (= will run). Unticking excludes it **from this run
only** — the Settings pipeline editor's own `pipelineSteps` edit buffer must not be touched (it is read by
`readForm()` on Save, so mutating it would silently persist the skip).

### FR-PIPE1-3 — Explain *why* a step is skippable
`detect_segments` renders with an explicit advisory note — that it is the slowest step by a wide margin,
that it only feeds Skip Intro / Skip Credits, and that skipping it leaves already-detected markers intact
and simply defers detection to a later run. This is the phase's whole point: the dialog should make the
"usually safe to untick" call obvious without the operator needing to know the pipeline internals.

### FR-PIPE1-4 — `scan_files` is not skippable
`executePipeline` runs `scan_files` regardless of whether it appears in the passed list (it falls back to a
default `PipelineStep` at `Main.kt:430`). Its checkbox is therefore rendered ticked and disabled, with a
note that discovery always runs — offering a tick that silently does nothing would be a lie.

### FR-PIPE1-5 — Remember the last choice
The unticked set persists in `localStorage` (key `js-pipeline-skip`, matching the `js-theme` precedent) so
an operator who habitually skips `detect_segments` doesn't re-untick it every run. The dialog always shows
the restored state explicitly, so a remembered skip can never be silently applied.

### FR-PIPE1-6 — Per-run skip on the API, persisted nowhere
`POST /api/pipeline/run` accepts an optional JSON body `{"skipSteps": ["detect_segments"]}`, read
defensively (`runCatching { call.receive<…>() }.getOrDefault(…)` — the established pattern at
`MediaRoutes.kt:1491`/`1579`) so an empty-body call keeps working. Applied to the existing filter at
`MediaRoutes.kt:1865`. `scan_files` is ignored if present in `skipSteps` (see FR-PIPE1-4). Nothing is
written to config; the scheduled path (`Main.kt:260-293`) is untouched.

### FR-PIPE1-7 — Make the skip visible after the fact
The run's `type` descriptor (`ScanTracker.kt:27-29`, already rendered by Activity at `Activity.kt:302-311`)
records the skip, e.g. `full · skipped: detect_segments`, so a run that took an unusually short time is
self-explanatory later. Response stays `String`-typed throughout (`MediaRoutes.kt:1879-1887` documents that
mixing `String`/`Int` in the response `mapOf` breaks serialization here).

### FR-PIPE1-8 — Fix `detect_segments`' missing Activity label
`Activity.kt:776-788`'s `stepLabel()` has no `detect_segments` case, so it falls through to `else -> step`
and renders the raw id in Activity's step chips. Add it — this phase makes that chip considerably more
likely to be looked at.

## Invariants
- **The dialog never changes persisted config.** Ticking/unticking affects exactly one run; the Settings
  pipeline editor and `config.toml` are untouched.
- **What the dialog lists is what runs** — the step list comes from the same enabled-step source the run
  itself filters, and the filtered list is what feeds Activity's step plan.
- **Skipping a step is non-destructive** — no already-computed data is cleared; the step simply doesn't run
  this time.

## Out of scope
- Per-step `scope` (`missing` vs `all`) overrides in the dialog — that stays persisted config.
- Applying a skip to the *scheduled* run (this is a manual-run escape hatch; a permanent change belongs in
  Settings).
- Cancelling/skipping a step mid-run — `POST /api/scan/cancel` already cancels the whole run.

## Source references
- Dialog + trigger: `src/wasmJsMain/kotlin/dev/jellystructure/ui/Settings.kt` (`triggerPipelineRun`,
  `PIPE_BLOCKS`, `PIPE_SHORT`, the `pipe-run`/`pipe-run-full` wiring).
- Modal pattern: `design/app/detail.css` (`.modal-back`/`.modal`), `src/wasmJsMain/.../ui/Metadata.kt`
  (`showTrackerModal`), `design/app/series-simpsons.html` (closest visual template — a confirm dialog).
- API: `src/wasmJsMain/kotlin/dev/jellystructure/api/MediaApi.kt` (`runPipeline`),
  `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/MediaRoutes.kt` (`POST /api/pipeline/run`).
- Runner (unchanged): `src/linuxX64Main/kotlin/dev/jellystructure/Main.kt` (`executePipeline`).
- Activity label: `src/wasmJsMain/kotlin/dev/jellystructure/ui/Activity.kt` (`stepLabel`).
