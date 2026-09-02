package dev.jellystructure

import dev.jellystructure.ops.GateClassKind
import dev.jellystructure.ops.GateStats
import dev.jellystructure.ops.currentGateClass
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import kotlin.concurrent.AtomicInt
import kotlin.time.TimeSource

/**
 * Global cap on simultaneous outbound HTTP connections, plus (Phase 129) the single shared Curl
 * client every stateless outbound caller should use.
 *
 * Kotlin/Native's CIO server uses select() which crashes fatally when any file descriptor
 * reaches FD_SETSIZE (1024). Every live Curl connection consumes one FD, and with many scan
 * workers each making concurrent TMDB + Jellyfin + artwork calls the total easily exceeds
 * the ceiling. All backend HTTP clients must acquire a permit before opening a connection.
 *
 * Budget: 64 concurrent outbound connections (in-flight, via [withPermit]) + one shared idle
 * connection pool (resting keep-alive sockets, via [client]) instead of the ~12 independent
 * pools a `HttpClient(Curl)` per caller used to leave resting. Remaining headroom covers inbound
 * server connections, WebSocket sessions, SQLite WAL, ffprobe pipes, and stdio.
 *
 * Phase 134 (FR-OPS2 §F): 24 → 64 — this is the gate up to 100 concurrent scan workers actually
 * queue behind for TMDB/artwork/Jellyfin calls, so unlike `ProcessGate` (real forked processes,
 * kept conservative) this one scales with worker count: a stalled HTTP round-trip only costs
 * latency, not host resources, so a higher in-flight cap is safe throughput, not a resource risk.
 *
 * Phase 182 (FR-182-6/FR-182-8) — this used to be ONE undifferentiated FIFO shared by every
 * outbound caller, INTERACTIVE (a Ravilo playback negotiation, an admin page read) and BACKGROUND
 * (a scan's TMDB/Jellyfin fan-out) alike, with an UNTIMED `sem.acquire()` — the one place in the
 * whole outbound path no `HttpTimeout` reaches, since the timeout only applies once inside the
 * permit. Under a heavy scan this made the server look completely down to every TV. The 64-permit
 * total is now split: [INTERACTIVE_RESERVE] permits [GateClassKind.BACKGROUND] work can NEVER
 * consume, and the remaining [SHARED] pool either class may use (INTERACTIVE bursts into it when
 * its reserve is empty; BACKGROUND only ever draws from here). Both classes now acquire with a
 * bounded wait (never an unbounded one) — see [withPermit]'s doc for what happens on expiry.
 */
object OutboundHttp {
    private const val TOTAL_PERMITS = 64

    // Sized to the worst-case concurrent-TV burst rather than a round percentage — a single playback
    // negotiation alone is 4 sequential OutboundHttp permits (getItemDetail/startPlaybackSession/
    // getPlaybackInfo/reportPlaybackProgress), so this covers several concurrent starts without ever
    // touching the shared pool a scan is working through. Revisit against real fleet size — see
    // phase-182's open question #3.
    private const val INTERACTIVE_RESERVE = 16
    private const val SHARED = TOTAL_PERMITS - INTERACTIVE_RESERVE

    // INTERACTIVE_ACQUIRE_TIMEOUT_MS is deliberately SHORTER than every read-path hydration timeout
    // (HomeFeedService.CONTINUE_TIMEOUT_MS, DetailService's 2_500L, BrowseService.HYDRATE_TIMEOUT_MS) —
    // otherwise those timeouts fire first and this bound is invisible in practice (phase-182 FR-182-8).
    private const val INTERACTIVE_ACQUIRE_TIMEOUT_MS = 1_500L
    private const val BACKGROUND_ACQUIRE_TIMEOUT_MS = 30_000L
    private const val POLL_INTERVAL_MS = 20L

    private val reserved = Semaphore(INTERACTIVE_RESERVE)
    private val shared = Semaphore(SHARED)

    // Introspection only (FR-182-3's diagnostic snapshot, FR-182-9's /api/health surface) — never read
    // for control-flow decisions, so a stale/racy read here is harmless.
    private val reservedInFlight = AtomicInt(0)
    private val sharedInFlight = AtomicInt(0)
    private val interactiveWaiting = AtomicInt(0)
    private val backgroundWaiting = AtomicInt(0)
    private val interactiveTimeouts = AtomicInt(0)
    private val backgroundTimeouts = AtomicInt(0)

