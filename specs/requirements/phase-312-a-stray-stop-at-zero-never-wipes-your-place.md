# Phase 312 — A stray stop at 0 never wipes your place

> Owner, 2026-10-07, after a film on the Pixel 9 Pro: *"I just watched [a film] on Pixel 9, can you check why it
> was so slow at streaming/lagging"*. The investigation found that the film's place was lost too. Owner, 2026-10-08:
> *"yes, let's write the specs"*.

## Status

`Planned` — written 2026-10-08 (dev-authored) from a read-only investigation (Jellyfin's log and database, the backend
log, the backend database). Not dev-reviewed, not built. Backend only. Related: **310** (the writer; its review item 7
already proposes one user-data write per stop, which this phase extends); **R375** and **R343** (the other write-backs
after a stop).

## What happened

- **2026-10-07 19:32 CEST, Pixel 9 Pro (Ravilo 1.50).** A film was started at 0:00 and then skipped ahead to 18:34
  (Jellyfin restarted the transcode with `-ss 00:18:34`). It was stopped at **18:37**. Jellyfin's log, 0.9 s apart:

  ```
  17:32:33.715  User "jogvan" stopped playback of '…' at "1117014"ms ("Ravilo" "1.50")
  17:32:34.606  User "jogvan" stopped playback of '…' at "0"ms ("Ravilo" "1.50")
  ```

  Jellyfin's `UserData` for the film is now **position 0, not played, no last-played date**: the place is gone. The
  backend logged one stop (`playback stop: … at 1117014ms`) and nothing at 17:32:34, so it did not log sending the
  second stop.
- **How a 0 wipes it** (Jellyfin `SessionManager.OnPlaybackStopped`, `master`): a stop *with* a position runs
  `UpdatePlayState(item, data, positionTicks)`. A position under `MinResumePct` (5 %) saves the place as 0 and leaves
  `Played` as it was. A stop with *no* position assumes "watched" (that was 310's case). A stop at an explicit 0 is
  therefore "start again from the beginning", whatever was saved a second earlier.
- **It is not rare.** Jellyfin's logs for 2026-10-04 to 10-07 hold 489 Ravilo stops. **71** are at 0 ms, and **24** of
  those land within 5 s *after* a real stop (over 1 min) of the same item. Stue TV showed the same pattern on
  2026-10-06 at every kids' episode change (`0 · real · 0` within 70 ms). Every one of the 24 that was not finished
  lost its place.
- **Where the 0 comes from: not pinned yet.** It is not Jellyfin's idle check (that reports the last check-in
  position, or none). Every stop the backend sends goes through `JellyfinSink.stop`, `releaseSession`'s writer-less
  path, `mark(watched = true)` or `setPlayed(played = true)`. The last two send 0 on purpose but then mark the item
  played, which did not happen here. The likeliest source is a **second Jellyfin session for the same play** that
  also holds the item as *now playing* at position 0, ended right after ours (Jellyfin's `CloseIfNeededAsync` reports
  a stop with the session's own `PlayState.PositionTicks`). For example: one session from the stream URL's `DeviceId`
  and `ApiKey`, another from the backend's `/Sessions/Playing` identity, the session bridge, or R291's composed-master
  requests.

## Requirements

### FR-312-1 — Find the second stop

Before any fix, one instrumented test play on the dev stack (a phone, a film, start → seek → stop) captures:
`GET /Sessions` (every session holding the item, with `DeviceId`, `Client`, `PlayState.PositionTicks`) once a second
from start to 10 s after the stop; every Jellyfin call the backend makes for that play (method, path, identity's
`DeviceId`), logged at debug for the test; and Jellyfin's debug log for `SessionManager`. The finding (which session,
which call creates it, which call ends it) goes into this spec's build notes, and FR-312-2 removes the cause.

### FR-312-2 — One play, one Jellyfin session

Every Jellyfin request the backend makes for one play (PlaybackInfo, the stream URLs it hands out, `/Sessions/Playing*`,
the session bridge, R291's playlist fetches, 308's variant playlists) carries **the same device identity**, so Jellyfin
has one session for it. No second session may hold the item as now-playing. A request that must not count as playback
(a playlist fetch, a probe) uses a URL form or identity that Jellyfin does not turn into a playing session.

### FR-312-3 — The stop is the last word, checked after Jellyfin settles

Whatever FR-312-1 finds, the backend makes its own decision stick. After our stop has landed (310's writer), and once
more **3 s later**, it reads the item's `UserData` back. If what Jellyfin holds differs from what the stop decided
(R347: finished ⇒ played at 0; not finished ⇒ not played, at our position; R343/R375: their rules), it writes the
decision back with one `setUserData(played, positionTicks, lastPlayedDate)`. This is the same single write 310's review
proposes (item 7), plus the delayed re-check, which catches a stray stop that lands after ours. A repair is logged with
what was there and what was put back (no titles: item id only).

### FR-312-4 — A stop at 0 right after a real one is not trusted

A 0 ms stop the backend itself would send for an item it stopped at a real position under 10 s earlier is dropped,
logged as `ignored a stop at 0 for item … (stopped at … N ms ago)`. That covers `mark()`/`setPlayed()` racing a stop,
and any future path. Marking watched still works: `mark(watched = true)` marks played first, then zeroes the position.

### FR-312-5 — Repair the places already lost

A one-off repair like 310's FR-310-7, from Jellyfin's own log (it holds both stops with their times). For each 0 ms
stop within 5 s after a real stop of the same item, where the item is now at position 0 and not played, and the real
position was before the finished threshold (R347), set it back to that position, with `LastPlayedDate` at the real
stop's time. Dry run first, the owner presses Apply. Logs older than Jellyfin's retention are out of reach.

### FR-312-6 — Tests are part of the phase

1. The FR-312-3 re-check: a fake Jellyfin whose `UserData` flips to position 0 one second after our stop is
   corrected within the re-check, with exactly one write; a fake that keeps our position gets no write.
2. FR-312-4: a 0 ms stop for an item stopped 3 s earlier at 18 min is dropped; one 20 s later goes through;
   `mark(watched = true)` still ends played.
3. FR-312-2: for one play, every request in a recorded run (a fake Jellyfin recording identities) carries the same
   `DeviceId`.
4. The repair: given log lines and `UserData` rows, the dry run lists exactly the wiped, unfinished items.
5. A regression test for the case FR-312-1 finds, named after it.

## Acceptance

1. On the dev stack, play a film on the phone, seek, stop at 18 min: Jellyfin's log shows **one** stop, and the film
   resumes at 18 min on the phone, on a TV and in Jellyfin's web app.
2. Ten episode changes in a row on Stue TV: no `stopped … at "0"ms` after any real stop.
3. The repair puts the 2026-10-07 film back at 18:37.

## Open questions (dev)

1. FR-312-1's finding decides how big FR-312-2 is; if the second session comes from the stream URL's `ApiKey`,
   check whether Jellyfin 12.1 has a request form that streams without creating a session.
