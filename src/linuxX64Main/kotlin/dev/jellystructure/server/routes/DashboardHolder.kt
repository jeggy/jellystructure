package dev.jellystructure.server.routes

import dev.jellystructure.model.DashboardDto
import dev.jellystructure.model.DashboardSince
import dev.jellystructure.model.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference
import kotlin.time.TimeSource

/** One finished build: the overview every admin sees, and the kind of each title (what *Since your last visit* needs, without walking the library per request). */
class HeldOverview(val dto: DashboardDto, val kinds: Map<String, MediaKind>)

/**
 * DEB-2498 (FR-285-15, FR-285-16, FR-285-17) — the Dashboard overview is built here, in the background, by one owner,
 * and held. A request reads [current]; it never builds and never waits on a build.
 *
 * One worker coroutine owns every build, so a build never runs twice at once. [request] drops a token on a conflated
 * channel: any number of triggers that land while a build runs leave one token, which is **one** follow-up build.
 * With no trigger the worker rebuilds every [maxAgeMs]. A build that throws keeps the last overview and is tried again
 * on the next trigger or interval — the page never loses what it already had.
 */
class DashboardHolder(
    scope: CoroutineScope,
    private val build: suspend () -> HeldOverview,
    private val onBuilt: suspend (HeldOverview) -> Unit = {},
    private val maxAgeMs: Long = 5 * 60_000L,
    /** A write storm (a scan) triggers constantly; builds start no closer together than this. A row action skips the wait. */
    private val minGapMs: Long = 2_000L,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val held = AtomicReference<HeldOverview?>(null)
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private val requested = AtomicLong(0)
    private val urgent = AtomicInt(0)
    private val completed = MutableStateFlow(0L)
    private val builds = AtomicInt(0)

    /** `null` until the first build after boot has completed (FR-285-17). */
    val current: HeldOverview? get() = held.value

    /** How many builds have run — for tests and the log. */
    val buildCount: Int get() = builds.value

    init {
        request()   // boot
        scope.launch { loop() }
    }

    /** Ask for a build. Returns the ticket [awaitBuildFor] waits on. */
    fun request(urgent: Boolean = false): Long {
        if (urgent) this.urgent.value = 1
        val ticket = requested.incrementAndGet()
        triggers.trySend(Unit)
        return ticket
    }

    /** FR-285-18 — a row action: ask for a build and wait for one that started after the ask, at most [timeoutMs]. True if it landed in time. */
    suspend fun requestAndWait(timeoutMs: Long = 10_000L): Boolean = awaitBuildFor(request(urgent = true), timeoutMs)

    suspend fun awaitBuildFor(ticket: Long, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { completed.first { it >= ticket }; true } ?: false

    private suspend fun loop() {
        var lastStart: TimeSource.Monotonic.ValueTimeMark? = null
        while (true) {
            withTimeoutOrNull(maxAgeMs) { triggers.receive() }   // a trigger, or the interval running out
            lastStart?.let { mark ->
                if (urgent.getAndSet(0) == 0) {
                    val wait = minGapMs - mark.elapsedNow().inWholeMilliseconds
                    if (wait > 0) delay(wait)
                }
            }
            urgent.value = 0
            lastStart = TimeSource.Monotonic.markNow()
            // Read after the trigger is taken: every request made before this line is served by this build,
            // any made after it leaves a token for the next one.
            val ticket = requested.value
            try {
                val result = build()
                held.value = result
                builds.incrementAndGet()
                completed.value = ticket
                runCatching { onBuilt(result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onFailure(e)
                completed.value = ticket   // a waiter is released; what it gets is the last overview it already had
            }
        }
    }
}

/** What `GET /api/dashboard` answers: the held overview with the caller's own *since*, or the not-built-yet shape. */
internal fun servedOverview(held: HeldOverview?, since: (Map<String, MediaKind>) -> DashboardSince?): DashboardDto =
    if (held == null) DashboardDto(built = false) else held.dto.copy(since = since(held.kinds))
