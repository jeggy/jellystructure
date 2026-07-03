# Phase 123 — Media-job progress: accurate ETA & time-remaining for remux jobs (FR-JB1)

## Goal
Long ffmpeg operations (an audio-track reorder is a full remux) should show an accurate live **progress
bar %, speed, AND time-remaining/ETA** in the Activity "Jobs & workers" view. ffmpeg already emits
everything needed —
`frame=48203 fps=330 … time=00:15:15.03 … speed=6.27x` — and the media item's total duration is known,
so `%`, ETA, and speed are all derivable. Today `%` and speed are shown but **ETA is missing at every
layer**.

## Current state (verified in code — this is Phase 109 under-delivering, not new scope)
Phase 109 §FR-B.4 literally named `eta_seconds` and the built design mockup shows "~2m10s left"; it was
never wired.
- `FfmpegRunner.runRemuxTracked` (`FfmpegRunner.kt:101`) runs `… -progress pipe:1 -nostats`;
  `runCommandTracked` (`:137`) parses `out_time_ms` (µs) → `lastOutTimeUs` and `speed=` → `lastSpeed`,
  and computes pct **in the runner** = `((out_time_s)/durationSeconds*100).coerceIn(0,100)` (`:164`;
  duration from `FfmpegRunner.probeDurationSeconds:87`, passed at `MediaJobQueue.kt:227,267`). The
  callback is `(pct, speed)` — `out_time`, duration, and speed are **all in scope** at the
  `progress=continue` branch but **no ETA is computed** and out_time/duration are not passed out.
- `media_job` table (`MediaJob.sq`): `pct`, `speed`, `file_count`, `files_done` — **no `eta_seconds`,
  no `duration`**. `updateProgress` persists `(pct, speed, files_done)`.
- `MediaJobSnapshot` (commonMain `jobs/JobEvent.kt:37`) + FE mirror (`MediaApi.kt:98`): `pct`, `speed`,
  `fileCount`, `filesDone` — **no `etaSeconds`**.
- `Activity.kt renderJobsPanel` (`:404-413`): already a determinate bar `<i style="width:${r.pct}%">` +
  "speed=<x> · <pct>% · file N of M". A reusable `formatRemaining(ms)` helper exists (`:702`). Design
  target `#jobff` (`design/app/activity.html:176`) wants "…speed=6.2x · ~2m10s left" — the ETA is the
  sole missing piece.

## Requirements

### A. Compute ETA at the source (backend — matches the named `eta_seconds` field)
1. `FfmpegRunner`: at the `progress=continue`/`end` branch compute
   `remaining_s = (durationSeconds − out_time_s) / speedMult`, where `out_time_s = lastOutTimeUs / 1e6`
   and `speedMult = speed.removeSuffix("x").toDoubleOrNull()`. Guard `speedMult > 0` and non-null
   duration, else ETA is `null`. Extend the progress callback to `(pct, speed, etaSeconds: Long?)`.
2. `MediaJob.sq`: add `eta_seconds INTEGER`; new migration
   `src/commonMain/sqldelight/dev/jellystructure/db/13.sqm`
   (`ALTER TABLE media_job ADD COLUMN eta_seconds INTEGER;`); widen `updateProgress`; null it in
   `requeueRunning`.
3. `MediaJobSnapshot` (commonMain) + FE mirror (`MediaApi.kt`): add `etaSeconds: Long? = null`.
   `MediaJobQueue.onProgress`/`toSnapshot`: accept, persist, and map it.

### B. Show it (frontend)
1. `Activity.kt:413`: append `r.etaSeconds?.let { " · ~${formatRemaining(it * 1000.0)} left" } ?: ""`
   (helper already present). Line becomes e.g. "speed=6.2x · 41% · ~2m10s left". The bar is unchanged.
2. ETA jitter: showing the latest sample is acceptable; light exponential smoothing of `speedMult` is a
   nice-to-have, not required.

## Scope
- `FfmpegRunner` (ETA calc + widened callback), `MediaJob.sq` + `13.sqm`, `MediaJobQueue`
  (persist + map), `MediaJobSnapshot` (commonMain), `MediaApi.kt` mirror, `Activity.kt` (one line).
  ~8 small edits, one migration, no new endpoints.

## Non-goals
- No per-frame throughput/bitrate display (`frame`/`fps`/`size`/`bitrate` deliberately not surfaced —
  ETA + speed + % is the useful set).
- No change to Phase 116's separate scan-item ETA path (it only donates `formatRemaining`).
- No ETA for flag-only mkvpropedit ops (near-instant, not remuxes).

## Acceptance
- Reordering audio on a ~45-min episode shows a determinate bar plus "speed=6.2x · ~7m left" that
  counts down and converges as it finishes.
- ETA is **absent** (not "NaN"/"0s") when duration is unknown or speed hasn't stabilised.
- `GET /api/jobs` and the WS `MediaJobSnapshot` both carry `eta_seconds`.
