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
| 16:36:45 | ended; one more 50 s try at 16:37:40, then she gave up | |

Every job had `defer_while_playing = 1` and the switch was on. The play's `encoder_summary` reads speed median 0.78×
and minimum 0.505×, so our encoder could not keep real time while the jobs ran. `anyActive()` is true while the
tracker holds an entry, and for 120 s (`PLAYBACK_DEFER_GRACE_MS`) after the last start, heartbeat or stop. Both
windows were missed in this run. At 16:30:58 the stalled play had gone quiet long enough to be reaped or to leave the
grace, although its session was still open. At 16:32:43 a fresh start, 13 s old, was not counted.

Which of the two causes held on each claim is the first thing the build pins down from the code. Candidates: the
watchdog's reap on a silent `screen`, and a Samsung start registering with the tracker later than its session
starts. The requirements below say what must hold whichever it was.

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
