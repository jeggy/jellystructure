# Phase 310 — Watch progress always reaches Jellyfin, and nothing is marked watched by accident

> Owner, 2026-10-07: *"I just watched [an episode] on Stue TV, but didn't finish, but looking now, it shows that I finished
> the episode. Something is clearly broken here, please find out what."* — and then: *"Let's write a spec to fix it and
> have it as a requirement that you should write tests as well, so this type of bug will not come again."*

## Status

`Planned` — **Dev-reviewed 2026-10-07** (see *Dev review*); written 2026-10-07 (dev-authored) from a read-only investigation on the dev stack (v1.50-30-g0df9d193,
container up since 2026-10-06 ~07:56 CEST). Not dev-reviewed, not built. Backend only (`PlaybackWriter`,
`PlaybackService`, the timeout helpers, health, Dashboard).

## What happened (2026-10-06, all times UTC; read-only from logs and both databases)

1. **15:34:40 — the playback writer stopped.** Phase 219's `PlaybackWriter` is the one coroutine that sends every
   progress report and every stop to Jellyfin, one at a time. Stue TV's last progress to land was ~5:57 into the
   episode it started at 15:28:42; nothing from any device has landed since. `/api/health` at 22:20 the next night:
   `playback_writer {"queued": 15, "retrying": 0, "landed": 1441, …, "last_failure": null}` — **unchanged across one
   minute**. No error, no warning, no "abandoned" line: it ended silently.
