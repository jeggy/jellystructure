# Phase 265 — Skip steps on every manual full run, not only from Settings

> Owner, 2026-09-26: *"Currently when in the settings > Libraries page it's possible to schedule a full
> pipeline scan, and then skip some steps with the provided popup. We want this same popup to be available
> when doing a full pipeline scan directly from the Library and Dashboard pages."*

## Status

`Planned` — written 2026-09-26, **dev-reviewed 2026-09-26** against `main` `0e5e434f` (see *Dev review*
at the end). Admin frontend plus one route change and one small new route. **Numbering:** verified
against `STATUS.md` the same day — admin taken through **264**.

Extends **Phase 154** (the pre-run dialog, FR-PIPE1-1…8) from one page to every manual full run.

## What is there today

- **Settings → Libraries → *Run pipeline now*** (both faces of the split button) opens Phase 154's
  dialog (`showPipelineRunDialog`, `Settings.kt`). It lists the steps, lets them be unticked for this run
  only, remembers the last choice (`localStorage` `js-pipeline-skip`), and starts
  `POST /api/pipeline/run` with `{"skipSteps": [...]}`.
- **Library → *▶ Scan library*** (`#scan-btn`, `#scan-full`, `Library.kt`) and **Dashboard →
  *▶ Scan library*** (`#dash-scan`, `#dash-scan-full`, `Dashboard.kt`) call `MediaApi.startScan(full)` →
  `POST /api/scan`. Since Phase 175 that runs **the same configured pipeline** through the same
  `launchScanRun`, every step included. The label says "scan", but the run is the whole pipeline, with
  no preview and no way to skip `detect_segments` (hours) or the file checks.
- **The command palette's *Start full scan*** (`Shell.kt`) calls `startScan(full = true)`, the same run
  again, also without the dialog.
- `POST /api/scan` reads no body. The skip list exists only on `POST /api/pipeline/run`.

Everything the dialog needs is private to `Settings.kt`: the dialog itself, `PIPE_BLOCKS` (step names
and subtitles), `PIPE_SHORT` and `FILE_CHECK_STEPS`.

### A mismatch found on the way

The dialog lists `saved.filter { it.enabled }`, the saved pipeline's enabled steps. The server runs
`effectivePipeline(config)`, which is the same list **unless no step is enabled**. In that case it runs a
built-in default (`scan_files`, `pull_tmdb`, `fetch_artwork` when artwork fetching is on, and the two
file checks). So on an install with an empty pipeline the dialog shows nothing while five or six steps
run. That breaks Phase 154's own invariant, *what the dialog lists is what runs*, and moving the dialog
onto more pages would spread it.

## Requirements

**FR-265-1 — One dialog, shared.** `showPipelineRunDialog`, `PIPE_BLOCKS`, `PIPE_SHORT`,
`FILE_CHECK_STEPS` and the `js-pipeline-skip` key move out of `Settings.kt` into one shared file (for
example `ui/PipelineRunDialog.kt`). Settings keeps using it unchanged, including its *unsaved pipeline
edits* note, which is only ever shown there. The same markup, the same notes (`detect_segments`'s *usually
safe to skip*, `scan_files` ticked and disabled, the file-check note), and the same modal pattern.

**FR-265-2 — Library and Dashboard open it.** Both faces of *Scan library* on both pages open the dialog
instead of starting at once. The title names the button that was pressed: *Scan library* or
*Scan library (full rescan)*, and the full face keeps the dialog's *every step sees the whole library*
line. *Start run* calls `POST /api/scan` (with `?full=true` for the full face) and the chosen skip list.
The toast names what was skipped, as Settings' does (*"Scan started · skipped Segments"*). Cancel starts
nothing. The button's running state and the Activity chips are unchanged.

**FR-265-3 — The command palette's *Start full scan* opens it too.** It is the same run. A palette
command that silently skips the preview the buttons now show would be the one door left open.

