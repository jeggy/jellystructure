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

## Dev review (2026-10-08, against `main` `4222ac4c`)

Read against `PlaybackService` (`stopPlayback`, `releaseSession` and its seven callers, `mark`, `setPlayed`,
`JellyfinSink`), `PlaybackWriter`, `JellyfinSessionBridge`, `TvRoutes` (`/tv/playback/stop`), `JellyfinClient`
(`forDevice`, the auth header, every `/Sessions/Playing*` call), the Ravilo clients (`TvApiClient`, `ravilo-ui`,
`ravilo-cast`), and Jellyfin `master`: `SessionManager.cs` (`OnPlaybackStopped`, `CheckForIdlePlayback`,
`CloseIfNeededAsync`, `ReportSessionEnded`, `OnSessionEnded`, `Logout`), `PlaystateController.cs` and
`TranscodeManager.cs`. The diagnosis of the *effect* holds. The *source* is narrower than the spec says, and FR-312-2
probably fixes nothing. Seven items, two for the owner.

1. **Jellyfin never makes up a stop at 0 on its own.** Its stops come from three places only:
   - the API (`POST /Sessions/Playing/Stopped`, `DELETE /PlayingItems/{id}`);
   - the idle check, which reports `LastPlaybackCheckInPositionTicks`, needs a session idle for over 5 min, and
     reads *"unknown"* when nothing was reported;
   - `OnPlaybackStart` stopping a *different* previous item.

   Ending a session (`CloseIfNeededAsync` → `OnSessionEnded`, `Logout`, `ReportSessionEnded`) reports **no** stop, and
   the transcode manager never calls `OnPlaybackStopped`. So the spec's lead is wrong: *a second session ending
   reports its `PlayState.PositionTicks`* does not happen. The 0.9 s-later stop is an explicit API call from a client.
2. **The only clients are ours, and the Ravilo apps never call Jellyfin's session API.** Nothing in `ravilo-ui`,
   `ravilo-cast` or `shared` calls `/Sessions/Playing*`; every stop goes through `/api/tv/playback/stop` →
   `stopPlayback`, which always logs `playback stop:`, and only one was logged. Of the backend's four Jellyfin stop
   calls:
   - `mark`/`setPlayed` send 0 but then mark played, which didn't happen;
   - `releaseSession`'s writer-less path is dead in production;
   - so it is `JellyfinSink.stop`, whose STOPs come from `releaseSession`.

   `releaseSession` has **seven callers**, five of them *silent*: start-supersede (`:724`), abandoned start (`:746`),
   the music start (`:802`, `:805`) and the restream pair (`:1458/1464`, `:1522/1527`). Each queues a STOP at
   `old.positionMs` or `startPositionMs`, which is 0 for a play that began at 0:00. **Most likely source: one of those
   silent releases for the same (device, item), landing just after the real stop.** For example, a start or restream
   still negotiating when the viewer pressed Back (FR-180-3's `stopAlreadyArrived` path releases at
   `startPositionMs` = 0), or a superseded tracker entry. The writer keys pending writes by (device, item), so a STOP
   queued after the first one landed is a fresh write that lands 1 s later. The 2026-10-07 film started at 0:00 and
   was seeked, which fits. So does Stue TV's `0 · real · 0`, where the leading 0 precedes the backend's own stop log
   line.
3. **FR-312-1: instrument our side first, the cheapest proof.** Every STOP gets a permanent INFO line naming its
   caller (`stop write: item=… at=…ms reason=user|supersede|abandoned|restream|music-start|mark`), logged when queued
   and when it lands. One test play then names the path. Add to the test: start at 0, press Back during the cold
   start, seek, change the audio track (restream). `GET /Sessions` sampling is still useful but secondary. FR-312-2
   (one session per play) is **probably moot**: the stream URLs and every `/Sessions/Playing*` call already carry the
   same `forDevice` identity (`ravilo-<device>-<user>`).
4. **The real fix belongs in `releaseSession`'s callers:** a release for a play the viewer has already stopped must
   not send a stop at all. It only needs to free encodes (`stopActiveEncoding`, 180). Make a "release encodes only"
   variant for supersede/abandon/restream when the tracker shows a stop already landed for that key, or when the
   superseded entry is the same play. That replaces FR-312-4's 10 s heuristic with a rule that has no window to tune.
5. **FR-312-3 (read back, write our decision) stays as the net, with one guard.** Skip the correction when a new play
   of that item has started on any device since the stop (`playbackTracker`), or the 3 s re-check could overwrite a
   resume elsewhere. It must also share 310 review item 7's single `setUserData` write, so R343, R347 and R375 don't
   race it. **310 is a prerequisite:** with the writer dead, neither the stop nor the re-check lands.
6. **FR-312-5's evidence needs filtering.** Of the 71 zero-ms stops, some are legitimate: R343 shuffles report 0 by
   design (`reported 0ms: shuffled`), and start-over clears. Restrict the repair to a 0 ms stop within 5 s **after** a
   real stop of the same item (the 24), and drop items R343/R375 deliberately reset.
7. **Tests:** add to FR-312-6 a `PlaybackService` test per silent caller (abandoned start, supersede, restream) that,
   after a real stop has landed, asserts **no** second STOP reaches the sink; that is the regression test FR-312-6
   item 5 asks for.

**For the owner:**
- **Q1 — How to find the source:** (a) the permanent stop log line plus one dev-stack test play on the Pixel **(lean)**;
  (b) also turn on Jellyfin request logging for that test; (c) skip finding it and rely on the read-back net only.
- **Q2 — Repair scope:** (a) the 24 cases from Jellyfin's logs, dry run then Apply **(lean)**; (b) only the 2026-10-07
  film; (c) no repair.

## Decided by the owner (2026-10-08, after the streaming re-review)

1. **Finding the source (Q1): a permanent log line naming the caller of every stop the backend sends, then one test
   play on the dev stack** (start, seek, stop). The review's lead is one of `releaseSession`'s silent callers queueing a
   stop at the start position; the fix is a "release the encodes only" path for a play that has already stopped.
2. **Repair (Q2): all 24 cases from Jellyfin's log**, dry run first, the owner presses Apply.
3. Comes right after 310 (it needs the working writer), in step 1 of the order. See `specs/research-reports/ravilo-streaming-plan-2026-10-08.md` for the whole order.
