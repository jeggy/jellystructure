# A stalled or starting play still holds background work
Task: DEB-5535

> Owner, 2026-11-25 (DEB-5531): picked *"Background jobs also wait for a play that is starting or has just dropped"*
> from the log review.

## Scope

Backend only: `PlaybackTracker.anyActive` / `isPlaybackActive()` (`tv/PlaybackService.kt`), and the between-units
checks of long queue jobs. No route, DTO, schema or client change. The household switch and its rule
(`phase-262-…` FR-262-1, `row.deferWhilePlaying && scan.defer_while_playing`) are unchanged. This spec changes what
*"someone is watching"* means, which phase 262 kept out of its scope (its Non-goals: *"Changing what 'playing' means"*).

## What happened (DEB-5531, prod DB, 2026-11-24, a viewer's Samsung TV)

| UTC | Session event | Queue |
|---|---|---|
| 16:20:21 | `ps-24653074…` started | |
| 16:29:46 | `offline` (the TV stopped reporting while stalling) | |
| 16:30:58 | | `segments_season` claimed, correlating 36 episode pairs, until 16:32:43 |
| 16:31:30 | `ended` (stopped) | |
| 16:32:30 | `ps-abe368e8…` started; first frame only after 48.6 s, then 5 stalls, 83 s in total | |
| 16:32:43 → 16:33:50 | | three more `segments_season` claimed (the last correlating 56 pairs), then `verify_file` and `check_track_lengths` |
| 16:36:45 | ended; one more 50 s try at 16:37:40, then the viewer gave up | |

Every job had `defer_while_playing = 1`. The household switch was **off**, not on; the amendment below corrects
this. The play's `encoder_summary` reads speed median 0.78×
and minimum 0.505×, so our encoder could not keep real time while the jobs ran. `anyActive()` is true while the
tracker holds an entry, and for 120 s (`PLAYBACK_DEFER_GRACE_MS`) after the last start, heartbeat or stop. Both
windows were missed in this run. At 16:30:58 the stalled play had gone quiet long enough to be reaped or to leave the
grace, although its session was still open. At 16:32:43 a fresh start, 13 s old, was not counted.

Which of the two causes held on each claim is the first thing the build pins down from the code. Candidates: the
watchdog's reap on a silent `screen`, and a Samsung start registering with the tracker later than its session
starts. The requirements below say what must hold whichever it was.

### Amendment 2026-11-25 — neither candidate: the household switch was off (Sjúrður, DEB-5535)

The build's first step answered the question above from prod's config, its container log and a DB copy, before any
Kotlin was written. **Neither candidate let the claims through.**

- Prod's `config.toml` has `[scan] defer_while_playing = false`, and has had it since between 2026-09-27 (the
  `pre-270` backup reads `true`) and 2026-10-13 (the `pre-DEB925` backup reads `false`). The code default is `true`
  (`AppConfig.kt`), so it was switched off in Settings. FR-262-1 is `row && household && playing`, so with the switch
  off `claimNext` holds nothing, whatever the row's flag says. The review read the row flag and took the household
  half to be on.
- With the switch on, the shipped tracker would have held every claim in the window:
  - The watchdog reaped the TV's silent play at 16:29:46, after its events socket closed abruptly at 16:29:31. That
    stop runs through `PlaybackTracker.stopped`, which refreshes the 120 s grace, so the reap itself covers the claims
    to 16:31:46.
  - A new start at 16:30:18 called `started()` in the same second as its negotiation, and that play was active until
    the viewer's stop at 16:31:14. That stop's grace runs to 16:33:14.
  - The start at 16:32:30 was in the tracker 13 s before the 16:32:43 claim. The 16:33:50 `verify_file` and
    `check_track_lengths` claims fell inside the next play, which started at 16:33:16.
- Therefore **FR-1 already holds on the shipped code**: every start path calls `started()` before it answers.
  **FR-2 would have changed nothing on 11-24.** **FR-3 is the one confirmed gap:** a deferrable job that is already
  running does not yield. Of the queue's jobs, only the subtitles pre-warm stops between units today (FR-295-1).
  `segments_season`,
  `verify_file` and `check_track_lengths` run to the end.
- **Acceptance 3 cannot pass while the switch is off**, whatever is built, because with it off no job ever waits for
  playback.

Whether the switch goes back on, and which of FR-2 to FR-4 are still wanted, is the owner's decision on DEB-5535. Its
answer is written here when it comes.

### Amendment 2026-11-25 — owner: the switch goes back on, and only FR-3 is built (DEB-5535)

