# Phase 306 — A progress report keeps a direct play out of the cast ceiling

> Owner, 2026-10-04: *"yes"* — to writing this up on its own rather than waiting for R368, after the R368 dev review
> found it.

## Status

`Planned` — written 2026-10-04 (dev-authored) from the R368 dev review's shipped bug 3; checked against `main`
`33c8bcf5`. Backend only (`PlaybackService.kt`), no wire, DTO, config or client change. Restores **286 FR-286-8**
(the session ceiling counts a speaker only while it transcodes) after the first progress report, which it has
never held past.

## What happens today

`PlaybackTracker.started(...)` records whether the playback is a direct play (`TrackedPlayback.directPlay`,
`PlaybackService.kt:168-175`), and `activeDirectDeviceIds()` (`:269-272`) leaves those devices out of the cast
ceiling (`CastService.checkCeiling`, called at `:521` and `:695`). But `heartbeat(...)` (`:197-209`) rebuilds the
entry on every progress report:

```kotlin
active[key] = TrackedPlayback(device, jellyfinId, positionMs, clock(), existingPlaySessionId)
```

It carries the Jellyfin play-session id over (phase 180) but not `directPlay`, which falls back to its default
`false`. So from the first progress report (about 10 s in) every direct play counts as converting:

- two speakers playing MP3s straight from the server use up the default ceiling of 2, and a third cast (a film, or
  another speaker) gets phase 182's 503 *server busy*;
- the same applies to a film direct-played on a Chromecast.

`activeDirectDeviceIds()` has no other caller, so only the ceiling is affected.

Nothing has been seen in the household yet only because it rarely has three casts at once.

## Requirements

### FR-306-1 — A progress report keeps what the start recorded
`heartbeat` keeps every field of the existing entry and changes only `positionMs` and `heartbeatMs`, through one
method on `TrackedPlayback` (`withProgress(positionMs, heartbeatMs)`). That covers `directPlay` and `jellyfinPlaySessionId`
today, and any field added later, so the same slip can't happen to the next field.

### FR-306-2 — A progress report with no entry stays conservative
When there is no entry (after a backend restart, or a progress report that arrives before its start), the
heartbeat creates one as today, with `directPlay = false`: an unknown playback counts against the ceiling. R368's
restart restore (the stored session row) is what fixes this case properly; this phase doesn't try to guess.

### FR-306-3 — Nothing else moves
No change to how a start decides `directPlay`, to the ceiling's number, to `checkCeiling`, or to the stop grace
window (`stoppedUntilMs` still wins over a late progress report).

## Acceptance

1. A direct-played song on a speaker, 30 s in (three progress reports), is still left out of the ceiling:
   `activeDirectDeviceIds()` contains that speaker.
2. With the ceiling at 2 and two speakers direct-playing, a third cast starts (no 503).
3. A transcoding playback still counts, before and after progress reports.
4. After a backend restart, a progress report for an unknown playback counts against the ceiling until its next
   start (FR-306-2).
5. The tests below are green.

## Tests

In `src/linuxX64Test/kotlin/dev/jellystructure/tv/PlaybackTrackerTest.kt`, next to the existing tracker tests and in
their style:
- `heartbeat keeps directPlay from the start`: `started(directPlay = true)`, then `heartbeat` → the entry still has
  `directPlay == true` and the device is in `activeDirectDeviceIds()`.
- `heartbeat keeps the Jellyfin play session id`: the phase 180 behaviour, now through `withProgress` (a regression guard).
- `heartbeat updates only position and time`: every other field equals the start's.
- `heartbeat without a start counts as converting`: no `started`, then `heartbeat` → `directPlay == false`, the
  device is not in `activeDirectDeviceIds()`.
- `a device with one direct and one converting playback is not direct`: today's `all { it.directPlay }` rule,
  unchanged.

In `CastServiceTest.kt`, one case: with ceiling 2 and two `cast` devices whose playbacks are direct and have each
had a progress report, `checkCeiling` lets a third device start.

Live check (optional, on the Gæsteværelse speaker only): with the ceiling set to 1, cast an MP3 album there, wait
30 s, then cast a film to another receiver: it starts (today it gets the 503).

## Dev notes

`TrackedPlayback` (`PlaybackService.kt:88`) is a plain class, not a data class. Add
`fun withProgress(positionMs: Long, heartbeatMs: Long) = TrackedPlayback(device, jellyfinId, positionMs, heartbeatMs, jellyfinPlaySessionId, directPlay)`
beside its fields, so a new field is added in one place. Don't turn it into a data class: that would change
`equals` for the maps and the `superseded` handling. At `:204`:
`active[key] = active[key]?.withProgress(positionMs, clock()) ?: TrackedPlayback(device, jellyfinId, positionMs, clock())`.
The `existingPlaySessionId` local goes. Note that the ceiling check with ceiling 1 makes the live check easy.
