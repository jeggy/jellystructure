# Phase 261 — Every file check is a pipeline step with its own cadence, one job per file, and a card that says when

## Status

`Planned` — written 2026-09-25 with the owner, not dev-reviewed, not built. Spec first. Reshapes how phases
**254** (whole-file verification) and **255** (track lengths) are scheduled and shown; changes neither check.

> Owner, 2026-09-25, on the sweep jobs: *"I'm not a fan of these jobs. […] If there is no real reason for
> them being merged into a single job, like they are doing cross-checks and have to do it together, like
> when detecting intros and outros for a whole season at once, then let's keep it. But it would be very nice
> to extract this, so it's easier to keep track of the current status with number of jobs rather than
> progress of a single job, which also may be stopped because of a restart."* Then: *"it would be nice to
> keep track of everything, so items will be re-processed following the 1day/1week/1month/1year etc that we
> have in our scan pipeline configuration. And maybe have a small card on the media details page, so we can
> see what things these items have been checked against and when."* On the read cost of re-verifying whole
> files: *"let's change it, so it's configurable within the pipeline setup configuration page. So a
> standard setup could be every 5 years."*

## What happens today

- **Two library-wide sweeps outside the pipeline.** `file_integrity_sweep` (254: read every video file end
  to end through ffmpeg's demuxer) and `track_coverage_sweep` (255: probe where each audio/video track
  really ends) are each **one job row** on the segments queue over a worklist recomputed every run,
  re-queued every 15 minutes by a loop in `Main.kt`, gated by one switch (`behavior.verify_files`). The
  Activity page shows *Verify video files (667 not yet read end to end)* as a single row whose progress
  restarts at 0 after a restart (`requeueRunning`) while its label keeps the count it was enqueued with.
  Nothing is lost on restart — each file's result is persisted — but nothing on the page says so.
- **Checked once, then never again.** A stored result is current while the file's size and mtime match
  (254 FR-254-3). A file that never changes is never re-read, so silent decay of a disk is never seen.
- **The pipeline already has the cadence the owner wants** — per step, three tiers by the title's release
  age (`refresh_this_year` · `refresh_1_5y` · `refresh_older`, values `daily` · `weekly` · `monthly` ·
  `6months` · `yearly` · `never`), decided by `isDueForRecheck` against one timestamp per title,
  `media.last_examined_at`. The two file checks live outside it.
- **One timestamp for the whole pipeline.** `last_examined_at` is stamped by the scan, not per step, so
  "what has this title been checked against, and when" cannot be answered from the data. History entries
  exist for some actions (`nfo_write`, `artwork_fetch`, `tracks_reprobe`, `segments_pruned`), not for a
  successful pass that changed nothing.
- **The one job that genuinely must stay whole** is `segments_season`: intro fingerprinting compares the
  episodes of a season with each other (phase 150), and 260 already promises a running season finishes.
  Neither file check looks at two files together.

## Requirements

### A. One job per file

- **FR-261-1 — the sweeps become per-file jobs.** `file_integrity_sweep` and `track_coverage_sweep` are
  retired. Their work is enqueued as one row per file on the segments queue: types `verify_file` and
  `check_track_lengths`, `media_id` = the owning title (so the row links to it), the path in the params,
  dedupe keys `verify:<path>` / `lengths:<path>` (the existing partial unique index makes a duplicate
  enqueue a no-op), `deferWhilePlaying = true`, label *Verify · {title} · S01E04*. The title page's
  **Check now** enqueues the same rows, never deferred (254 FR-254-6, 255 FR-255-6 unchanged in effect).
  Cancel, restart (`requeueRunning`) and 260's *Empty queue* therefore work per file with no new code.
- **FR-261-2 — the queue is made safe for thousands of waiting rows.** Measured against the code: `claimNext`
  reads every queued row of every lane on every claim and JSON-parses each one's params to read
  `deferWhilePlaying`; every state change broadcasts a snapshot to the admin socket; `listRecent(50)` is
  scanned for the last subtitles failure. Before FR-261-1 lands: the claim reads one candidate per lane
  (`ORDER BY created_at LIMIT 1`, with the defer flag as a column on `media_job`, not inside the JSON);
  broadcasts of per-file rows are coalesced (at most one summary event per type per second, plus the row's
  own event when it finds something or fails); the Activity page's Jobs view groups per-file rows of one
  type into one line — *Verify files · 2,261 waiting · 41 done today · 1 finding* — expandable to rows, so
  the number of jobs **is** the status. `/api/health` `job_queues` already counts waiting rows per lane.
