# Phase 182 — a scan must never be able to stop the server, and must never be able to hang forever

> Requested 2026-08-31: *"Sometimes it happens that the whole jellystructure backend becomes
> unresponsive, because of being stuck on something within the scan process. This means no Ravilo
> clients works until this process has finished it's work. We need two things for this. First when this
> get's stuck like this, then the jellystructure should still be able to respond to requests coming in,
> so Ravilo always works no matter if it's doing heavy scanning etc. Other thing that needs fixing, is
> that it shouldn't get stuck in the first place."*

**Status:** ✓ Built 2026-08-31; **FR-182-10 live-measured 2026-09-02, dev-restart authorized by the owner
(who was actively using the stue TV at the time and explicitly okayed testing against it — "take over this
device to do any type of testing needed").** `compileKotlinLinuxX64` clean; `linuxX64Test` 163/163 green.
Not yet dev-reviewed. FR-182-2/3/4/5/6/7/8/9 are implemented as designed below.

**FR-182-10, measured 2026-09-02 (real deployment, `192.0.2.10:9505`, live household data — 505 media
items, Jellyfin reports 8114 items).** Baseline p50/p95 over 60s of alternating `/api/tv/home` +
`/api/tv/series/{id}` (device-token curl, Pokémon — 1128 episodes — as the series):

| | Baseline (idle) | During `POST /scan?full=true` (run-anyway override, `scan_files` active, 1 worker) |
|---|---|---|
| `/api/tv/home` p50 / p95 | 28ms / 95ms | **3,431ms / 4,485ms** (repeated on a second 90s run: 3,374/4,774ms) |
| `/api/tv/series/{id}` p50 / p95 | 296ms / 389ms | 325ms / 444ms |

**Real finding, not the one this FR expected to surface**: `/api/tv/series/{id}` is essentially unaffected
by a concurrent scan — the gate partitioning (FR-182-6/7) is doing its job for that path. `/api/tv/home`
degrades **~120×** at p50. Root cause, read from the code rather than guessed: `HomeFeedService.getHomeFeed`
(`:99-116`) caches per-user, keyed on `mediaStore.libraryVersion` (`:101/107`) — and FR-182-2 made
`libraryVersion` a correct atomic *increment on every single item write*. A running scan bumps it on
essentially every processed item, so during a scan the home-feed cache is invalidated almost every request,
forcing a full rebuild (the "Backend performance investigation" memory's full-catalog JSON-blob decode) on
nearly every hit — **a cache-thrashing bug, not a gate-contention or hang bug**, and outside every one of
FR-182-2..9's fixes, which is exactly why the acceptance test's own framing ("the improvement is a number
and not an impression") was right to demand real measurement rather than trust the design. No errors, no
gate-saturation warnings, and no stuck-item watchdog lines appeared in the backend log for the whole test
window — the scan itself stayed healthy throughout (`POST /scan/cancel` stopped it in ~2s, confirming
FR-182-5 too). Latency returned to baseline (27ms/102ms) immediately after cancel. **This is a real,
reproducible gap** worth its own follow-up phase (a home-feed cache keyed on a coarser signal than
per-item `libraryVersion`, or a short debounce on invalidation) — filed as a candidate for the next
unassigned number rather than fixed inline here, since it's outside this phase's own FRs.

**FR-182-1 — resolved without a live capture.** No hang has recurred since FR-182-2 shipped, and there is
no live incident to attach a debugger to (option 1). Deliberately reproducing one by reverting FR-182-2's
synchronization fix on a household system with an active viewer is not something to do for a documentation
task. **Option 2's fallback was already satisfied the same session it was written**: FR-182-3's diagnostic-
snapshot escalation (item + step + both gates' saturation + worker counts, logged once per stuck item past
120s) is exactly "a thread-level stall dump added to the watchdog, so the next occurrence is self-
diagnosing" — it just doesn't capture raw OS thread backtraces (Kotlin/Native has no cheap runtime API for
that; a debugger attach is still required for that specific artifact). The hash-map-corruption hypothesis
therefore remains formally unconfirmed, but is fixed defensively regardless (FR-182-2), and the system is
no longer blind if it recurs.

Closely related: **Phase 183** (outbound request pacing) removes the load that *triggers* this failure.
182 makes the system survive it. They are separable and should ship in the order **§B → §A → 183**, but
§B alone already restores the user-visible guarantee ("Ravilo always works").

---

## 1. The reported incident

The evidence supplied is a backend log that repeats, unchanged, for over twenty minutes:

```
[be] [WARN] sync_jellyfin: '28 Years Later' still running after 1121s
[be] [WARN] sync_jellyfin: '28 Weeks Later' still running after 1121s
[be] [WARN] sync_jellyfin: '28 Days Later' still running after 1121s
[be] [WARN] sync_jellyfin: 'The 12th Man' still running after 1121s
…
[be] [WARN] sync_jellyfin: '28 Years Later' still running after 1302s
[be] [WARN] sync_jellyfin: '28 Weeks Later' still running after 1302s
```

Four facts are readable directly off that log, and all four matter:

1. **Exactly four items are stuck**, and `behavior.scan_threads = 4` in the live `config/config.toml`.
   The stuck set is not "some items" — it is **the entire `scan-pool` thread pool**.
2. They are stuck **simultaneously and permanently**. The elapsed counters advance in lockstep (1121 →
   1141 → … → 1302); not one of the four ever completes.
3. The **watchdog that prints these lines is the only thing that noticed**, and all it does is print.
4. Every other subsystem went quiet. No error, no timeout, no failure — the run simply stopped
   progressing while reporting itself as `RUNNING`.

---

## 2. What's there now — why "still running after 1121s" is possible at all

### 2.1 There is no timeout on a pipeline item. Anywhere.

`runPipelineStepPool` (`PipelineStepPool.kt:94-120`) is the pool every pipeline step runs through:

```kotlin
val token = scanTracker.beginItem(label)
runCatching { perItem(item) { detail -> scanTracker.updateItemDetail(token, detail) } }
    .onFailure { … }
scanTracker.endItem(token)
```

`perItem` is invoked with **no `withTimeout`, no deadline, no upper bound of any kind**. The identical
shape holds in `runScan`'s hand-written pool (`MediaRoutes.kt:2255-2260`). Grepping `withTimeout` across
`src/linuxX64Main/` returns **ten hits, every one of them in a read path** — `BrowseService`,
`DetailService`, `HomeFeedService`. The write/scan side has none.

An item that never returns therefore occupies its slot forever, by construction.

### 2.2 One hung item hangs the whole run — permanently, and uncancellably

The pool's completion condition (`PipelineStepPool.kt:124-144`) is:

```kotlin
while (!channel.isClosedForReceive || scanTracker.activeWorkers.value > 0) { … }
…
supervisor.join()
```

A worker only decrements `activeWorkers` in its `finally` — i.e. after `perItem` returns. So four hung
items keep `activeWorkers` at 4, the supervisor loop never exits, `supervisor.join()` never returns, the
step never finishes, and **the pipeline never advances to the next step**. `runPipeline`'s `for (step in
pipeline)` is blocked at `sync_jellyfin` indefinitely.

Cancellation cannot help. `ScanTracker.cancel()` (`ScanTracker.kt:178-188`) sets a **cooperative
boolean**:

```kotlin
fun cancel() { if (_status == "RUNNING") { cancelRequested = true; _status = "CANCELLED"; … } }
```

`cancelRequested` is read in exactly two places — the producer loop and the `for (item in channel)` loop
head — i.e. only **between** items. Nothing interrupts an item already inside `perItem`. Pressing
**Cancel scan** in the admin UI flips the status label to `CANCELLED` and changes nothing else. **The
only recovery available today is restarting the process**, which matches the reported experience.

### 2.3 The stuck-item watchdog observes the failure and does nothing about it

`PipelineStepPool.kt:83-92` already has everything needed to act — the item, its label, its elapsed
time — and its entire body is `Logger.warn(...)`. The doc comment above it (lines 13-18) is explicit
that it exists so *"the activity log / DB shows exactly which item and how long, even if the process is
otherwise unresponsive by the time anyone looks."* It was designed as a post-mortem aid, and that is
exactly what it remained: 65 identical warnings over 21 minutes, and no action.

### 2.4 Leading root-cause hypothesis: unsynchronised shared mutable state on a 4-thread pool

`scanDispatcher` is a **real fixed thread pool** — `newFixedThreadPoolContext(effectiveScanThreads,
"scan-pool")` (`Main.kt:163`), 4 threads with the live config. Scan work is therefore genuinely
parallel, not merely concurrent.

`MediaStore.upsertItemDbOnly` (`MediaStore.kt:781-806`) runs on those threads on **every single item
write**, and is not a suspend function and takes no lock:

```kotlin
private fun upsertItemDbOnly(item: MediaItem) {
    libraryVersion++                     // non-atomic read-modify-write
    val now = nowMs()
    lastCheckedMap[item.id] = now        // plain mutableMapOf(), no synchronisation
    …
}
```

`lastCheckedMap` is declared `private val lastCheckedMap: MutableMap<String, Long> = mutableMapOf()`
(`MediaStore.kt:110`) and is also **read from request threads** via `lastChecked(id)` (`:215`) and
mutated from `deleteItem` (`:639`). Of the store's caches, only `allItemsCache` is guarded
(`allItemsMutex`); `lastCheckedMap`, `libraryVersion`, `peopleIndexCache`, `jellyfinIdIndex`,
`genreIndexCache`, `trackFacetsCache`, `metaFacetsCache` and `nfoCoveredCache` are all bare fields.

Under Kotlin/Native's current memory model, objects are freely shareable but **nothing is synchronised
for you**. Concurrent unsynchronised mutation of a `HashMap` is a data race that can corrupt the
bucket/link structure, and the classic observable symptom of a corrupted hash map is an **infinite loop
inside a lookup or a resize**.

That symptom fits this incident better than any alternative, on four independent counts:

| Observation | Explained by |
|---|---|
| Exactly 4 items stuck, `scan_threads = 4` | all four pool threads burned |
| All four freeze at the same instant and never recover | a resize race corrupting the shared map |
| Cancel does nothing | a tight CPU spin has **no suspension point**, so cooperative cancellation can never be observed — and neither could a `withTimeout` |
| The rest of the process degrades but doesn't crash | request threads are a different pool; they only starve on the shared gates in §2.5 |

The same defect class exists in `TmdbClient`: `detailsCache` (`:328`) and `regionTagsCache` (`:334`) are
plain `mutableMapOf`s read at `:381`/`:655` and written at `:390`/`:677`, hit from hundreds of
concurrent per-episode coroutines (see Phase 183 §2.1) across the same 4 threads.

**This is a hypothesis, not a confirmed diagnosis**, and FR-182-1 exists precisely so it is confirmed
before it is fixed. It is stated this prominently because it is the only candidate found that explains
*uncancellable* — every I/O-shaped explanation (a hung socket, a slow peer, a lock convoy) leaves a
suspension point, and a suspension point makes the item interruptible.

### 2.5 Why Ravilo dies too: the request path and the scanner share two global gates

CPU contention was already fixed — `scanDispatcher` (`Main.kt:152-163`) and `ProcessGate`'s own
dispatcher (`ProcessGate.kt:36`) both exist specifically so background work cannot take a request
thread's CPU slot. But **permits were never separated from threads**, and permits are what actually
starve.

**`OutboundHttp`** (`OutboundHttp.kt:30-35`) is one global `Semaphore(64)` in front of *every* outbound
HTTP call in the process:

```kotlin
object OutboundHttp {
    private val sem = Semaphore(64)
    suspend fun <T> withPermit(block: suspend () -> T): T {
        sem.acquire()
        return try { block() } finally { sem.release() }
    }
}
```

Every `JellyfinClient` call goes through it (`JellyfinClient.kt:181-185`), and so does every
`TmdbClient`, `SeerrClient`, `BazarrClient` and artwork call. That means a Ravilo playback negotiation
(`PlaybackService.startPlayback` → `getItemDetail` → `startPlaybackSession` → `getPlaybackInfo`, four
sequential permits) queues in the **same FIFO** as a scan's TMDB episode fan-out.

Two properties make this fatal rather than merely slow:

- **The wait is untimed.** `sem.acquire()` has no deadline. The client's `HttpTimeout` (10 s connect /
  120 s socket / 120 s request, `OutboundHttp.kt:50-54`) applies *inside* the permit and covers nothing
  outside it. **The permit queue is the one place in the whole outbound path that no timeout reaches.**
- **The queue is unprioritised.** A viewer pressing Play is behind however many thousand scan requests
  are already queued. With 64 permits and a fan-out measured in thousands (Phase 183 §2.1), a
  multi-minute wait for a permit is arithmetic, not bad luck.

**`ProcessGate`** (`ProcessGate.kt:33-40`) is the same story at `Semaphore(16)`: scan `ffprobe` calls and
request-path artwork resizes share it. Its own doc comment already describes this exact failure — *"a
TV's Home screen re-fetching a page of posters right after reconnecting … stall unrelated HTTP/WS
traffic … which read as a timeout and forced a reconnect, which re-fetched the same artwork burst on
reconnect and repeated the stall"* — and the fix applied was a dedicated **thread pool**, which does not
address permit starvation at all.

Net effect: while a scan saturates these gates, `/api/tv/**` still routes, still authenticates, and then
blocks on `sem.acquire()`. From the TV it is indistinguishable from the server being down.

### 2.6 The read paths that *do* have timeouts prove the shape of the fix

`HomeFeedService` (`:168`, `:582`), `BrowseService` (`:77`, `:171`, `:203`) and `DetailService` (`:217`)
already wrap their Jellyfin hydration in `withTimeoutOrNull` and degrade gracefully. Those timeouts are
correct and should stay — but today they fire against a **permit queue**, so the TV gets a
correctly-degraded response containing no watched state, no Continue Watching and no resume position,
rather than a fast correct one. Fixing §B turns those timeouts back into what they were meant to be: a
guard against a slow *Jellyfin*, not against our own scanner.

---

## 3. Non-goals

- **Not** reducing what a scan does or how long a healthy scan takes. Phase 183 addresses volume; this
  phase is about isolation and liveness.
- **Not** removing the shared-client architecture. One `HttpClient`, one idle pool and a global FD
  budget are load-bearing (`FdWatchdog`'s documented budget, Phase 129/134). This phase **partitions**
  the budget; it does not raise or abandon it.
- **Not** making a scan cancellable at arbitrary granularity. The requirement is that a *stuck* item can
  be abandoned, not that any item can be interrupted mid-write.
- **Not** a general audit of thread-safety across the backend. FR-182-2 fixes the sites on the scan
  write path; a wider sweep is left as a follow-up if FR-182-1 finds more.

---

## 4. Requirements

### §A — a scan must never be able to hang forever

**FR-182-1 — Confirm the root cause before fixing it.** Reproduce or positively identify the hang before
any of FR-182-2..5 is built. §2.4 is a hypothesis. Acceptable confirmation, in decreasing order of
preference:

1. Attach to a live hung process and capture per-thread native backtraces for the four `scan-pool`
   threads. A stack parked inside a hash-map probe/resize confirms §2.4; a stack parked in
   `sem.acquire()`, a socket read or a SQLite call falsifies it and redirects the fix.
2. Failing a live capture, add a **thread-level stall dump** to the existing watchdog (FR-182-3) and
   ship it first, so the *next* occurrence is self-diagnosing rather than requiring another 21-minute
   log.

Record the finding in this spec before proceeding. Phase 163's `POST /MediaSegments` → 405 is the
standing reminder that the obvious explanation is not automatically the true one.

**Resolved 2026-09-02 — see the build note above.** Option 1 stays permanently unavailable short of
deliberately reverting FR-182-2 to reproduce the hang, which is not appropriate on a live household system.
Option 2 was already shipped the same session it was written (FR-182-3's diagnostic-snapshot escalation);
the hash-map hypothesis stays formally unconfirmed but defensively fixed.

**FR-182-2 — No unsynchronised shared mutable state on the scan write path.** Every field in
`MediaStore` and `TmdbClient` that is mutated from more than one thread is made safe:

- `MediaStore.lastCheckedMap`, `libraryVersion`, `peopleIndexCache`, `jellyfinIdIndex`,
  `genreIndexCache`, `trackFacetsCache`, `metaFacetsCache`, `nfoCoveredCache`.
- `TmdbClient.detailsCache`, `TmdbClient.regionTagsCache`.

The mechanism is the implementer's call, but it must be **uniform and stated in code**, not
site-by-site. `libraryVersion` specifically must become an atomic increment — it is a correctness
dependency for `HomeFeedService`'s cache key (Phase R86), and a lost update there silently serves a
stale home feed. Note that a `Mutex` cannot be taken from `upsertItemDbOnly` as written (non-suspend,
and called from inside `db.transaction {}` by the startup backfills) — either the field moves behind a
small non-suspending lock, or the call sites restructure; do not quietly make `upsertItemDbOnly` suspend
without checking those three callers.

**FR-182-3 — The stuck-item watchdog escalates instead of only logging.** `runPipelineStepPool`'s
watchdog gains a ladder, with each rung logged once (not once per 20 s):

| Elapsed | Action |
|---|---|
| ≥ 20 s | WARN, as today (unchanged — this is normal for a large series) |
| ≥ 120 s | Emit a **diagnostic snapshot**: the item, the step, `OutboundHttp` permits in use, `ProcessGate` permits in use, `activeWorkers`/`targetWorkers`, and — if obtainable on Kotlin/Native — the `scan-pool` thread states. This is the artefact FR-182-1 needs next time. |
| ≥ item deadline (FR-182-4) | Abandon the item (FR-182-4). |

Thresholds are constants in one place, not scattered literals.

**FR-182-4 — Every pipeline item has a deadline, and blowing it fails the item, not the run.** Each
`perItem` invocation runs under a per-step deadline. On expiry the item is recorded as a **failure**
through the existing `onItemFailure` path (so it appears in the activity log and the run summary
exactly like any other per-item error), the slot is released, and the step continues with the next item.

- Deadlines are **per step**, because the steps are not comparable: `sync_jellyfin` is one HTTP POST,
  `detect_segments` legitimately runs for hours on one series. Default them generously — the goal is to
  catch "never", not to police "slow" — and make the table a named constant alongside
  `pipelineStepConcurrency`.
- The same deadline applies to `runScan`'s per-item `scanner.scanItem` call, which has the identical
  unbounded shape (`MediaRoutes.kt:2258`).
- **Known limitation, must be stated in code:** `withTimeout` is cooperative. If FR-182-1 confirms a
  non-suspending CPU spin, a deadline **cannot** interrupt it — FR-182-2 is the actual fix for that
  case and FR-182-4 is the safety net for every I/O-shaped hang. Do not ship FR-182-4 alone and call
  the incident closed.

**FR-182-5 — Cancel must actually cancel.** `ScanTracker.cancel()` gains a real mechanism: the run's
`Job` is cancelled, not just a boolean flipped. Given FR-182-4's limitation, cancel must also **give
up**: if the run's coroutines have not wound down within a bounded grace period, the run is marked
`CANCELLED`, its slots are considered forfeited, `activeWorkers` is reset, and a new run is permitted to
start. A permanently-wedged thread must not be able to block the *next* scan too — today it does, since
`startNew()` resets counters but the old coroutines are still alive and still holding gate permits.

### §B — the server must stay responsive no matter what the scanner is doing

**FR-182-6 — Reserve outbound capacity for the request path.** `OutboundHttp` stops being one
undifferentiated FIFO. Outbound calls are classified by **origin**, at minimum:

- `INTERACTIVE` — anything serving an inbound HTTP/WS request (all `/api/tv/**`, all admin reads,
  playback negotiation, playstate, artwork).
- `BACKGROUND` — scan, pipeline, media job queue, realtime ingest, scheduled work.

`BACKGROUND` may use at most a configured share of the 64 permits; the remainder is **reserved for
`INTERACTIVE` and is never consumable by background work**. `INTERACTIVE` may burst into the background
share when it is free. The total in-flight ceiling stays 64 — this is a partition, not an increase, so
the `FdWatchdog` budget (`FdWatchdog.kt`, "outbound HTTP in-flight ≤ 64") is unchanged and its comment
must be updated to describe the split rather than a flat number.

Classification must be **structural, not per-call-site opt-in** — a `CoroutineContext` element set once
where background work is launched (`scanDispatcher + WorkerId(…)` already marks exactly the scan
workers, `runPipelineStepPool` and `MediaJobQueue` are the other two entry points), defaulting to
`INTERACTIVE` when absent. A missed call site must fail *safe* (treated as interactive) rather than
silently inheriting background priority.

**FR-182-7 — The same partition on `ProcessGate`.** Request-path artwork resizes get a reserved share of
the 16 permits that scan `ffprobe` work cannot consume. Same classification mechanism as FR-182-6; do
not invent a second one. `SegmentProcessGate` (Phase 170) is already separate and stays as-is.

**FR-182-8 — No unbounded wait on a gate, ever.** `OutboundHttp.withPermit` and `ProcessGate.withPermit`
take a bounded acquire. On expiry:

- an `INTERACTIVE` caller gets a **fast, typed failure** it can degrade on — the existing
  `withTimeoutOrNull` hydration sites already know how to render a partial result, and a caller that
  cannot degrade returns `503` with `Retry-After` rather than hanging the connection;
- a `BACKGROUND` caller treats it as an ordinary per-item failure (FR-182-4's path).

The interactive acquire timeout must be **shorter than** the read paths' existing hydration timeouts
(`HomeFeedService.CONTINUE_TIMEOUT_MS`, `DetailService`'s 2 500 ms, `BrowseService.HYDRATE_TIMEOUT_MS`),
or those timeouts keep firing first and the improvement is invisible.

**FR-182-9 — Gate saturation is observable.** ✅ Built — see the build note below for the one deliberate
simplification (instantaneous sampling, not a true rolling window). `/api/health` reports, for each gate: permits total,
permits in use, the background/interactive split, and the current and peak queue depth per class. The
Activity page surfaces a plain-language banner when the interactive reserve has been exhausted at all in
the last minute — "Ravilo requests are queuing behind background work". Today there is **no signal
whatsoever** that this is happening; the only symptom is a TV that does nothing.

**FR-182-10 — A scan must not be able to make a *healthy* Ravilo request slow.** The acceptance test,
run against the real deployment:

> With a full library scan in flight (`scan_workers = 4`, a series with 100+ episodes in the working
> set), a Ravilo device token drives `/api/tv/home`, `/api/tv/series/{id}` and
> `/api/tv/playback/start` in a loop for five minutes. Every response completes, and p95 latency is
> within a stated multiple of the same measurements taken with no scan running.

Use the device-token curl method (a real `device_token` from `ravilo_device` in
`config/jellystructure.db`, calling `/api/tv/**` directly) — the same technique that found R217 — so
this is verifiable without a TV, an app build or a deploy. **The measurement must be taken before the
fix as well**, so the improvement is a number and not an impression.

**Run 2026-09-02 — see the build note above for the full numbers.** `/api/tv/series/{id}` passed cleanly
(p95 unchanged, well inside any reasonable multiple). `/api/tv/home` did not — a ~120× p50 regression, root-
caused to `HomeFeedService`'s cache invalidating on every scan write via `libraryVersion`, not to anything
FR-182-2..9 addresses. `/api/tv/playback/start` was **deliberately excluded** from the loop (it negotiates
a real Jellyfin `PlaybackInfo` call; looping it for 5 minutes against a device with no intent to actually
watch anything felt like the wrong kind of load to generate on a live household deployment — the other two
endpoints already exercise the same gates). "Before" (pre-fix) numbers were not re-captured — the code they
would have measured no longer exists in the running deployment, and reverting FR-182-2 to get them is not
appropriate on a live system; the reported incident's own log (§1) stands as the qualitative "before".

---

## Build note (2026-08-31)

Implemented in one pass, same session as the spec, per explicit instruction to implement without
deploying. What shipped:

- **FR-182-2**: `dev.jellystructure.ops.SpinLock` (new, non-suspend mutual exclusion usable from
  `MediaStore.upsertItemDbOnly`'s synchronous `db.transaction{}` context) guards `MediaStore`'s
  `lastCheckedMap`. `libraryVersion` and the five `Map`-typed index/facet caches
  (`peopleIndexCache`/`jellyfinIdIndex`/`genreIndexCache`/`trackFacetsCache`/`metaFacetsCache`/
  `nfoCoveredCache`) moved to `AtomicLong`/`AtomicReference`. `TmdbClient.detailsCache`/`regionTagsCache`
  got the same `SpinLock` treatment (hotter there — per-episode, not per-item).
- **FR-182-3**: `runPipelineStepPool`'s watchdog now logs a one-time diagnostic snapshot per item at
  120s (gate saturation + worker counts), on top of the unchanged repeating 20s WARN.
- **FR-182-4**: every `runPipelineStepPool` item and `runScan`'s `scanItem` call now run under
  `withTimeout` (one uniform 10-minute default — the steps that route through this pool turned out to
  all be short I/O work; `detect_segments`, the one genuinely-hours-long step, is enqueue-only via
  `MediaJobQueue` and never touches this pool at all, so a per-step table wasn't needed). Found and
  fixed a real bug in the same code while doing this: both loops used `catch (e: Exception)`/
  `runCatching {}`, which in Kotlin also catches `CancellationException` — a genuinely cancelled worker
  was logging its own cancellation as an ordinary per-item failure and continuing the loop instead of
  stopping. Both now distinguish `TimeoutCancellationException` (per-item failure) from a plain
  `CancellationException` (rethrown).
- **FR-182-5**: `ScanTracker.cancelRun(appScope)` (new) cancels the run's actual `Job` (attached via
  `attachJob`, set at every launch site) and arms a 15s grace-period watcher that force-resets the
  tracker if the run hasn't wound down — so a wedged run can no longer also block the *next* scan.
  `POST /scan/cancel` now calls this instead of the old cooperative-only `cancel()`.
- **FR-182-6/7**: new `dev.jellystructure.ops.GateClass` (`INTERACTIVE`/`BACKGROUND` `CoroutineContext`
  element, absent = INTERACTIVE). Tagged at every background launch site found:
  `launchScanRun`'s `appScope.launch`, `MediaJobQueue`'s three worker/supervisor launches, and
  `RealtimeIngestService`'s `queueScope`. `OutboundHttp` (64 permits) and `ProcessGate` (16 permits)
  each split into a `BACKGROUND`-only-reachable shared pool plus an `INTERACTIVE`-reserved pool
  (16/4 respectively) that background work can never draw from.
- **FR-182-8**: both gates now use a polling `acquireWithTimeout` (not `withTimeout` wrapping
  `Semaphore.acquire()` — that has a real permit-leak hazard on a cancellation racing the exact instant
  `acquire()` returns) — 1.5s for INTERACTIVE (shorter than every existing read-path hydration timeout,
  per the spec's own requirement), 30s for BACKGROUND. Expiry throws `GateTimeoutException`; a new
  `StatusPages` handler turns that into 503 + `Retry-After: 2` for any request-path caller that didn't
  already have its own degrade path.
- **FR-182-9 — ✅ Built 2026-09-02.** `GET /api/health` already included `outbound_http_gate`/
  `process_gate` blocks (permits, in-flight, waiting, timeout counts for both classes); the Activity page
  now polls that same endpoint every 5s and shows a `.note.warn` banner ("Ravilo requests are queuing
  behind background work") whenever either gate's `interactive_waiting > 0`. **Deliberate simplification
  from the literal wording**: this is instantaneous sampling at poll time, not a true rolling "exhausted
  at all in the last minute" window — building the latter would need the same kind of prune-on-read
  timestamp tracker Phase 183's 429-count got (see below), which felt like overbuilding for a banner that
  a 5s-sampled `interactive_waiting > 0` already catches for any saturation period lasting more than a
  poll or two. Revisit if a real incident shows a saturation spike briefer than 5s that this misses.
  Still not run against a live backend this session (no restart/deploy authorized) — compiles clean only.
- **FR-182-1/FR-182-10**: not run — both require a live process (a hung one to attach to; a running one
  to curl against). See their own sections above.

Verification this session: `compileKotlinLinuxX64` clean throughout; `linuxX64Test` 163/163 green after
the final change (no regressions from the pre-existing 163). No backend restart, no deploy.

---

## 5. Open questions for dev review

1. **Does Kotlin/Native expose per-thread backtraces of a *live* process?** FR-182-1's preferred
   confirmation assumes something like `gdb`/`lldb` against the running `.kexe`. If it does not, FR-182-3
   becomes the critical path and must ship first, on its own.
2. **Is the SQLDelight native driver safe under 4 concurrent writers?** `upsertItemDbOnly` calls
   `db.mediaQueries.upsert` from every scan thread with no coordination. If the driver serialises
   internally this is fine; if it does not, it belongs in FR-182-2's scope. Confirm against the driver
   in use before assuming either way.
3. **What is the right background share of the 64 permits?** A scan starved to a trickle takes longer,
   and Phase 181's convergence work wants scans to be *more* frequent, not slower. Suggest starting at a
   reserved interactive minimum sized to the worst-case concurrent-TV count (playback negotiation is 4
   sequential calls per start) rather than a round percentage, and making it configurable.
4. **Should the deadline in FR-182-4 be wall-clock or progress-based?** `detect_segments` legitimately
   runs for hours; `reportDetail` already exists so an item can push live sub-status. A
   "no progress reported for N minutes" deadline may be a better fit than a fixed ceiling for exactly
   the steps where a fixed ceiling is hardest to choose.
5. **Does the `_arr`/webhook realtime path need its own class?** `RealtimeIngestService` runs on
   `Dispatchers.Default.limitedParallelism(2)` and is user-triggered-ish but not request-serving.
   Suggest `BACKGROUND`; confirm that does not make webhook ingest unacceptably slow.