**FR-265-4 — `POST /api/scan` takes the same skip list.** It accepts the optional body
`{"skipSteps": [...]}` exactly as `POST /api/pipeline/run` does: read defensively (a bodyless call from
curl, a test or an older frontend still works), `scan_files` dropped, passed as `skipSteps` to
`launchScanRun`, never written to config. `?library=` and `?full=true` keep working. Both routes parse
the body through **one** helper, so they cannot drift. The run descriptor already records the skip
(`launchScanRun`'s *· skipped: …* suffix), so Activity shows it with no change.

**FR-265-5 — The dialog lists what the server will run.** A new read-only `GET /api/pipeline/plan`
answers `effectivePipeline(config)` as it stands (step id, `enabled`, `scope`, in execution order), the
same function `launchScanRun` filters. Every page's dialog, Settings included, builds its list from this
answer, not from the config object. That closes the empty-pipeline mismatch above, and it keeps the
client from re-deriving the server's defaulting rule (render-never-compute).

**FR-265-6 — Nothing to skip, no dialog.** When the plan holds nothing but `scan_files`, pressing the
button starts the run at once, as it does today. A dialog whose only row is a disabled tick box is a
pointless extra click.

**FR-265-7 — One remembered choice for every page.** The remembered skip (`js-pipeline-skip`) is shared:
unticking `detect_segments` in Settings shows it unticked the next time Library or Dashboard opens the
dialog, and the other way round. It is always shown in the dialog, never applied silently, as
FR-PIPE1-5 requires.

**FR-265-8 — Tests follow the button.** `tests/e2e/scan-fixture.spec.ts` clicks *▶ Scan library* on the
Dashboard and waits for the run. With this phase that click opens the dialog, so the test must press
*Start run* too, or it waits two minutes and fails. Add one e2e case: open the dialog from Library,
untick `detect_segments`, start, and assert that the run's descriptor on Activity reads
*… · skipped: detect_segments*. Backend: `POST /api/scan` with a skip body skips the step, without one
it runs every step, and `scan_files` in the body is ignored.

## Non-goals

- The scheduled run and realtime ingest. They have no one to ask, and Phase 154 already scoped the
  dialog to manual runs.
- *Resume* on the Dashboard. It continues a cancelled run with that run's own plan.
- Bazarr's *Run full scan* on the Subtitles page, which is Bazarr's scan, not the pipeline.
- Changing any step's persisted `enabled`/`scope`. That stays in Settings.

## Acceptance

1. Library → *▶ Scan library*: the dialog opens, titled *Scan library*, listing every step the run will
   do. Untick *Detect intro & credits* → *Start run*: the toast says it was skipped, Activity's chips have
   no Segments chip, and the run's descriptor names the skip.
2. Dashboard → *Scan library (full rescan)*: the same dialog, titled for the full rescan, with the
   remembered skip already unticked.
3. The command palette's *Start full scan* opens the same dialog.
4. A test install with no pipeline step enabled: the dialog lists the built-in default steps the server
   actually runs (today it would list none).
5. `curl -X POST /api/scan` with no body starts a full run exactly as today.
6. `scan-fixture.spec.ts` and the new e2e case pass in CI.

## Dev review (2026-09-26, against `main` `0e5e434f`)

The call sites and routes are where the spec says. Seven items.

1. **The five triggers.** Library: `#scan-btn` / `#scan-full` click handlers (`Library.kt:300`, `:303`)
   → `MediaApi.startScan(full)` (`:444`). Dashboard: `#dash-scan` / `#dash-scan-full` (`Dashboard.kt:98`,
   `:101`) → `:373`, which also carries the **resume** path (`if (resume) MediaApi.resumeScan()`). Resume
   stays dialog-free, per the non-goal. The palette's *Start full scan* (`Shell.kt:258-263`). The buttons'
   running-state code (`Library.kt:538-554`, `Dashboard.kt:473-482`) is untouched.
2. **What moves.** `showPipelineRunDialog` (`Settings.kt:2798`), `PIPE_SKIP_KEY`, `PIPE_BLOCKS` (`:2618`),
   `FILE_CHECK_STEPS` (`:2636`) and `PIPE_SHORT` (`:2637`) are all `private` to `Settings.kt`. They become
   `internal` in `ui/PipelineRunDialog.kt`. The dialog keeps its `hasUnsavedEdits` parameter; only
   Settings' call site (`:3246`) ever passes `true`.
3. **The plan is one function, used twice.** `runPipeline` builds the step list Activity shows as
   `listOf("scan_files") + pipeline.filter { it.step != "scan_files" }` (`PipelineEngine.kt:220`), over
   `effectivePipeline(config)` (`:130`) minus the skips (`MediaRoutes.kt:2347`). Extract that ordering into
   one pure function. `runPipeline` calls it, and `GET /api/pipeline/plan` answers it. Then the dialog, the
   chips and the run cannot list different steps. The response is a `@Serializable` list of
   `{step, scope}`: the route file's own note (`MediaRoutes.kt:2079`) records what a mixed-type `mapOf`
   does to serialization.
4. **The dialog takes the plan, not the config.** Its `saved: List<PipelineStep>` parameter becomes the
   plan answer. Settings still fetches the saved config (`ConfigApi.get()`), but only to compare against
   its edit buffer for the unsaved-edits note.
5. **One body parser for both routes.** `PipelineRunRequest` (`MediaRoutes.kt:133`) and its defensive
   receive (`:2071`) become a small helper that both `POST /api/scan` (`:2031`) and `/pipeline/run` call.
   `/scan` then passes `skipSteps` to `launchScanRun`, which already takes it (`:2343`) and already writes
   the *· skipped: …* descriptor (`:2352`). `MediaApi.startScan` (`MediaApi.kt:445`) gains the same
   `setBody` as `runPipeline` (`:458-462`).
6. **FR-265-6 reads the plan's length.** A plan of exactly `["scan_files"]` means start at once.
   `effectivePipeline` falls back to the built-in default only when *no* step is enabled, so this case
   means an operator enabled `scan_files` alone.
7. **The e2e change is one click.** `scan-fixture.spec.ts:106` clicks *Scan library* by text; it then
   presses `#prun-go`. `scan-replace.spec.ts:81` posts to `/api/scan` directly and is unaffected (item 5
   keeps the bodyless call working).

**Net effect.** One file moved out of `Settings.kt`, three call sites wrapped, one helper shared by two
routes, one small GET route, one `MediaApi` parameter, one e2e click plus the new case. No migration, no
config change.
