# Phase 295 — A subtitle pre-warm waits for playback instead of spinning

> Found live on production, 2026-10-02 (backend v1.48-33-g1ae6c1c4): 76 % CPU, about 500 log lines a minute.

## Status

`✓ Built` 2026-10-02 (see Build notes). Written 2026-10-02 (dev-authored), against `main` `145fc0f9`. Number verified free (admin specs top at
294). Not dev-reviewed.

**Amends** phase 262 (FR-262-1, *one switch, read live*): the pre-warm walk was the one place that still stopped on
playback alone. And phase 213 (FR-213-3, the per-stream playback check; FR-213-2's in-place requeue).

## What happens

1. `MediaJobQueue.kt:1173` logs `job … (prewarm_subtitles) re-queued — a TV started playing (0 already warmed)` about
   eight times a second for as long as anything plays, here since 23:24 UTC.
2. The household has *Defer background work while a TV is watching* **off** (`scan.defer_while_playing = false`).
   Phase 262 made that switch decide, at claim time, whether a queued job waits for playback: off, so `claimNext`
   takes the `prewarm_subtitles` row while something plays. `/api/health` agrees
   (`subtitles_deferred_by_playback: false`).
3. But `PipelineStepOps.prewarmSubtitles` checks `isPlaybackActive()` **alone** between streams (213's FR-213-3),
   before warming the first one. Playback is on, so it returns `Deferred(0)`.
4. `runClaimed` turns that into an in-place requeue: the same row, back at the head of its queue, claimable at once.
   The worker claims it again on its next pass (no sleep: it found work), the walk asks Jellyfin for the item's
   stream list again, and yields again. The spin rate is the stream-list lookup's round trip.
5. "A TV started playing" can be true while Jellyfin's `/Sessions` shows nothing: `isPlaybackActive()` is
   jellystructure's own tracker, which also covers 120 s after the last heartbeat or stop (178), and any session it
   tracks (a film, a song, a cast). That is by design and unchanged here.

## Requirements

**FR-295-1 — One rule, at claim time and between streams.** The walk stops for playback exactly when the claim would
have held the row: the row asked to defer, **and** the household switch is on, **and** something plays
(`MediaJobQueue.waitsForPlayback`, used by both). With the switch off, the pre-warm carries on through playback, as
262 says every queue job does. The walk takes this rule as a parameter (`yieldToPlayback`); its default stays
`isPlaybackActive()` for any other caller.

**FR-295-2 — A job that stops for playback waits.** The in-place requeue also *parks* the row
(`PlaybackParking`): the claim passes over it while something still plays, for up to 30 s. It is claimable the moment
playback ends, or after 30 s if playback goes on, when the walk decides afresh. The row keeps its place in the queue
(213's FIFO rule); the worker sleeps rather than spins. This is a safety net: with FR-295-1 the claim already holds a
deferring row while the switch is on.

**FR-295-3 — One line per deferral.** The requeue line is logged once per stop (*re-queued — waiting for playback to
end (n already warmed)*), and the claim logs once when a parked row is taken again (*resumes — playback ended or its
wait ran out*). No line per pass.

**FR-295-4 — Nothing else changes.** Health's `subtitles_deferred_by_playback` (262's FR-262-4), the 3-attempt
Jellyfin-busy back-off (213's FR-213-4), the producers, the config, and what "playing" means.

## Acceptance

1. Unit (`PlaybackParkingTest`): the walk's rule equals the claim's for all eight combinations, and is false with the
   switch off while playing; a parked row is held while playing, released when playback ends, released after 30 s if
   it goes on, logged as resumed once; a row that yields whenever it runs is requeued once per 30 s, not once per
   pass.
2. After deploy, with the switch off and a TV playing: the subtitles queue runs its pre-warm (Jobs view), and the log
   has no `re-queued` line for it.
3. With the switch on and a TV playing: one `re-queued — waiting for playback to end` line per job, then nothing
   until playback ends, then one `resumes` line.
4. CPU back to idle levels while a TV plays.

## Build notes (2026-10-02)

**Built.** `MediaJobQueue.waitsForPlayback(rowDefers, householdDefers, playing)` is the one rule; `deferNow()` (the
claim) and the new `yieldsToPlayback(row)` (passed to `PipelineStepOps.prewarmSubtitles` as `yieldToPlayback`) both
use it. `PlaybackParking` (`media/PlaybackParking.kt`, 30 s back-off, stale entries pruned after an hour) is filled by
`runClaimed`'s in-place requeue and read by `claimNext`, which skips a lane whose next row is parked while playback
goes on and releases the row (one `resumes` line) when it claims it again. The requeue reason now reads *waiting for
playback to end (n already warmed)*. `claimNext` became `suspend` (it logs); it is only called under `claimMutex`.

**Root cause, confirmed** against production's `config.toml` (read only): `scan.defer_while_playing = false`. The
claim (262) took the row; the walk (213) yielded on `isPlaybackActive()` alone before the first stream; the in-place
requeue put it straight back; the worker reclaimed it with no sleep because it had found work. The walk's check was
the one deferral 262 did not route through the household switch.

**What changes for the household:** with the switch off, the pre-warm now runs while a TV plays, as every other queue
job already does since 262. Turning the switch on restores 213's behaviour (the claim holds the row; a running walk
stops between streams and waits).

**Not changed:** why `isPlaybackActive()` was true while Jellyfin's `/Sessions` showed nothing. It is jellystructure's
own tracker (178's 120 s grace after the last heartbeat or stop, plus any tracked film, song or cast session);
`GET /api/playback/active` names the devices it counts. Worth a look on production if it stays true for hours.

**Verified:** `linuxX64Test` green, including `PlaybackParkingTest` (7 tests: the walk's rule equals the claim's for
all 8 combinations; hold while playing, release on playback end, release after 30 s, resume logged once, never-held,
one requeue per 30 s over two minutes of playback instead of one per pass).

**Owed (deploy):** acceptance 2–4 on production.
