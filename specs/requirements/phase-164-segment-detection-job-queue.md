# Phase 164 — `detect_segments` off the pipeline's critical path, into a de-duplicated job queue (FR-SEG3)

> Reported 2026-08-14: *"The detect segments is starting to become very slow and staying in the way of
> all other normal phase processing."* Segment detection should still be **triggered** by the pipeline,
> but must stop **being** a pipeline step that the run has to wait for — so a pipeline run can start
> again while detection from the previous run is still working through the library.

**Status:** Planned — dev-authored, not yet built.

## 1. The problem, precisely

`detect_segments` is an ordinary step inside `executePipeline`
(`src/linuxX64Main/kotlin/dev/jellystructure/Main.kt:656-711`). It runs **two** `runPipelineStepPool`
passes: a per-item chapter/heuristic pass, then — when `detect_fingerprint` is on — a per-**season**
Chromaprint pass that `fpcalc`s every episode of every eligible season and correlates them pairwise
(`PipelineStepOps.detectIntroFingerprintsForSeason` / `detectOutroFingerprintsForSeason`). On this
library that is hours of work, and it is by far the slowest thing the pipeline does — the Settings UI
already says so out loud (`Settings.kt:2273`: *"Usually safe to skip — by far the slowest step
(hours; it decodes each episode)"*).

Three concrete consequences, all of them structural rather than "it's just slow":

1. **The whole pipeline run is held open for its duration.** `executePipeline` is called inside
   `runTagged(..., scanTracker)`; `scanTracker.running` stays true until the last step returns.
2. **Every other automated run is skipped while it runs.** The scheduler loop
   (`Main.kt:299`) does `if (scanTracker.running) { Logger.info("Scheduled run skipped — a scan is
   already running"); delay(60_000); continue }`. A `0 */6 * * *` schedule whose `detect_segments`
   pass takes seven hours silently loses a scheduled run — nothing else in the pipeline (TMDB
   re-pulls, artwork, NFO, Jellyfin sync, IMDb ratings) gets to run either, even though none of them
   depend on segments.
3. **The work is invisible and un-managed when it is triggered from anywhere else.**
   `POST /api/segments/redetect` (`SegmentRoutes.kt:337-355`) is a bare `appScope.launch { … }`:
   no queue, no dedup, no progress, no cancel, no restart-survival. Clicking "detect again" twice on
   the same season starts the same expensive correlation twice, concurrently, against the same rows.

There is no ordering reason for `detect_segments` to be inline. Segments live in their own
`media_segment` table since Phase 163; nothing downstream of the step consumes them — `NfoWriter`
writes no segment fields (verified: the only `credits` in `NfoWriter.kt` is a writer-credit person
tag), `sync_jellyfin` pushes NFOs, and Ravilo reads segments live through `DetailService`. The step
is a pure producer with no in-run consumer.

## 2. Design decision — extend the existing `media_job` queue, don't build a second one

Phase 109 already ships a persistent, restart-surviving, DB-backed job queue with a REST surface
(`JobsRoutes.kt`), live WS updates (`JobEvent.MediaJobUpdate`), and the **Jobs & workers** panel in
Activity (`Activity.kt:423-532`) with running/queue/recent lists and cancel/retry. Rebuilding that for
segments would duplicate all of it.

What does *not* fit as-is: `MediaJobQueue` is deliberately **concurrency 1** — a global FIFO with a
`bulkRunning` mutual-exclusion flag — because its only job types are full-file ffmpeg **remuxes**,
where two at once would double disk I/O on the same array. Segment detection is a different
workload: short reads, `nice`/`ionice`'d, already bounded by `ProcessGate` (16 permits), and it wants
real parallelism.

So: **one table, two lanes.**

- `media_job` gains a `lane` column (`'media'` | `'segments'`, default `'media'`) and a nullable
  `dedupe_key`.
- `MediaJobQueue` gains a second, independent worker loop for the `segments` lane with its own
  concurrency, leaving the `media` lane's single-worker FIFO guarantee exactly as it is today.
- The Jobs page renders **two worker lines** instead of one, and the queue/recent lists gain a lane
  badge. Everything else (cancel, retry, the WS event, the count badge) is reused unchanged.

## 3. Functional requirements

### FR-164-1 — Schema: lane + dedupe key (migration 33)

```sql
ALTER TABLE media_job ADD COLUMN lane TEXT NOT NULL DEFAULT 'media';
ALTER TABLE media_job ADD COLUMN dedupe_key TEXT;

CREATE INDEX media_job_lane_state ON media_job(lane, state);

-- The whole "the same item must never be in the queue twice" guarantee, enforced by the database
-- rather than by a check-then-insert in Kotlin (two concurrent enqueues — a pipeline run and an
-- operator clicking "detect again" — would otherwise both read "not present" and both insert).
CREATE UNIQUE INDEX media_job_dedupe_active
    ON media_job(dedupe_key)
    WHERE dedupe_key IS NOT NULL AND state IN ('queued', 'running');
```

`MediaJob.sq` must be updated to match the post-migration shape byte-for-byte (this repo's standing
`.sqm`/`.sq` rule, enforced by `verifySqlDelightMigration`).

`enqueue` becomes dedup-aware: the insert is attempted, and a unique-constraint violation is **not an
error** — it means an identical unit of work is already queued or running, and the existing row's
snapshot is returned instead. The caller cannot tell the difference except through a new
`deduped: Boolean` on the returned snapshot, which the redetect endpoints use for their toast copy
("already queued").

### FR-164-2 — Work-unit granularity and dedupe keys

The unit must match the unit detection actually operates on, otherwise dedup is either too coarse
(blocking legitimate work) or too fine (letting the same fingerprint correlation run twice).

| Trigger | Job type | Unit | `dedupe_key` |
|---|---|---|---|
| Movie (pipeline or editor) | `segments_movie` | one movie | `seg:movie:<itemId>` |
| TV season (pipeline or editor "detect again for the season") | `segments_season` | one season of one series | `seg:season:<itemId>:<season>` |
| Selected episodes (editor multi-select) | `segments_episodes` | the selected episodes of one series+season | `seg:episodes:<itemId>:<season>:<sorted,joined,episodeKeys hashed>` |

A `segments_season` job runs the same two tiers the pipeline runs today for that season: the
chapter/heuristic pass over the season's episodes, then (if `detect_fingerprint` is on and the season
has ≥2 eligible episodes) the intro + outro fingerprint passes. A `segments_episodes` job runs the
chapter/heuristic tier **only**, exactly matching `redetectEpisodes`' existing deliberate scope
decision (the fingerprint tier is a whole-season consensus algorithm — narrowing its input degrades
the result for every episode, it doesn't just narrow the work; see
`SegmentRoutes.kt`'s own comment on this).

**Season-vs-episode overlap is deliberately not deduped against each other.** A queued
`seg:season:X:2` and a queued `seg:episodes:X:2:…` are allowed to coexist; the second is cheap, and
`PipelineStepOps` re-checks per-kind existence and per-marker locks before every write, so the worst
case is a small amount of redundant probing, never a wrong or overwritten marker.

### FR-164-3 — `detect_segments` becomes an enqueue-only pipeline step

`Main.kt`'s `"detect_segments" ->` branch is replaced with: compute the same working set it computes
today (`needsDetection` for `scope = "missing"`, everything for `scope = "all"`), expand it into the
work units of FR-164-2, enqueue them all, then finish. It emits `StepStarted`/`StepFinished`
immediately like the `notify`/`wait` steps do, with a summary of the form
`"enqueued 214 detection jobs (37 already queued)"`, and **never** blocks the run.

The pipeline run therefore completes in the time the other steps take. `scanTracker.running` drops,
and the next scheduled run is no longer skipped.

The Phase 154 pre-run step picker keeps working unchanged: unticking `detect_segments` for one run
means nothing is enqueued for that run.

### FR-164-4 — The segments worker lane

- Concurrency comes from a new `behavior.segment_workers` (TOML `segment_workers`), default `2`,
  clamped `1..8`. Deliberately a **separate, smaller knob** from `scan_workers`: this lane runs
  concurrently with request serving now, and `ProcessGate`'s 16 permits are shared with artwork,
  probing and everything else. Re-polled live (same pattern as `runPipelineStepPool`'s worker-count
  supplier) so a change in Settings takes effect on the next job dispatch, not the next restart.
- Jobs are drained oldest-first within the lane.
- `start()`'s existing `requeueRunning()` already covers restart recovery for both lanes — a segment
  job interrupted by a restart is re-queued and simply re-runs (detection is idempotent and
  lock-respecting).
- Every existing per-process protection stays: `ProcessGate` permits, `nice -n 19 ionice -c3`, and
  `-threads 2` on the ffmpeg heuristic calls.

### FR-164-5 — Cancel, retry, progress

- **Cancel, queued** — unchanged from today: mark `cancelled`.
- **Cancel, running** — the `media` lane's approach (`pkill -f` on the unique temp output path) does
  not apply: segment detection produces no temp file to match on. Instead it is **cooperative**: the
  worker sets a per-job cancel flag that `PipelineStepOps`' per-episode loops check between episodes
  and between the intro/outro tiers. The currently in-flight `ffmpeg`/`fpcalc` child finishes (seconds
  to a couple of minutes); the job then stops and is marked `cancelled`. The UI must say this
  plainly ("stopping after the current episode") rather than implying an instant kill.
- **Retry** — reuses the existing retry path; re-enqueues under the same dedupe key.
- **Progress** — `files_done`/`file_count` map to episodes-done/episodes-in-unit. `pct` is derived
  from those; `speed`/`eta_seconds` stay null for this lane (there is no single ffmpeg progress
  stream to read). The running card must not render an empty `speed=` for segment jobs.

### FR-164-6 — Jobs & workers page

`design/app/activity.html` + `Activity.kt`'s `#view-jobs`:

- Two worker lines. Existing: **Media worker** — *concurrency 1 · FIFO queue · remux runs at low I/O
  priority*. New: **Segment detection** — *concurrency N · runs alongside the pipeline · low CPU/IO
  priority*, with its own running/queued/done-today chips.
- Queue and recent rows gain a lane badge and segment-specific labels via `jobTypeLabel`:
  `segments_movie` → "intro & credits detection", `segments_season` → "intro & credits detection
  (season)", `segments_episodes` → "intro & credits detection (episodes)".
- The `#jobs-count-badge` counts both lanes.
- The running card shows `episode 7 of 12` from `files_done`/`file_count` instead of the remux
  command line.

### FR-164-7 — Redetect endpoints enqueue instead of launching

`POST /api/segments/redetect` stops calling `appScope.launch` and enqueues the matching work unit(s)
instead, responding `202 Accepted` with `{ jobId, deduped }`. The segment editor's existing toasts
("Queued detect_segments for …") become literally true, and gain the already-queued variant. The
editor should link the toast to Activity ▸ Jobs & workers.

### FR-164-8 — Retention

`media_job.deleteOld` exists in `MediaJob.sq` but **is not called from anywhere today** (verified).
With a segments lane the table grows by one row per season per full run, so this phase must actually
wire it: a daily sweep deleting terminal-state rows older than 14 days, run from the same background
launcher family as the WAL checkpoint in `Main.kt`.

## 4. Non-goals

- Changing the detection algorithms themselves (Phase 159's accuracy work stands).
- Making `detect_segments` incremental across runs beyond the existing `scope`/lock semantics.
- Any queue for other pipeline steps. Only `detect_segments` has this problem; artwork/TMDB/NFO
  finish in minutes.
- Priorities/reordering within the segments lane. Oldest-first is enough; the editor's own redetect
  is small and will not be starved behind a full-library sweep for long because the lane is
  concurrent.

## 5. Open questions to settle before/while building

1. **Does anything actually read segments within a single pipeline run?** Investigation says no
   (NFO/Jellyfin sync/Ravilo all independent), but confirm against `RealtimeIngestService.runConfiguredSteps`
   — its `detect_segments` branch (`RealtimeIngestService.kt:146-148`) runs `PipelineStepOps.detectSegments`
   inline for a **single** freshly-ingested item. That path is short (one movie or one series' new
   episodes) but should probably enqueue too, for consistency and dedup against a concurrent pipeline
   sweep. Recommendation: enqueue there as well.
2. **`file_count` for a season job** — episodes eligible after `partCount == 1` filtering, computed at
   enqueue time or at run time? Run time is more accurate (the library may have changed since the job
   was queued); enqueue time gives the queue list something to show. Recommendation: enqueue-time
   estimate, corrected on start.
3. Whether `segment_workers` should also cap the *fingerprint* tier separately from the
   chapter/heuristic tier — `fpcalc` is markedly heavier than `blackdetect`.

## 6. Verification

- `verifySqlDelightMigration` green; `.sqm`/`.sq` byte-identical shape.
- A unit test proving the partial unique index rejects a second active enqueue with the same
  `dedupe_key` and accepts one after the first reaches `done`/`failed`/`cancelled`.
- Manual: start a pipeline run with `detect_segments` enabled on the live library — the run must
  finish in minutes with a populated segments queue, and a second **Run pipeline now** immediately
  afterwards must be accepted (not "a scan is already running").
- Manual: click "detect again" twice on the same season — second click reports already-queued, and
  exactly one job exists.
- Manual: restart the backend mid-lane — running segment jobs come back as `queued` and drain.
