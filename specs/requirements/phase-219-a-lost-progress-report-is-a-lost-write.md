# Phase 219 — A lost progress report is a lost write, not a timeout to shrug at

> 24 hours of production logs, 2026-09-15 → 16:
>
> ```
> [WARN] Jellyfin reportPlaybackProgress failed: Timed out waiting for 6000 ms                    ×6
> [WARN] Jellyfin reportPlaybackProgress failed: Coroutine "request-handler" timed out waiting for 6000 ms  ×3
> [WARN] Jellyfin reportPlaybackProgress failed: Coroutine "request-handler" timed out waiting for 5000 ms  ×2
> [WARN] Playstate refresh failed: Timed out waiting for 6000 ms                                 ×18 (+8 at 5 s)
> [WARN] Continue Watching refresh failed: Timed out waiting for 6000 ms                         ×15 (+3 at 5 s)
> [ERROR] Unhandled route exception on /api/tv/events: ECONNRESET (104): Connection reset by peer ×43
> ```
>
> The first three lines are the viewer's position being **dropped on the floor**. The last one is a TV
> turning off, recorded 43 times a day as a server error in the Activity log.

## Status

`Planned` — written 2026-09-16 from the live stue-TV sweep
(`specs/research-reports/stue-tv-test-sweep-2026-09-16.md`, findings F10 and F11). Not dev-reviewed,
not built. Backend only; touches the playback write path, the 205 refreshers and the events socket.

**Numbering:** verified against `STATUS.md` on 2026-09-16 — admin taken through 218.

## What the code does (traced against `main`, 2026-09-16)

**The write path.** `TvRoutes.kt:592-597` `POST /tv/playback/progress` → `PlaybackService.reportProgress`
(`:459-476`) → `tvToken(...)` (`withTimeoutOrNull(TOKEN_CHECK_TIMEOUT_MS = 5_000)`, `:920/:927`) →
`JellyfinClient.reportPlaybackProgress` (`:759-775`):

```kotlin
= runCatching { httpPost(".../Sessions/Playing/Progress") { … } }
  .let { if (it.isFailure) Logger.warn("Jellyfin reportPlaybackProgress failed: …") }
```

Fire-and-forget. No retry, no queue, no record that it failed beyond the line. And `runCatching`
swallows a `TimeoutCancellationException` from an *enclosing* deadline — which is exactly what the log
shows: `Coroutine "request-handler" timed out …` inside the progress warning means the request-handler
coroutine's own scope was cancelled mid-write and the write was abandoned. This is the defect phase
**210** fixed in `prewarm_subtitles` (FR-210-1: a `runCatching` that absorbs cancellation), in one
more place. At `main` today the only 6 s deadline is `HomeFeedService.CONTINUE_TIMEOUT_MS` and the 5 s
ones are `PlaystateCache.FETCH_TIMEOUT_MS` and `TOKEN_CHECK_TIMEOUT_MS`; which scope wraps a progress
write at 6 s is not obvious from the call site, and the deployed build predates 211 — FR-219-1 exists
to answer it rather than guess.

**The refreshers.** `PlaystateCache.kt:57-60` (20 s cadence, `withTimeoutOrNull(5_000)` around token +
fetch) and `HomeFeedService.kt:774-778` (60 s cadence, 6 s), both launched with
`GateClass.BACKGROUND` — so they queue on the **shared** outbound pool (182: 48 permits after the 16
interactive are reserved) behind whatever a scan is doing, and the deadline counts the wait. A
refresher that times out is harmless by design (R231/205: the previous value stands) — but 40+ a day
means the household's playstate is routinely 20–60 s staler than the cadence promises, and the
warning cannot tell "Jellyfin was slow" from "we never got a permit".

**The events socket.** `Server.kt:567-600` `webSocket("/api/tv/events")` catches `Throwable` around
its frame loop and logs a disconnect at WARN — yet 43 `ECONNRESET`s reached the StatusPages catch-all
(`:221-224`), so they are thrown **outside** that loop (upgrade or close handshake). `Logger.error`
there "writes both the log line and the Activity entry in one call": every TV that powers off writes
an Activity-log **error**.

## Requirements

**FR-219-1 — Name the deadline.** Every timeout that can cancel a playback write is identified and
carried in the failure line ("progress write for `<item>` cancelled by `<scope>` after `<n>` ms"),
never a bare "Timed out waiting for". No `runCatching` on a write path swallows `CancellationException`
— 210's rule, applied to `reportPlaybackProgress`, `stopPlaybackSession` and their callers.

**FR-219-2 — A write is queued and retried, never abandoned.** `/tv/playback/progress` and
`/tv/playback/stop` enqueue (last-position-wins per `(device, item)`) and respond at once; a dedicated
writer, `GateClass.INTERACTIVE`, retries with jittered backoff until Jellyfin acks or the session is
superseded (180's teardown rules decide "superseded"). The **stop** is the write that must land: it is
retried past the client's disconnect, bounded only by 180's watchdog. A progress tick that is
superseded by a newer tick is dropped silently — that is not a lost write.

**FR-219-3 — A write does not wait behind background work.** The writer holds the interactive reserve
(182) and its deadline, if any, excludes gate wait: measure wait and request separately and log both
on failure. A write that cannot get a permit is *queued*, not timed out.

**FR-219-4 — Refreshers say which of two things happened.** `PlaystateCache` and the Continue Watching
loop time the permit wait and the Jellyfin round trip separately. A cycle skipped for want of a permit
is INFO ("skipped — outbound pool busy, will retry in `<cadence>`"); a Jellyfin timeout stays WARN.
`/api/health` reports, per refresher, the age of the last successful cycle per user, so a stale
household is visible without reading logs.

**FR-219-5 — A TV hanging up is lifecycle.** Whatever throws on `/api/tv/events` outside the frame
loop is caught in the route, logged once at INFO with the device id, never reaches StatusPages, never
writes an Activity entry, never counts as a 500. The catch-all stays for real bugs.

**FR-219-6 — Acceptance.** Twenty-four hours of production logs after deploy: zero `Unhandled route
exception on /api/tv/events`; every write-failure line names its cause; a synthetic test cancels the
request scope mid-write and asserts the write still lands in Jellyfin.

## Non-goals

- Changing the refreshers' cadences or the 205 design; this phase makes them honest, not faster.
- The client's Home refresh on return (R248).
- Jellyfin's own slowness under disk contention (213/214 and the 2026-09-15 incident's follow-ups).

## Verification

1. Unit: the writer retries a `TimeoutCancellationException` and a 5xx, drops a superseded tick, and
   lands a stop after a simulated client disconnect.
2. Live: play, pull the TV's network for ten seconds mid-episode, restore, Back — Jellyfin holds the
   position from just before the pull.
3. Logs and `/api/health` per FR-219-4/6.

## Open questions

- Whether the progress writer should coalesce to one Jellyfin call per `(device, item)` per N seconds
  regardless of client tick rate — probably yes, and R216's QoE sampling already implies a cadence.
- Correlate the refresher timeouts with `job_queues` occupancy once 213 is live: if they line up with
  `prewarm_subtitles` runs, FR-219-4's "pool busy" line will say so on its own.
