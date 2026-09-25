# Phase 262 — "Defer while a TV is watching" is one switch, and every queue job obeys it

## Status

`Planned` → **built 2026-09-25** (see §Build). Written 2026-09-25 with the owner, not dev-reviewed.

> Owner, 2026-09-25: *"Currently all these jobs get paused while someone is streaming something. Can we
> make this pause if someone is streaming into a setting? So I can very easily run these jobs, even if
> someone is watching something."*

## What happens today

- The setting exists: `scan.defer_while_playing` (default `true`), shown under Settings → Advanced as
  *Defer scans while a TV is watching*, read by scheduled/realtime scans and the pipeline's heavy steps
  (178, 213 FR-213-6).
- **Three job producers ignore it** and hard-code `MediaJobParams(deferWhilePlaying = true)`: phase 254's
  `file_integrity_sweep`, phase 255's `track_coverage_sweep`, and the pipeline's `prewarm_subtitles`
  enqueue. Their rows are then held back by `claimNext` whenever anything plays, and the two sweeps also
  stop themselves between files on `isPlaybackActive()` — with the toggle off or on. An operator who
  switched deferral off still watched *Verify video files* sit at "playback started — 667 file(s) still to
  verify" through an evening.
- The flag travels inside each row's params, so even a producer that honoured the setting would freeze the
  answer at enqueue time: flipping the toggle would not release a row already waiting.

## Requirements

- **FR-262-1 — one switch, read live.** Whether a queued job waits for playback is decided at claim time
  and between a sweep's files as `row.deferWhilePlaying && scan.defer_while_playing`. The row's own flag
  keeps its meaning (a job that never defers, such as an operator's *Check now* or a repair, stays that
  way); the household setting is the override. Switching it off releases everything waiting, with no
  re-enqueue; switching it on holds the next claim.
- **FR-262-2 — the producers say the truth.** The two sweeps and the subtitle pre-warm enqueue with
  `deferWhilePlaying = true` as before (they are background work), and the decision above is what makes
  the setting count. No producer reads the setting itself, so a later change of mind is one line, in one
  place.
- **FR-262-3a — the toggle lives with the worker settings.** Settings → Libraries → *Scanning*, beside Scan
  workers and Job workers (owner, 2026-09-25: *"move this toggle into the libraries configuration, so it's
  alongside all the worker settings"*), no longer under Advanced. Same key, same behaviour.
- **FR-262-3 — the toggle says what it covers.** Its hint on the Settings page names the background
  queue jobs (whole-file verification, track lengths, intro/credits detection, subtitle pre-warm) beside
  the scans it already named, and says the change takes effect at once.
- **FR-262-5 — the pipeline's own wait obeys it live too.** A scheduled or realtime run sitting in
  *Pipeline deferred — TV playing* re-reads the switch every 2 s (the same tick as *Run anyway*), so
  switching it off releases the run at once; a manual *Scan library* click never deferred and still does
  not. Before this the run read the setting once at its start.
- **FR-262-4 — health tells the same story.** `/api/health`'s `job_queues.subtitles_deferred_by_playback`
  is computed with the same rule, so it can never say *deferred* while the switch is off.

## Non-goals

- A per-job or per-step deferral setting. One household switch; 261 may add per-step cadence later, not
  per-step deferral.
- Changing what "playing" means (`isPlaybackActive()`).

## Acceptance

1. Unit: `deferDecision(rowDefers = true, householdDefers = false)` is false; `(true, true)` is true;
   `(false, true)` is false.
2. With the switch off and a TV playing, the Jobs view shows the verification sweep running (files
   advancing); with it on, the sweep stops between files with the existing "playback started" reason.

## Build (2026-09-25)

`MediaJobQueue.deferDecision()` (pure, companion) is the one rule; `Media_job.deferWhilePlaying()` — used by
`claimNext` and the health snapshot — and the two sweeps' between-files check now go through it with
`configStore.current.scan.deferWhilePlaying`. The Settings hint names the queue jobs. `DeferDecisionTest` (1). `awaitPlaybackClear` takes a `stillDefers` lambda and checks it on every tick (FR-262-5).