2. **Why it ended (most likely, by elimination):** `PlaybackWriter.attempt()` does
   `if (e is CancellationException) throw e` (210's rule, *"never swallow the writer's own cancellation"*). But a
   `TimeoutCancellationException` from an **inner** timeout is also a `CancellationException`. Rethrown out of
   `drain()`, it ends the launched coroutine as a normal *cancellation* — no log, no crash, no restart. At 15:34:55–57,
   the same seconds, the Continue Watching loop logged `timed out after 6000 ms` and then
   `Continue Watching refresh failed: Timed out waiting for 6000 ms`: the **outer `withTimeoutOrNull` leaked its own
   `TimeoutCancellationException`** instead of returning null (Jellyfin was slow just then). The writer's progress path
   runs `cachedTokenCheck`'s `withTimeoutOrNull(5000) { checkToken(…) }` against the same Jellyfin at the same moment
   (a token check is due every 5 min). Every other way out was checked and is bounded: HTTP calls (120 s request
   timeout; Ktor 3.6's `HttpRequestTimeoutException` is an `IOException`), outbound permits (timed acquire →
   `GateTimeoutException`), token checks (`withTimeoutOrNull`), the after-stop hooks (no locks, notifications are
   fire-and-forget). A hang would have unstuck within minutes; a dead loop never does.
3. **Jellyfin then marked things watched on its own.** With no progress arriving, Jellyfin's idle check stops a
   playing session after ~5 min with **no position** (`stopped playback … at "unknown"ms`), and Jellyfin's
   `UserDataManager` treats a stop with no position as *"the client couldn't report it — assume it was watched"*:
   `Played = true`, `PlaybackPositionTicks = 0`, `PlayCount++`. Stops that did come from our own `/Sessions/Playing`
   switches carried position `0`.
4. **The viewer's real stop never landed.** A 2 h 42 min episode on Stue TV: started 22:18:51 CEST, paused
   22:54–23:38, stopped at **45:52 (28 %)** — the backend knew all of it (`playback_session ps-b0f31c181f0cb488`,
   `position_ms 2752859`), Jellyfin has `Played 1, position 0, PlayCount 3`.
5. **Blast radius:** every Ravilo play by every user since 15:34. Jellyfin's `UserData` shows **13 video items marked
   played at position 0** since then (11 jogvan, 1 meidam, plus music), some of them genuinely finished (kids' shuffled
   episodes), most not. The backend's `playback_session` rows hold the true last position of each.

## Requirements

### FR-310-1 — Only the writer's own cancellation stops the writer

`PlaybackWriter.attempt()` rethrows a `CancellationException` **only when the writer's own job is being cancelled**
(`currentCoroutineContext().job.isCancelled` / `!isActive`). Any other `CancellationException` — a leaked
`TimeoutCancellationException`, a child scope's cancellation — is a **failure of that write**: logged with its class
and message (FR-219-1's wording), retried with the normal backoff, and the drain loop goes on to the next write.

### FR-310-2 — No write can hold the line

- Each attempt runs under its own deadline (**30 s**). Past it the attempt is a failure, retried like any other.
- Everything a stop starts **after it has landed** — releasing the encodes (180/308), R375's last-played write-back,
  R343's unwatched write-back, the Home refresh (R248) — runs **off the writer's line**, on the service's background
  scope, each with its own deadline. A slow follow-up never delays the next device's progress.
- One slow device never delays another: writes are taken oldest first across devices, as today, but a write that
  has failed 3 times in a row is moved behind the others until its next retry is due (it already is — keep it).

### FR-310-3 — The writer is supervised, and its health is visible

- The drain loop runs under a **supervisor** that restarts it if it ends for any reason other than shutdown, logs
  `ERROR playback writer stopped (<class>: <message>) — restarted`, and counts restarts.
- `/api/health`'s `playback_writer` gains `alive` (bool), `restarts`, and `oldest_waiting_s` (age of the oldest
  queued write).
- The Dashboard (285's grammar, *critical* — it stops watch history) shows **"Watch progress isn't reaching
  Jellyfin"** when `alive` is false or `oldest_waiting_s` > 120, with the count of waiting writes and the last
  failure. Zero is silence.

### FR-310-4 — A timeout never leaks out of a "…OrNull"

One shared helper, `boundedOrNull(ms) { … }`, returns null on its own timeout **and** on a
`TimeoutCancellationException` that escapes from inside it (the leak seen at 15:34:57 around
`coroutineScope { async … }`), and rethrows only a cancellation of the caller's own job. Every `withTimeoutOrNull`
on the playback, token, Continue Watching and playstate paths moves to it; the rest of the backend follows (a
`scripts/check-timeouts.sh` lists the remaining raw `withTimeoutOrNull` sites so they can't creep back).

### FR-310-5 — Every long-lived loop survives a leaked timeout

The backend's forever-loops — the playback writer, the Continue Watching refresh, the playstate refresh, the stop
watchdog (110), the session reaper (R372), the job-queue workers (213), the publish queue (307), the realtime ingest
— run on one helper, `supervisedLoop(name) { … }`, that applies FR-310-1's rule (only its own cancellation ends it),
logs and backs off on any other throwable, and restarts the body. `scripts/check-loops.sh` fails on a
`while (true)` inside a `launch` that does not go through it.

### FR-310-6 — Jellyfin is never left to guess "watched"

- On every play start the backend sends Jellyfin **one progress report at once** (the start position), so Jellyfin's
  session has a position from its first second and an idle-stop can never be "unknown".
- **The stop is the last word.** After our stop lands, if what the stop decided (R347: finished or not, at what
  position) differs from Jellyfin's `UserData` for that item (`Played`, position), the backend writes its own decision
  back — the R343 write-back, generalised to every stop. It never marks played what our rule says is unfinished.
- When Jellyfin idle-stops one of our sessions (its session bridge reports the session gone while ours is still
  playing), the backend re-registers the play with its current position instead of letting it lapse.

### FR-310-7 — Repair what this one broke

A one-off repair, run once after the build is deployed: for every Ravilo play whose `playback_session` ended
(or was idle-stopped) between **2026-10-06 15:34 UTC** and the deploy, compare its last known position with Jellyfin's
`UserData`; where Jellyfin says `Played` at position 0 and our position is before the finished threshold (R347: the
credits marker, else 90 %), set it back to **unplayed at our position**, and restore `LastPlayedDate` (R375). A
dry-run first lists the items it would change (counts in the log; titles only in the admin, never in the repo); the
owner presses Apply. Truly finished plays are left alone.

### FR-310-8 — Tests are part of the phase (owner requirement)

The phase is not done until these exist and pass in CI (`linuxX64Test`):

1. **Writer — a leaked timeout.** A sink that throws `TimeoutCancellationException` (not the writer's) on the first
   call: the write is retried and lands; a second device's write lands in between; `alive` stays true.
2. **Writer — a hang.** A sink that never returns for one device: the 30 s deadline fails that attempt (virtual
   time), the other device's writes land meanwhile, and the stuck write retries.
3. **Writer — a slow follow-up.** A Home-refresh hook that never returns: the stop counts as landed and the next
   write lands at once.
4. **Writer — real cancellation still stops it.** Cancelling the writer's scope ends it, no restart.
5. **Supervisor.** A drain body that throws any `Throwable` is restarted, logged at ERROR, `restarts` counted.
6. **`boundedOrNull`.** Reproduces the leak shape (`coroutineScope { async { … } }` timing out under a nested
   timeout) and returns null; a cancelled caller still gets its `CancellationException`.
7. **`supervisedLoop`.** Each loop listed in FR-310-5 survives a body that throws `TimeoutCancellationException` once,
   and keeps ticking.
8. **No "unknown" stop.** With a fake Jellyfin, a play start sends a progress report with the start position before
   any client tick; an idle-stopped session is re-registered with its position.
9. **The stop is the last word.** A fake Jellyfin whose stop handler marks the item played: an unfinished stop
   writes back *unplayed at the position*; a finished one leaves it played.
10. **Repair.** Given sessions and `UserData` rows, the dry run lists exactly the wrongly-marked items and Apply
    changes only those.
11. **Health and Dashboard.** `oldest_waiting_s` > 120 or `alive = false` produces the critical row; a healthy writer
    produces none.
12. **Guards.** `scripts/check-loops.sh` and `scripts/check-timeouts.sh` run in CI next to `check-phases.sh`.

## Non-goals

- Replacing Jellyfin as the store of watch history.
- Changing what counts as finished (R347 stands).

## Acceptance

1. With the build deployed, a play on Stue TV stopped at 28 % reads *in progress at that position* in Ravilo and in
   Jellyfin, every time.
2. Killing Jellyfin's responses for 2 minutes mid-play (dev stack) leaves the writer alive; when Jellyfin answers
   again every queued write lands within a minute, and no item is marked watched.
3. `/api/health` shows `alive: true`, `oldest_waiting_s` under 15 during normal play.
4. The repair puts the owner's 2 h 42 min episode back at 45:52, unwatched, and leaves the finished kids' episodes watched.

## Open questions (dev)

1. Confirm the leak mechanism on the dev stack (a Jellyfin that stalls 6 s on `/Users/{id}`) — the fix holds either
   way, since FR-310-1/-3 make the writer survive any cause.
2. Whether Jellyfin's session bridge tells us reliably that Jellyfin idle-stopped a session (FR-310-6's third
   point); if not, the start-time progress report plus the writer's health make it rare enough.

## Immediate workaround (until the fix ships)

A backend restart revives the writer (the 15 queued writes are stale and would be dropped). It needs the owner's go
(dev-compose deploys/restarts need approval), and it does not undo what Jellyfin already marked.

## Dev review (2026-10-07, against `main` `23600c28`)

Read against `PlaybackWriter.kt` (and `PlaybackWriterTest.kt`), `PlaybackService` (`JellyfinSink`, `reportProgress`,
`stopPlayback`, `cachedTokenCheck`, `startPlaybackSession`'s body), `HomeFeedService` (the Continue Watching build and
loop), `PlaystateCache`, `OutboundHttp`, `JellyfinClient`, `Main.kt` (`rootScope`), every `while (true)` in
`src/linuxX64Main`, `shared/…/PlaybackFinish.kt`; kotlinx.coroutines **1.11.0** `Timeout.kt`; Ktor **3.6.0**'s Curl
engine (`CurlProcessor.kt`, `CurlMultiApiHandler.kt`); Jellyfin **12.1** (`SessionManager.cs`, `SessionInfo.cs`,
`PlaybackStartInfo`/`PlaybackProgressInfo`); the container log (it survives `docker restart`) and Jellyfin's logs and
`system.xml`. The spec's diagnosis holds and the root cause is now found: it is not in our timeout code at all. Ten
items, four for the owner.

1. **Root cause: Ktor's Curl engine hands one request's cancellation to another request.** When a call's context is
   cancelled, `CurlProcessor.handleSendRequest` queues `cancelRequest(easyHandle, cause)` on `curlScope`, and
   `CurlMultiApiHandler` processes it later as `removeEasyHandle(easyHandle, cause)` →
   `activeHandles.remove(easyHandle)…responseCompletable.completeExceptionally(cause)`. `activeHandles` is keyed by
   the **easy-handle pointer**. If the cancelled call has already finished and its handle was freed, and a new request
   was given a handle at the same address before the queued cancellation runs, the **new** request fails with the
   **old** cause (a classic ABA). The cause here is Continue Watching's own `TimeoutCancellationException`
   (*"Timed out waiting for 6000 ms"* — `CONTINUE_TIMEOUT_MS` is the only 6000 ms timer in the backend). Evidence
   from the logs, every time 1–37 s after a Continue Watching build timed out:
   - 2026-10-06 15:34:57 — the next user's Continue Watching build (its own `withTimeoutOrNull` rethrows it, because
     `e.coroutine !== coroutine`: 1.11's rule for a timeout that isn't its own) **and the writer's progress call**;
   - 2026-10-06 17:31:00 — `scan_music failed: Timed out waiting for 6000 ms` (no timer of its own);
   - 2026-10-07 06:16:39 and 06:16:59 — `Playstate refresh failed: Timed out waiting for 6000 ms` (its timers are
     5 s and 20 s).
   The writer survived 06:16 because nothing was in flight; positions land again from 12:30 today.

2. **Why it was silent (FR-310-1 holds).** `attempt()` rethrows any `CancellationException`; out of `drain()` it ends
   the `launch` as a *cancellation*. `rootScope` has a `SupervisorJob` and a `CoroutineExceptionHandler`, but a
   handler is never called for a `CancellationException`, so nothing was logged. Implement FR-310-1 as
   `if (e is CancellationException && !currentCoroutineContext().isActive) throw e`; anything else is a failed write.

3. **FR-310-4 belongs at the HTTP boundary, not only around `withTimeoutOrNull`.** One choke point carries every
   outbound call: `OutboundHttp.withPermit`. There, a `CancellationException` while the caller's own coroutine is
   still active is **foreign**: convert it to an `IOException` (`ForeignCancellation`, logged once with its message),
   and let FR-194-6's idempotent retry run once. That repairs every caller at once (Continue Watching, playstate, the
   pipeline, the writer), and the many correct `if (e is CancellationException) throw e` sites (`JellyfinClient.kt:450,
   492, 514, 1678`, the music clients) become right again. `boundedOrNull` stays as a second guard on the
   `withTimeoutOrNull` sites. **For the owner:** report the Curl engine bug upstream to Ktor (no household details,
   just the pointer-keyed cancel). Lean: yes.

4. **FR-310-2 holds, with two notes.** The 30 s deadline must be a constructor parameter (as `baseBackoffMs` is) so
   tests can shrink it. The after-stop work must stay **in its current order** inside one background job
   (`releaseEncodes` → R375 restore → R343 write-back → `afterStartOverWriteBack`/`onStopLanded`); only the job moves
   off the writer's line, not each step.

5. **FR-310-5's list is wrong; narrow it.** Of every `while (true)` that is a forever-loop in `src/linuxX64Main`,
   only `PlaybackWriter.drain` dies on a foreign cancellation. The rest wrap their body in `runCatching` (Continue
   Watching, playstate, session tick, AI jobs, Bazarr poll, acquisition, request lifecycle, recommendations, MKV
   health, FD watchdog, genre/file-size/version refreshers); the job-queue workers catch `Exception` and the pool
   supervisor refills them; `AudioRenditionJobs`' idle loop calls nothing that throws. The one other casualty is
   `PublishQueue.sendOne` (`catch (e: CancellationException) { throw e }`): a press's drain ends and the item waits
   for the next press or restart (307's restart → waiting). There is no "realtime ingest" loop. So: fix the writer and
   `sendOne`, add the guard script (`scripts/check-cancellation-rethrow.sh`: a rethrow of `CancellationException`
   without an `isActive` check, outside `OutboundHttp`), and drop the `supervisedLoop` migration of loops that are
   already safe.

6. **FR-310-6: Jellyfin's guess is ours to prevent, and it's one field.** Jellyfin 12.1: an idle timer every 5 min
   stops a session whose `LastPlaybackCheckIn` is > 5 min old (only a non-automated progress report moves it), with
   `PositionTicks = LastPlaybackCheckInPositionTicks`; that value is set from the **start report's `PositionTicks`**
   and each progress report. A stop with no position does `PlayCount++, Played = true, PlaybackPositionTicks = 0`.
   Our `startPlaybackSession` sends **`StartPositionTicks`**, which `PlaybackStartInfo` doesn't have (it inherits
   `PositionTicks` from `PlaybackProgressInfo`), so every Ravilo play is "unknown" until its first progress lands.
   **Send `PositionTicks` in the start body** — that replaces the extra progress report. `InactiveSessionThreshold`
   is 0 here (Jellyfin's paused-session stop is off). The session bridge gets no idle-stop signal, so **drop the
   third point** (re-register).

7. **"The stop is the last word" — make it one write.** Today a stop can trigger up to three separate user-data
   writes after it lands: R347's tick (`mark(watched = true)` in the background, racing), R343's unwatched
   write-back, R375's date restore. Fold them into **one** `setUserData(played, positionTicks, lastPlayedDate)` built
   from `resolveStop` (finished ⇒ played, position 0; unfinished ⇒ unplayed at `reportMs`; shuffled ⇒ R343's
   rule), sent after the stop lands. One write, no ordering race, and it also overrides Jellyfin's guess when a stop
   lands late.

8. **FR-310-7: `playback_session` can't be the repair's source.** It is one row per *session*: a shuffled kids'
   session spans many episodes and keeps only its last position. The per-item positions exist only as the backend's
   `playback stop: device=… item=… at Nms` log lines: **21 in the window** (2026-10-06 15:34:40–22:28:30 UTC; the
   shuffled ones say `(reported 0ms: shuffled, R343)`). They survive a `docker restart`, **not** a redeploy (the
   container is recreated). **For the owner:** save them now and repair from them (lean), or repair by hand. Also,
   the 15 writes queued at the restart were in memory and are gone. **For the owner:** keep queued *stops* in
   SQLite so a restart never drops one (lean: yes, a small `playback_outbox` table; progress ticks stay in memory).
   Songs got Jellyfin's guess too (play count +1, marked played). **For the owner:** leave music alone (lean) or
   include it.

9. **FR-310-8 is feasible; one test is the reason this shipped.** `PlaybackWriterTest`'s "thrown timeout" fakes it as
   `IllegalStateException("Timed out waiting for 6000 ms")`, so the rethrow was never exercised. The new tests must
   throw a **real** `TimeoutCancellationException` (its constructor is internal: capture one from another
   coroutine's `withTimeout(1) { awaitCancellation() }`). The backend tests use `runBlocking` and real time with
   millisecond backoffs, so no `kotlinx-coroutines-test` is needed. Files: `PlaybackWriterTest` (extended: foreign
   cancellation, hang + deadline, slow follow-up, own cancellation, supervisor), `OutboundHttpForeignCancellationTest`
   (a call that throws a foreign cancellation becomes an `IOException`; the caller's own cancellation still
   propagates), `JellyfinStartBodyTest` (`PositionTicks` present), `StopUserDataWriteTest` (item 7's single write),
   `PlaybackRepairTest`, `DashboardWriterRowTest`. The Curl ABA itself needs libcurl and a real server, so it is not
   reproducible in a unit test; the boundary test covers its effect.

10. **Compatibility and order.** No wire change. `/api/health`'s new fields are additive. A migration only if the
    outbox is accepted. Build order: (a) the boundary conversion + FR-310-1 + their tests (stops the bleeding),
    (b) `PositionTicks` in the start body, (c) supervisor, health, Dashboard row, (d) the single user-data write,
    (e) the repair, (f) the guard script.

## Decided by the owner (2026-10-07)

1. **Repair (FR-310-7): videos, from the saved stop log.** The backend's stop lines were saved before any redeploy:
   `~/jellystructure/backups/playback-stops-2026-10-06-writer-outage.log` (on the server, outside the repo — it names
   household titles). Every film or episode stopped before its finish (R347) in the window goes back to unwatched at
   its real position, `LastPlayedDate` restored; dry run first, the owner presses Apply. **Songs are left alone.**
2. **Queued stops are kept in the database** (a small table, one migration — the next free number at build time; 309
   also wants one): a stop survives a restart or redeploy until Jellyfin has acknowledged it. Progress ticks stay in
   memory (the next tick supersedes them anyway).
3. **The Ktor Curl-engine bug is written up outside this repo first:** a separate git repository next to this one,
   `../ktor-curl-cancel-repro/`, holds a minimal reproduction and the report, with no household details. It is
   picked up later and becomes either an issue or a pull request to Ktor; nothing is posted yet. Our own fix
   (review item 3, at `OutboundHttp.withPermit`) does not wait for upstream.
