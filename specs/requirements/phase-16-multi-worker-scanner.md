# Phase 16 — Multi-Worker Scanner with Dynamic Scaling (FR-W1)

## Problem
The scanner processes items sequentially. Large libraries are slow. We want a configurable number of
concurrent scan workers (coroutines), **default 1**, changeable from Settings — and the change must
take effect **while a scan is running**: raising the count spins up more workers immediately, lowering
it drains the excess workers gracefully (each finishes its current item, then stops).

## Confirmed threading model
Kotlin Native coroutines behave like the JVM model on multi-threaded dispatchers.
`Dispatchers.Default.limitedParallelism(N)` is available and works as expected.

## Current state (as-is)
- `runScan` (MediaRoutes.kt) calls `scanner.scan(tracker, skipIds) { item -> store.addOrUpdate; broadcast }`
  — a single sequential loop with `delay(100)` between items.
- `ScanTracker.recordProcessed` uses a plain `MutableSet` (not synchronized).
- `WsBroadcaster.broadcast` is already `Mutex`-guarded.
- `Behavior` config has no worker/thread fields. See [`_investigation-findings.md`](_investigation-findings.md).

## Config
1. Add to `[behavior]` in `AppConfig.Behavior`:
   - `scan_workers = 1` — concurrent coroutine workers. Range 1–32. Default 1. **Hot-configurable**,
     including mid-scan (see Dynamic scaling).
   - `scan_threads = 4` — size of the `limitedParallelism` thread pool the workers run on. Range 1–32.
     Default 4. **Requires app restart** (the dispatcher is created once at startup).
2. Both persisted in `config.toml` under `[behavior]`.

## Backend — producer/consumer worker pool
3. At startup in `Main.kt`, create `val scanDispatcher = Dispatchers.Default.limitedParallelism(config.behavior.scanThreads)`,
   inject into the scan machinery.
4. Refactor `runScan` to producer/consumer:
   - **Producer** (one coroutine): fetches Jellyfin items, filters skips/`skipIds`, sends each into a
     `Channel<JellyfinItem>(Channel.UNLIMITED)`, then closes it. (Refactor `Scanner.scan` so the
     per-item work — match library, derive path, `scanMovie`/`scanSeries` — is a callable
     `scanItem(jItem): MediaItem?`, decoupled from the iteration loop.)
   - **Workers**: launched on `scanDispatcher`, each reads from the channel, calls `scanItem`, stores
     the result, records progress, emits `ItemScanned`. Workers exit when the channel is drained.
5. Guard `ScanTracker._processedIds` with a `Mutex` (`kotlinx.coroutines.sync`) — `recordProcessed`
   is now called concurrently.
6. `WsBroadcaster.broadcast` is already concurrency-safe (verify, keep).

## Backend — dynamic scaling (primary requirement)
7. Maintain a live, mutable target: `targetWorkers: AtomicInt`, initialised from
   `config.behavior.scanWorkers` at scan start, and an `activeWorkers: AtomicInt` (incremented when a
   worker starts its loop, decremented when it exits).
8. A small **supervisor** owns scaling against the in-flight channel:
   - **Scale up** (e.g. 1→5): when `targetWorkers` rises above `activeWorkers`, launch the difference
     immediately as additional workers on the same channel. They begin consuming at once.
   - **Scale down** (e.g. 5→1): set the lower `targetWorkers`. Each worker, after finishing its
     current item, checks `if (workerIndex >= targetWorkers) exit`. Excess workers thus drain
     gracefully — they never abandon an in-progress item, and stop once their current job is done.
9. Settings writes are the trigger: when `PATCH /api/config` changes `scan_workers` and a scan is
   running, update `targetWorkers` and notify the supervisor (e.g. the supervisor polls
   `configStore.current.behavior.scanWorkers` each tick, or `PATCH` calls a `scanController.setWorkers(n)`).
   Choose one mechanism in the plan; the observable behaviour is what matters.
10. `scan_threads` is **not** dynamic — the dispatcher's thread pool is fixed at startup. Raising
    `scan_workers` above `scan_threads` is allowed (more coroutines than threads); document that real
    parallelism is bounded by `scan_threads`.

## `GET /api/scan/status`
11. Add `activeWorkers: Int` and `configuredWorkers: Int` (the current target) to the status response.
    These feed the Activity page's "N/target" runner display (see [`phase-17`](phase-17-activity-log-backend.md)).
    During a drain, `activeWorkers` ticks down toward `configuredWorkers` (e.g. 5→…→1).

## Settings UI
12. Lives in Settings → **Scanning** section (see [`phase-18`](phase-18-settings-page-cleanup.md)):
    - **Scan workers** — numeric input `min=1 max=32`. Hint: "Number of items processed concurrently.
      Changing this during a scan takes effect immediately — more workers start at once; fewer drain
      after finishing their current item."
    - **Scan thread pool size** — numeric input `min=1 max=32`. Hint: "Thread pool the workers run on.
      **Requires an application restart.**" Plus a restart-required amber banner when the form value
      differs from the running value, using `effectiveScanThreads` from `GET /api/config`:
      ```
      ⚠ Thread pool size has changed — restart the application for this to take effect.
      Current: 4 threads · Pending: 8 threads
      ```
13. `GET /api/config` returns `effectiveScanThreads: Int` (value read at startup), distinct from the
    persisted value. `readForm()` includes `scanWorkers` and `scanThreads`.

## Invariants
- A worker never abandons an in-progress item when scaling down.
- `scan_threads` always requires a restart; the dispatcher is immutable after creation.
- Resume/cancel semantics from Phase 7 still hold under the worker pool (processed-id checkpointing
  works across concurrent workers via the mutex).
