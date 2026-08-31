package dev.jellystructure.ops

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlin.concurrent.AtomicInt
import kotlin.time.TimeSource

/**
 * Phase 118 (FR C.3) — every `popen` call site in the backend (ffmpeg/ffprobe/mkvpropedit/screengrab/
 * health-check shell-outs) shares this gate, on top of any call site's own narrower bound (e.g.
 * Screengrabber's own Semaphore(2), the Phase 109 worker's single-remux serialization). A `popen` briefly
 * holds 2+ FDs (the pipe, plus whatever the child process itself opens) — with no shared ceiling, a burst
 * of user-triggered ffmpeg/ffprobe work could stack unbounded pipes toward the FD_SETSIZE crash.
 *
 * Phase 134 (FR-OPS2 §F): 4 → 16 to give a 100-scan-worker host real parallelism. Deliberately *not*
 * raised as high as the worker/HTTP ceilings — each permit here is a real forked OS process (CPU/memory
 * cost, not just an FD), so this stays a conservative multiple rather than tracking `scan_workers` 1:1.
 *
 * Bug fix: `popen`/`fgets`/`pclose` are blocking native calls with no coroutine suspension point of
 * their own — running them on whatever dispatcher the calling request happened to already be on (i.e.
 * Dispatchers.Default, the same shared pool the Ktor CIO engine's own request handling hops onto) let a
 * burst of artwork-resize calls (e.g. a TV's Home screen re-fetching a page of posters right after
 * reconnecting) tie up enough of that shared pool to stall unrelated HTTP/WS traffic — including the
 * admin web UI and other TVs' WS ping/pong, which read as a timeout and forced a reconnect, which
 * re-fetched the same artwork burst on reconnect and repeated the stall. A dedicated pool sized to the
 * semaphore below — so every granted permit gets its own thread — isolates this from request-serving
 * capacity entirely, the same fix already applied to `scanDispatcher` in Main.kt for the analogous
 * pipeline-vs-request-thread contention bug.
 *
 * Phase 182 (FR-182-7) — that fix isolated CPU, not the PERMIT queue: a scan's ffprobe fan-out and a
 * request-path artwork resize still shared one untimed 16-permit FIFO. Same INTERACTIVE/BACKGROUND
 * split and bounded-acquire shape as [dev.jellystructure.OutboundHttp] — see that object's doc for the
 * full reasoning, not repeated here. `SegmentProcessGate` (Phase 170) is already its own separate gate
 * and is unaffected by this split.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
object ProcessGate {
    private const val MAX_CONCURRENT = 16
    private const val INTERACTIVE_RESERVE = 4
    private const val SHARED = MAX_CONCURRENT - INTERACTIVE_RESERVE

    private const val INTERACTIVE_ACQUIRE_TIMEOUT_MS = 1_500L
    private const val BACKGROUND_ACQUIRE_TIMEOUT_MS = 30_000L
    private const val POLL_INTERVAL_MS = 20L

    private val reserved = Semaphore(INTERACTIVE_RESERVE)
    private val shared = Semaphore(SHARED)
    private val dispatcher = newFixedThreadPoolContext(MAX_CONCURRENT, "process-gate")

    private val reservedInFlight = AtomicInt(0)
    private val sharedInFlight = AtomicInt(0)
    private val interactiveWaiting = AtomicInt(0)
    private val backgroundWaiting = AtomicInt(0)
    private val interactiveTimeouts = AtomicInt(0)
    private val backgroundTimeouts = AtomicInt(0)

    class GateTimeoutException(message: String) : Exception(message)

    suspend fun <T> withPermit(block: suspend () -> T): T =
        if (currentGateClass() == GateClassKind.BACKGROUND) withBackgroundPermit(block) else withInteractivePermit(block)

    private suspend fun <T> withInteractivePermit(block: suspend () -> T): T {
        if (reserved.tryAcquire()) {
            reservedInFlight.incrementAndGet()
            try {
                return withContext(dispatcher) { block() }
            } finally {
                reservedInFlight.decrementAndGet()
                reserved.release()
            }
        }
        interactiveWaiting.incrementAndGet()
        try {
            if (!acquireWithTimeout(shared, INTERACTIVE_ACQUIRE_TIMEOUT_MS)) {
                interactiveTimeouts.incrementAndGet()
                throw GateTimeoutException("ProcessGate saturated (interactive, ${INTERACTIVE_ACQUIRE_TIMEOUT_MS}ms)")
            }
        } finally {
            interactiveWaiting.decrementAndGet()
        }
        sharedInFlight.incrementAndGet()
        try {
            return withContext(dispatcher) { block() }
        } finally {
            sharedInFlight.decrementAndGet()
            shared.release()
        }
    }

    private suspend fun <T> withBackgroundPermit(block: suspend () -> T): T {
        backgroundWaiting.incrementAndGet()
        try {
            if (!acquireWithTimeout(shared, BACKGROUND_ACQUIRE_TIMEOUT_MS)) {
                backgroundTimeouts.incrementAndGet()
                throw GateTimeoutException("ProcessGate saturated (background, ${BACKGROUND_ACQUIRE_TIMEOUT_MS}ms)")
            }
        } finally {
            backgroundWaiting.decrementAndGet()
        }
        sharedInFlight.incrementAndGet()
        try {
            return withContext(dispatcher) { block() }
        } finally {
            sharedInFlight.decrementAndGet()
            shared.release()
        }
    }

    private suspend fun acquireWithTimeout(sem: Semaphore, timeoutMs: Long): Boolean {
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            if (sem.tryAcquire()) return true
            if (start.elapsedNow().inWholeMilliseconds >= timeoutMs) return false
            delay(POLL_INTERVAL_MS)
        }
    }

    fun stats(): GateStats = GateStats(
        totalPermits = MAX_CONCURRENT,
        interactiveReserved = INTERACTIVE_RESERVE,
        sharedCapacity = SHARED,
        reservedInFlight = reservedInFlight.value,
        sharedInFlight = sharedInFlight.value,
        interactiveWaiting = interactiveWaiting.value,
        backgroundWaiting = backgroundWaiting.value,
        interactiveTimeouts = interactiveTimeouts.value,
        backgroundTimeouts = backgroundTimeouts.value,
    )
}