> Owner, 2026-11-25 (DEB-5535 card): *"Turn it on + make a running job step aside"* (recommended option).

- **The household switch goes back on.** `scan.defer_while_playing = true` is set in Settings on prod (live, no
  restart). That is an operations step, not part of the build, and it is Tróndur's.
- **FR-1 holds on the shipped code and needs no build.** **FR-2 and FR-4 are withdrawn.** FR-4 named FR-1's and FR-2's
  windows, so with FR-1 already shipped and FR-2 dropped there is nothing left for it to name.
- **FR-3 is narrowed to `segments_season`.** `verify_file` and `check_track_lengths` are one file per job (FR-261-1).
  A job of one unit has no boundary inside it, and the claim already holds such a job while a play runs. Nothing is
  built for them.
- **What a unit is in `segments_season`.** The job runs the chapter/heuristic tier over the season's episodes, then
  the intro fingerprint pass and the outro fingerprint pass. Each pass fingerprints every episode (Phase A, cached on
  disk by `FingerprintService`) and then correlates every pair (Phase B). The job may yield:
  - after each episode of the chapter/heuristic tier;
  - after each episode of a pass's Phase A;
  - after each pair of a pass's Phase B.
- **What resuming skips.**
  - A resumed job does not repeat a tier or pass that had finished.
  - In the heuristic tier it does not repeat an episode already done. This holds for a forced job too: progress travels
    in the requeued row's params, the way the subtitle retry count does (`Requeue.nextParams`).
  - Phase A re-reads its fingerprints from the cache, so that is free.
  - A Phase B cut short restarts that pass's pairs, because its candidates live only in memory. That costs at most one
    pass's correlation and writes nothing twice.
- **Yielding uses the rule the claim uses.** A job yields only where the claim would have held it: the row asks, the
  household switch is on, and something plays (`yieldsToPlayback`, FR-295-1). A yield is a requeue in place with a
  reason, as the subtitles pre-warm's is. A non-deferrable `segments_season` (a title's own *Detect segments*) never
  yields.
- **Acceptance** now reads: Acceptance 1 is withdrawn, because FR-1 needs no build and FR-2 is gone. Acceptance 2 stands
  for the heuristic tier and gains one case, a yield inside an intro pass's Phase B that resumes with the heuristic tier
  and Phase A skipped. Acceptance 3 counts its week from the later of the switch going on and the FR-3 build reaching
  prod.

## Requirements

- **FR-1 — a start counts at once.** A play counts as watching from the moment the server accepts its start (the
  playback session's `started`, or the stream/encoder request that precedes it, whichever comes first). It does not
  wait for a progress report or a first frame. This holds for every client kind, Samsung `screen` and Chromecast
  `cast` included.
- **FR-2 — a silent play still counts while its session is open.** A play whose session is open and not paused keeps
  counting as watching while it is silent: stalled, buffering, or flagged `offline`. It stops counting when the session
  ends, or 10 minutes after its last report, whichever comes first. The 10 minutes match the offline reaper's horizon
  (R372). A paused session keeps today's 120 s grace, so an evening with a paused TV does not starve background work
  (FR-178-1's reason).
- **FR-3 — a long job yields between units.** A deferrable job that is already running when a play starts stops at its
  next unit boundary and goes back to waiting, the way the two sweeps already stop between files (FR-262-1). The units
  are each episode or episode pair for `segments_season`, and each file for `verify_file` and `check_track_lengths`. It
  resumes from where it stopped and does not start the whole season again. A non-deferrable job (an operator's
  *Check now*, a title's own Refresh ▾) is unaffected.
- **FR-4 — health names it.** `GET /api/health` and the Processing view, which already say *deferred — TV playing*,
  say it during FR-1's start and FR-2's silence too. They name the device as `activePlaybackDeviceNames()` does today.

## Acceptance

1. **Unit test** (`linuxX64Test`, on `PlaybackTracker` with an injected clock):
   - a `started` with no heartbeat makes `anyActive()` true at once;
   - a play that goes silent with its session open stays active at +5 min and is inactive at +10 min 1 s;
   - a paused play is inactive at +121 s;
   - a stopped play is inactive at +121 s.
2. **Unit test** for FR-3: a `segments_season` job over 4 episodes, with playback becoming active after episode 2,
   records 2 done, returns to waiting, and on release completes episodes 3 and 4 only.
3. **Prod, read-only, a week after the deploy.** In the prod DB, no `media_job` with `defer_while_playing = 1` has a
   `started_at` inside a real viewer's playback session window, counting from session start to end and leaving out
   paused stretches longer than 120 s. DEB-5531's comment thread has the query that found the overlaps.
