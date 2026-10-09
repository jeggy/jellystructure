# Phase 312 — A stray stop at 0 never wipes your place

> Owner, 2026-10-07, after a film on the Pixel 9 Pro: *"I just watched [a film] on Pixel 9, can you check why it
> was so slow at streaming/lagging"*. The investigation found that the film's place was lost too. Owner, 2026-10-08:
> *"yes, let's write the specs"*.

## Status

`⚠ Partial` — **built 2026-10-08 with 310 (`5966a6b2`), deployed to the dev stack, live-verified on Stue TV**; the repair
waits for the owner's *Put them back*. Dev-reviewed 2026-10-08; written 2026-10-08 (dev-authored). Backend only. See
*Build notes*.

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

## Build notes (2026-10-08)

**Built** (`5966a6b2`, with 310):
- **FR-312-1** — every stop the backend sends is logged with its reason when queued and when it lands
  (`stop write: item=… at=…ms reason=user|watchdog|abandoned-resend|… queued|landed (312)`); `mark`/`setPlayed`'s direct
  stops at 0 log too; a superseded start's release logs *encodes only, no stop*.
- **The source, found from the logs:** of the 24 *0 ms after a real stop* cases, every one that could still be matched to an
  item was a **finished** episode or film. The 0 came from R347's own tick: `mark(watched = true)` ran in the background
  right after the stop and sent Jellyfin a stop at 0 before marking it watched — harmless for a finished item, and gone
  now that the tick is folded into 310's single user-data write. The 2026-10-07 film's own 0 could not be matched any
  more: its Jellyfin item was re-imported that evening and the old id no longer exists.
- **Review item 4** — the silent `releaseSession` callers no longer send a stop: a superseded start, music start or
  restream only releases the old encode (`releaseEncodesOnly`, skipped when the play session is the same); an abandoned
  start re-sends **the viewer's own stop position**, which the tracker now keeps with its pending stop
  (`StartResult.stoppedAtMs`), never its own start position.
- **FR-312-3** — 3 s after the stop's user-data write the backend reads `UserData` back and writes it again if it
  drifted (played flag, or place by > 2 s); skipped while the item is playing anywhere.
- **FR-312-2** — not needed: every call already carries one `forDevice` identity (review item 3).
- **FR-312-4** — replaced by review item 4's rule (no window to tune).
- **FR-312-5** — in 310's repair (the candidates file's `312` rows). Jellyfin keeps three days of logs, so the 10-04 and
  10-05 cases were already gone; of the rest, all matched cases were finished plays, so the dry run lists none from 312.

**Tests** (FR-312-6): `PlaybackStopIntegrationTest` — a stop lands once at its place with one user-data write; an
abandoned start re-sends the viewer's own stop (no stop at 0); a superseded start sends Jellyfin no stop, only releases
the old encode; a stray stop at 0 after ours is undone by the read-back. `PlaybackRepairTest` covers the repair.

**Live (Stue TV, debug app, 2026-10-08 12:22 CEST):** a 4K film started from 0:00, seeked forward to 15 min, stopped
with Back. Jellyfin's log: one *started*, **one** *stopped … at "921779"ms*, no stop at 0; its `UserData` held the place
(unwatched at 921 779 ms); the read-back found nothing to correct. The test play was undone afterwards.

### Found live (2026-10-08 evening, R381's test plays)

Every finished episode still sends Jellyfin **two stops at 0 ms** around the real stop, logged as
`stop write: … at=0ms reason=mark-watched direct (312)`: the app marks the episode watched at the credits (two calls
in `PlayerScreen`, the advance path and the stop path), and the backend's `mark(watched = true)` still calls
`stopPlaybackSession(0)` (R185's zeroing) before `markPlayed`. For a finished item the result is right (played,
position 0) and the stop's own user-data write lands after, but it is the 0 ms stop this phase set out to remove, and
it would wipe a place if the app's "finished" and the server's R347 rule ever disagreed. Lean: `mark(watched = true)`
writes the user data (`setUserData(played = true, position 0)`) instead of sending a stop, and the app sends one
mark per episode. **Fixed 2026-10-08 (late):** `mark(watched = true)` and `setPlayed(played = true)` mark played, then
zero the position with a user-data write (`zeroPosition` → `setUserData(position 0)`); no `/Sessions/Playing/Stopped` at 0
is sent any more. `PlayerStore` keeps the items it has marked (`markedWatched`), so advancing at the credits and the stop
that follows send one mark. Test: `PlaybackStopIntegrationTest` *marking an item watched sends Jellyfin no stop at 0*.

## Live results after the integration deploy (2026-10-09, v1.50-105 → v1.50-106)

**Found live and fixed:** the FR-312-3 read-back undid a viewer's own *mark watched* pressed within its 3 s delay — the
stop's place (11:40) was written back over the viewer's 0:00 (`read back played=true at 0ms, expected played=null at
700000ms — writing it again`). The read-back now stands down when the viewer marked or unmarked the item after the stop
landed (`readBackStillOurs`, `StopUserDataTest`); `mark` and `setPlayed` (the routes and R343's clear) note the viewer's
word. Every watchdog stop in the live test logged its reason (`reason=watchdog`), and no stop at 0 was sent by a stop.
