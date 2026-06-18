# Phase 15 — Multi-Worker Scanner (FR-W1)

**Status:** Planned

## Problem
The scanner processes items sequentially. Large libraries are slow. Users want configurable
parallelism at two levels: the number of concurrent scan workers (coroutines) and the size of the
thread pool they run on.

## Confirmed threading model
Kotlin Native coroutines work identically to the JVM model when using multi-threaded dispatchers.
`Dispatchers.Default.limitedParallelism(N)` is available and behaves as expected. Different
dispatchers can be used for different parts of the application without issues.

## Config
1. Add two new fields to the `[behavior]` section of `AppConfig` / `Behavior`:
   - `scan_workers = 1` — number of concurrent coroutine workers consuming scan items. Range: 1–32. Default: 1. **Hot-configurable**: changing this during an idle period takes effect on the next scan start.
   - `scan_threads = 4` — size of the `limitedParallelism` thread pool used by the scan dispatcher. Range: 1–32. Default: 4. **Requires application restart** — the dispatcher is created once at startup.
2. Both fields are persisted in `config.toml` under `[behavior]`.

## Backend — worker pool
3. At application startup in `Main.kt`, create a dedicated scan dispatcher: `val scanDispatcher = Dispatchers.Default.limitedParallelism(config.behavior.scanThreads)`. Injected into `MediaRoutes` and used for all scan coroutines.
4. The `runScan` function is refactored to a producer/consumer pattern:
   - **Producer** (single coroutine): fetches Jellyfin items, filters skips, sends `JellyfinItem`s into a `Channel<JellyfinItem>(capacity = Channel.UNLIMITED)`, then closes the channel.
   - **Workers** (N coroutines, N = `configStore.current.behavior.scanWorkers` at scan start): each reads from the channel, calls `scanner.scanMovie`/`scanner.scanSeries`, stores the result, records progress. Workers exit naturally when the channel is exhausted.
   - All worker coroutines are launched on `scanDispatcher`.
5. `ScanTracker.recordProcessed()` is called from multiple workers concurrently — guard `_processedIds` with a `Mutex` (from `kotlinx.coroutines.sync`).
6. `WsBroadcaster.broadcast()` is called from multiple workers concurrently — guard with a `Mutex` if not already thread-safe.
7. `GET /api/scan/status` response gains a field: `"activeWorkers": Int` — number of worker coroutines currently running (tracked with an `AtomicInt` incremented on worker start, decremented on worker end).
8. The number of workers (`scan_workers`) is read once at scan start and fixed for the duration of that scan run.

## Dynamic worker reconfiguration (stretch — may defer)
9. If implementing dynamic reconfiguration: when `scan_workers` is changed in Settings while a scan is running:
   - Increase: launch additional worker coroutines immediately against the same in-flight channel.
   - Decrease: set a `targetWorkers` atomic; workers check it after finishing each item and self-terminate if `workerIndex >= targetWorkers`.
10. If deferred, a note is shown in the Settings UI: "Worker count change takes effect on the next scan start."

## Settings UI
11. In Settings → Scanning section (Phase 17), add two new fields:

    **Scan workers**
    - Label: "Scan workers"
    - Control: numeric input `min=1 max=32`.
    - Hint: "Number of items processed concurrently during a scan. Takes effect on the next scan start."

    **Scan threads**
    - Label: "Scan thread pool size"
    - Control: numeric input `min=1 max=32`.
    - Hint: "Size of the thread pool used by scan workers. **Requires application restart to take effect.**"
    - **Restart required indicator**: when the current `scan_threads` value in the form differs from the value the running backend was started with, show a prominent amber banner below this field:
      ```
      ⚠ Thread pool size has changed — restart the application for this to take effect.
      Current: 4 threads · Pending: 8 threads
      ```
    - The backend exposes the currently-active thread count via a new field `effectiveScanThreads: Int` in `GET /api/config`. This is the value read at startup, not the persisted value.

## Invariants
- `scan_workers` changing does not interrupt a running scan; it applies on next scan start.
- `scan_threads` always requires restart; the thread pool is immutable after creation.
- The UI must clearly distinguish between the two so users understand which requires a restart.