    class GateTimeoutException(message: String) : Exception(message)

    /**
     * Acquires a permit sized to the caller's [dev.jellystructure.ops.GateClass] (INTERACTIVE by
     * default — see that class's doc on why absent means interactive) and runs [block], releasing
     * afterward. On a bounded-wait expiry throws [GateTimeoutException] — callers that can degrade
     * (the existing `withTimeoutOrNull` hydration sites) should catch it exactly like a slow-Jellyfin
     * timeout; a caller that can't degrade should surface 503 with Retry-After (FR-182-8) instead of
     * blocking the connection; a BACKGROUND caller (inside `runPipelineStepPool`/`runScan`) should treat
     * it as an ordinary per-item failure — it already does, since it's just another `Throwable` there.
     *
     * Uses `tryAcquire()` polling rather than wrapping `Semaphore.acquire()` in `withTimeoutOrNull` —
     * the latter has a real permit-leak hazard (a cancellation racing the exact instant `acquire()`
     * returns can throw before the permit is ever used or released). Polling is atomic per attempt, so
     * there is no such window; the cost is up to [POLL_INTERVAL_MS] of extra latency once contended,
     * which is immaterial next to either timeout.
     */
    suspend fun <T> withPermit(block: suspend () -> T): T =
        if (currentGateClass() == GateClassKind.BACKGROUND) withBackgroundPermit(block) else withInteractivePermit(block)

    private suspend fun <T> withInteractivePermit(block: suspend () -> T): T {
        if (reserved.tryAcquire()) {
            reservedInFlight.incrementAndGet()
            try {
                return block()
            } finally {
                reservedInFlight.decrementAndGet()
                reserved.release()
            }
        }
        interactiveWaiting.incrementAndGet()
        try {
            if (!acquireWithTimeout(shared, INTERACTIVE_ACQUIRE_TIMEOUT_MS)) {
                interactiveTimeouts.incrementAndGet()
                throw GateTimeoutException("OutboundHttp saturated (interactive, ${INTERACTIVE_ACQUIRE_TIMEOUT_MS}ms)")
            }
        } finally {
            interactiveWaiting.decrementAndGet()
        }
        sharedInFlight.incrementAndGet()
        try {
            return block()
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
                throw GateTimeoutException("OutboundHttp saturated (background, ${BACKGROUND_ACQUIRE_TIMEOUT_MS}ms)")
            }
        } finally {
            backgroundWaiting.decrementAndGet()
        }
        sharedInFlight.incrementAndGet()
        try {
            return block()
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

    /** FR-182-9 — surfaced on `/api/health` and read by the Activity page's saturation banner. */
    fun stats(): GateStats = GateStats(
        totalPermits = TOTAL_PERMITS,
        interactiveReserved = INTERACTIVE_RESERVE,
        sharedCapacity = SHARED,
        reservedInFlight = reservedInFlight.value,
        sharedInFlight = sharedInFlight.value,
        interactiveWaiting = interactiveWaiting.value,
        backgroundWaiting = backgroundWaiting.value,
        interactiveTimeouts = interactiveTimeouts.value,
        backgroundTimeouts = backgroundTimeouts.value,
    )

    /**
     * Phase 129 (FR-OPS1 §B.1) — the one shared client every stateless outbound caller (TMDB,
     * Jellyfin, *arr, artwork/logo downloaders, Ravilo artwork, chart providers) uses instead of
     * constructing its own `HttpClient(Curl)`. Timeouts are the most generous value any current
     * caller needed (artwork/logo downloads: 10s connect, 120s socket/request) — safe for callers
     * that used a shorter timeout (Jellyfin, Sonarr/Radarr, TMDB: 5-10s connect, 30-60s socket),
     * since a longer timeout only waits longer on a truly-hung peer, never a premature failure.
     * The long-lived WebSocket client (`JellyfinSessionBridge`) is NOT a stateless request/response
     * caller and stays on its own dedicated client. (Phase 181 removed the other one,
     * `JellyfinLibraryListener` — it never delivered a usable event on this Jellyfin version.)
     */
    val client: HttpClient by lazy {
        HttpClient(Curl) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 120_000
                requestTimeoutMillis = 120_000
            }
        }
    }
}