- **FR-261-3 — order is decided at enqueue time.** Rows are enqueued newest-modified first (254's rule), in
  batches: a new or changed file is enqueued by `scan_files` the moment it is ingested, ahead of whatever
  periodic work is waiting, so a fresh download is verified within minutes as today.

### B. A cadence, from the pipeline page

- **FR-261-4 — two new pipeline steps.** `verify_files` and `check_track_lengths` are `[[scan.pipeline]]`
  steps like every other, shown on the pipeline setup page with the same block, the same *Refresh unchanged
  titles on a schedule* toggle and the same three-tier table. `behavior.verify_files` is retired: on first
  boot a `false` migrates to both steps `enabled = false`; the key is then ignored. The `Main.kt` loop goes.
- **FR-261-5 — longer cadences exist.** `cadenceMs` and the picker gain `2years` and `5years` (the picker
  labels: *every 2 years*, *every 5 years*). Shared by every step.
- **FR-261-6 — defaults that respect the disk.** A whole-file read of the library is 200–300 GB; the tail
  probe reads ~15–60 MB per file. Defaults: `verify_files` **5years / 5years / 5years**; `check_track_lengths`
  **yearly / 2years / 5years**. An operator who wants daily verification of this year's titles can have it.
- **FR-261-7 — "due" is one rule.** A file is due when it has no current result (no row, or size/mtime
  changed — immediate, cadence irrelevant) **or** its `checked_at` is older than the tier's cadence for the
  title's release age, decided by the same `isDueForRecheck` the scan uses (with the row's `checked_at` in
  place of `last_examined_at`). A stored finding is never re-derived by the cadence: a file with a finding
  stays flagged until it changes or the operator re-runs it.
- **FR-261-8 — a step's run is a pass, not a job.** When the scheduled pipeline reaches `verify_files` it
  enqueues one row per due file and moves on (the same enqueue-only shape as `detect_segments`); the run
  summary says *2,261 files queued for verification*. The pre-run dialog (154) can untick either step for
  one run.

### C. What has this title been checked against, and when

- **FR-261-9 — per-step completion, per title.** New table `item_step_run(item_id, step, ran_at, outcome,
  detail, PRIMARY KEY(item_id, step))`, the **last** run of each step for each title: written by every
  pipeline step for every item it processed (`ok` · `changed` · `skipped` with why · `failed` with why), in
  the same place `last_examined_at` is stamped. For the two file steps the row is derived from the per-file
  tables (latest `checked_at`, worst state across the title's files), never a second source of truth.
  `last_examined_at` stays what it is (the scan's own stamp); nothing that reads it changes.
- **FR-261-10 — the Checks card.** On the title page's Overview tab, a *Checks* card with one line per
  enabled step in pipeline order: *Verify files · 3 Aug · clean · next due 2031* — the step's label, when it
  last ran for this title, its outcome, and the next due date computed by FR-261-7's rule (so the card and
  the scheduler cannot disagree; *never* reads *never*; a step disabled in the pipeline reads *off*). The
  two file steps expand to the file rows (a series groups them per season, as 255's block does). A step that
  has never run for the title reads *not yet*, never a blank. Where a per-title action already exists
  (re-probe, re-pull TMDB, fetch artwork, redetect segments, pre-warm subtitles, Check now) the line carries
  its ↻; where none exists there is no button — the card never invents an action.
- **FR-261-11 — nothing derived on the page.** The card renders one payload from `GET
  /api/media/{id}/checks` (step · last · outcome · detail · next due · per-file rows); the page computes no
  date and no state.

## Non-goals

- Splitting `segments_season`, `presize_artwork` or `waveform_backfill`. The season job must stay whole;
  the other two may follow this shape later but are not this phase.
- Any change to what 254 or 255 check, store or advise.
- A global "checks" page. The Dashboard's attention list and the Library filters already carry findings;
  this phase adds cadence and the per-title card only.

## Open questions

1. **First run volume.** The first `verify_files` pass after this lands enqueues every file whose result is
   older than five years — which, since 254 is a week old, is none; the first `check_track_lengths` pass
   likewise. But an operator who sets *every day* on this year's tier enqueues a few hundred rows a day.
   Should a step refuse to enqueue more than N rows per run (lean: no cap — the queue is bounded by
   FR-261-2 and the rows are cheap; say the count in the run summary instead)?
2. **Should the Checks card also list steps that do not run per title** (`wait`, `notify`)? Lean: no — the
   card lists what was done *to this title*.
3. **Does the Activity page keep a per-file row for a clean result at all**, or only for findings and
   failures (with clean ones counted in the group line)? Owner said flooding is fine; lean: keep every row,
   grouped, and let the age-based prune (`media_job` already deletes finished rows past a cutoff) bound it.

## Acceptance

1. Unit: `cadenceMs("5years")` and `"2years"` are the right lengths; `isDueForRecheck` with a `checked_at`
   older than the tier says due, a changed file is due regardless of cadence, and a stored finding is not
   re-queued by the cadence.
2. Unit: enqueuing the same file twice for the same check yields one row (dedupe), and `claimNext` reads one
   candidate per lane.
3. On the Settings page: both steps appear in the pipeline with the three-tier table; `5years` is offered;
   the written TOML round-trips; a config with `behavior.verify_files = false` boots with both steps off.
4. On production after deploy: the Jobs view shows *Verify files · N waiting* as a group with per-file rows;
   a restart leaves the count intact (running row requeued, nothing else moves); a title's Overview tab
   shows the Checks card with a line per step and *next due 2031* on *Verify files* under the default cadence.

## Design mirror

`design/app/settings.html`'s pipeline palette needs the two new blocks and the two cadence values;
`design/app/media.html` / `series.html` need the Checks card; `design/app/activity.html`'s Jobs view needs
the grouped per-file rows. None are drawn yet — this spec leads the mockups.
