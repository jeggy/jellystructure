# Phase 310 — Watch progress always reaches Jellyfin, and nothing is marked watched by accident

> Owner, 2026-10-07: *"I just watched [an episode] on Stue TV, but didn't finish, but looking now, it shows that I finished
> the episode. Something is clearly broken here, please find out what."* — and then: *"Let's write a spec to fix it and
> have it as a requirement that you should write tests as well, so this type of bug will not come again."*

## Status

`Planned` — written 2026-10-07 (dev-authored) from a read-only investigation on the dev stack (v1.50-30-g0df9d193,
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
