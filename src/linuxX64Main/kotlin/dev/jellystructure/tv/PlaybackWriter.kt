package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.log.Logger
import dev.jellystructure.ops.GateClass
import dev.jellystructure.ops.GateWaitRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * Phase 219 (FR-219-2/3) — a playback write is queued and retried, never abandoned.
 *
 * Before this phase `POST /tv/playback/progress` and `/stop` posted to Jellyfin inline, inside the
 * request handler's own coroutine, wrapped in a `runCatching` that swallowed the handler's own
 * cancellation — so a slow Jellyfin (or a saturated outbound pool) made the viewer's position vanish
 * with a "Timed out waiting for 6000 ms" line and nothing else. 24 hours of production logs showed
 * eleven of those.
 *
 * Now: the route enqueues (last-position-wins per `(device, item)`) and responds at once; this writer
 * drains the queue on its own scope under `GateClass.INTERACTIVE` (the reserved permits — a write never
 * waits behind background work), measures the permit wait and the Jellyfin round trip separately and
 * names both on failure (FR-219-1), and retries with jittered exponential backoff until Jellyfin acks
 * or the write is superseded. A progress tick superseded by a newer tick is dropped silently — that is
 * not a lost write. A STOP is the write that must land: it is retried past the client's disconnect,
 * bounded only by [STOP_MAX_AGE_MS] (the watchdog's own horizon), and only then logged as abandoned.
 */
class PlaybackWriter(
    private val scope: CoroutineScope,
    private val sink: PlaybackSink,
    private val clock: () -> Long = ::nowMonoMs,
    private val baseBackoffMs: Long = 1_000L,
    private val maxBackoffMs: Long = 30_000L,
    /** Phase 310 (FR-310-2) — no single attempt may hold the line longer than this; past it the attempt is a failure. */
    private val attemptDeadlineMs: Long = 30_000L,
    /** Phase 310 (FR-310-2) — the after-stop work's own deadline, off the writer's line. */
    private val followUpDeadlineMs: Long = 60_000L,
    /** Phase 310 (owner decision 2) — queued STOPs kept in SQLite until Jellyfin acknowledges them; null in tests. */
    private val outbox: PlaybackOutbox? = null,
    /** Phase 310 (FR-310-3) — the supervisor's pause before it restarts a drain loop that ended. */
    private val restartDelayMs: Long = 1_000L,
) {
    enum class Kind { PROGRESS, STOP }

    class PendingWrite(
        val kind: Kind,
        val device: DeviceData,
        val jellyfinId: String,
        val positionMs: Long,
        val isPaused: Boolean,
        val jellyfinPlaySessionId: String?,
        val enqueuedAt: Long,
        val seq: Long,
        /** R343 — this stop ends a *Start over* session that cleared its series: once the stop has landed the
         *  sink writes the episode back as unwatched at [positionMs] (Jellyfin's stop restores the stale flag). */
        val startOverUnplayed: Boolean = false,
        /** R357 (FR-R357-2) — a progress report's player volume (0–100) and mute; null when the report had none. */
        val volumePercent: Int? = null,
        val muted: Boolean? = null,
        /** R375 (FR-R375-6) — the episode's `LastPlayedDate` to write back once this stop has landed (a shuffled play,
         *  or a replay of a watched episode that did not finish), so it never becomes the last one played. */
        val restoreLastPlayed: String? = null,
        /** Phase 312 (FR-312-1) — who queued this STOP (`user`, `watchdog`, `abandoned`, …), logged when queued and landed. */
        val reason: String = "user",
        /** Phase 310 (dev review item 7) / 312 (FR-312-3) — the one user-data write the landed stop is followed by. */
        val userData: StopUserData? = null,
    ) { var attempts: Int = 0; var nextAttemptAt: Long = enqueuedAt }

    class Stats(
        val queued: Int,
        val retrying: Int,
        val landed: Long,
        val superseded: Long,
        val abandoned: Long,
        val lastFailure: String?,
        /** Phase 310 (FR-310-3) — the drain loop is running. */
        val alive: Boolean = true,
        /** Phase 310 (FR-310-3) — how many times the supervisor restarted a drain loop that ended. */
        val restarts: Long = 0,
        /** Phase 310 (FR-310-3) — the age of the oldest queued write, in whole seconds (0 with nothing queued). */
        val oldestWaitingS: Long = 0,
    ) {
        fun toJson(): String =
            """{"queued":$queued,"retrying":$retrying,"landed":$landed,"superseded":$superseded,"abandoned":$abandoned,""" +
                """"last_failure":${lastFailure?.let { "\"" + it.replace("\"", "'") + "\"" } ?: "null"},""" +
                """"alive":$alive,"restarts":$restarts,"oldest_waiting_s":$oldestWaitingS}"""

        /** Phase 310 (FR-310-3) — the Dashboard's critical row fires on exactly this. */
        val stalled: Boolean get() = !alive || oldestWaitingS > STALLED_AFTER_S
    }

    private val mutex = Mutex()
    private val pending = LinkedHashMap<PlaybackKey, PendingWrite>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var seq = 0L
    private var landed = 0L
    private var superseded = 0L
    private var abandoned = 0L
    private var lastFailure: String? = null
    private var restarts = 0L
    private val draining = kotlin.concurrent.AtomicInt(0)

    /**
     * Phase 310 (FR-310-3) — the drain loop runs under a supervisor. Before this phase it was one bare `launch`, and on
     * 2026-10-06 a foreign `CancellationException` rethrown out of [attempt] ended it as a *cancellation*: no log, no
     * crash, no restart — nothing reached Jellyfin for seven hours. Now anything that ends [drain] other than the
     * writer's own cancellation is logged at ERROR, counted, and the loop restarted.
     */
    private val job: Job = scope.launch(GateClass.INTERACTIVE) {
        outbox?.let { o -> runCatching { restoreFrom(o) }.onFailure { Logger.warn("playback writer: restoring queued stops failed: ${it.message}", "tv") } }
        while (true) {
            val ended = try {
                draining.value = 1
                drain()
                null
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                e
            } catch (e: Throwable) {
                e
            } finally {
                draining.value = 0
            }
            mutex.withLock { restarts++ }
            Logger.error("playback writer stopped (${ended?.let { it::class.simpleName + ": " + it.message } ?: "drain returned"}) — restarted", "tv")
            delay(restartDelayMs)
        }
    }

    suspend fun enqueueProgress(device: DeviceData, jellyfinId: String, positionMs: Long, isPaused: Boolean, volumePercent: Int? = null, muted: Boolean? = null) =
        enqueue(Kind.PROGRESS, device, jellyfinId, positionMs, isPaused, null, volumePercent = volumePercent, muted = muted)

    suspend fun enqueueStop(device: DeviceData, jellyfinId: String, positionMs: Long, jellyfinPlaySessionId: String?, startOverUnplayed: Boolean = false,
                            restoreLastPlayed: String? = null, reason: String = "user", userData: StopUserData? = null) {
        Logger.info("stop write: item=$jellyfinId device=${device.deviceId} at=${positionMs}ms reason=$reason queued (312)", "tv")
        enqueue(Kind.STOP, device, jellyfinId, positionMs, false, jellyfinPlaySessionId, startOverUnplayed, restoreLastPlayed = restoreLastPlayed,
            reason = reason, userData = userData)
    }

    private suspend fun enqueue(kind: Kind, device: DeviceData, jellyfinId: String, positionMs: Long, isPaused: Boolean, psid: String?, startOverUnplayed: Boolean = false,
                                volumePercent: Int? = null, muted: Boolean? = null, restoreLastPlayed: String? = null,
                                reason: String = "user", userData: StopUserData? = null) {
        val key = PlaybackKey(device.deviceId, jellyfinId)
        mutex.withLock {
            val existing = pending[key]
            // A stop already waiting must not be overwritten by a straggling progress tick for the same
            // key; anything else is last-position-wins, and the write it replaces was never "lost".
            if (existing != null && existing.kind == Kind.STOP && kind == Kind.PROGRESS) return
            if (existing != null) superseded++
            // R357 (FR-R357-2) — a coalesced tick keeps the latest volume: its own, else the one it replaces (a
            // server-made tick, e.g. R343's write-now, carries none and must not drop the player's).
            val keep = existing?.takeIf { it.kind == Kind.PROGRESS && kind == Kind.PROGRESS }
            pending[key] = PendingWrite(kind, device, jellyfinId, positionMs, isPaused, psid ?: existing?.jellyfinPlaySessionId, clock(), ++seq,
                startOverUnplayed = startOverUnplayed || (existing?.kind == Kind.STOP && existing.startOverUnplayed),
                volumePercent = volumePercent ?: keep?.volumePercent, muted = muted ?: keep?.muted,
                // R375 — a STOP merged over a STOP keeps the date it carried unless the new one names its own.
                restoreLastPlayed = restoreLastPlayed ?: existing?.takeIf { it.kind == Kind.STOP }?.restoreLastPlayed,
                reason = reason,
                // 310 — a STOP merged over a STOP keeps the user data it carried unless the new one brings its own.
                userData = userData ?: existing?.takeIf { it.kind == Kind.STOP && kind == Kind.STOP }?.userData)
        }
        if (kind == Kind.STOP) outbox?.let { o ->
            val w = mutex.withLock { pending[key] }
            if (w != null && w.kind == Kind.STOP) runCatching { o.save(w) }.onFailure { Logger.warn("playback writer: could not persist a stop for $jellyfinId: ${it.message}", "tv") }
        }
        signal.trySend(Unit)
    }

    /** Phase 310 (owner decision 2) — the stops a previous run queued and Jellyfin never acknowledged, queued again. */
    private suspend fun restoreFrom(o: PlaybackOutbox) {
        val saved = o.loadAll()
        if (saved.isEmpty()) return
        mutex.withLock {
            for (w in saved) {
                val key = PlaybackKey(w.device.deviceId, w.jellyfinId)
                if (pending[key] == null) pending[key] = PendingWrite(Kind.STOP, w.device, w.jellyfinId, w.positionMs, false, w.jellyfinPlaySessionId,
                    clock(), ++seq, startOverUnplayed = w.startOverUnplayed, restoreLastPlayed = w.restoreLastPlayed, reason = w.reason, userData = w.userData)
            }
        }
        Logger.info("playback writer: ${saved.size} queued stop(s) from before the restart queued again (310)", "tv")
        signal.trySend(Unit)
    }

    /** True once nothing is waiting — for tests and for a graceful shutdown. */
    suspend fun isIdle(): Boolean = mutex.withLock { pending.isEmpty() }

    suspend fun stats(): Stats = mutex.withLock {
        val now = clock()
        Stats(pending.size, pending.values.count { it.attempts > 0 }, landed, superseded, abandoned, lastFailure,
            alive = job.isActive && draining.value == 1, restarts = restarts,
            oldestWaitingS = pending.values.minOfOrNull { it.enqueuedAt }?.let { ((now - it) / 1000).coerceAtLeast(0) } ?: 0)
    }

    private suspend fun drain() {
        while (true) {
            val now = clock()
            val next = mutex.withLock { pending.values.filter { it.nextAttemptAt <= now }.minByOrNull { it.seq } }
            if (next == null) {
                val soonest = mutex.withLock { pending.values.minOfOrNull { it.nextAttemptAt } }
                if (soonest == null) signal.receive() else {
                    // Wake on a new write or when the soonest retry is due, whichever comes first.
                    val wait = (soonest - clock()).coerceIn(10L, maxBackoffMs)
                    kotlinx.coroutines.withTimeoutOrNull(wait) { signal.receive() }
                }
                continue
            }
            attempt(next)
        }
    }

    private suspend fun attempt(w: PendingWrite) {
        w.attempts++
        val recorder = GateWaitRecorder()
        val t0 = TimeSource.Monotonic.markNow()
        val result = runCatching {
            withTimeout(attemptDeadlineMs) { withContext(recorder) { if (w.kind == Kind.PROGRESS) sink.progress(w) else sink.stop(w) } }
        }
        val total = t0.elapsedNow().inWholeMilliseconds
        val wait = recorder.waitedMs
        val request = (total - wait).coerceAtLeast(0)
        val e = result.exceptionOrNull()
        // 210's rule, narrowed by phase 310 (FR-310-1): only the writer's OWN cancellation stops it. Any other
        // CancellationException — this attempt's own deadline, or a foreign one (Ktor's Curl engine handed one request's
        // cancellation to another on 2026-10-06) — is a failure of this write: logged and retried below.
        if (e is CancellationException && !currentCoroutineContext().isActive) throw e
        val ok = result.getOrDefault(false)
        val key = PlaybackKey(w.device.deviceId, w.jellyfinId)
        val stillCurrent = mutex.withLock { pending[key] === w }
        if (ok) {
            val removed = mutex.withLock { (pending[key] === w).also { if (it) pending.remove(key) }.also { landed++ } }
            if (w.kind == Kind.STOP) {
                Logger.info("stop write: item=${w.jellyfinId} device=${w.device.deviceId} at=${w.positionMs}ms reason=${w.reason} landed (312)", "tv")
                // The stop has landed: forget the persisted copy (unless a newer stop already replaced it), and run the
                // after-stop work off the writer's line, in its order, under its own deadline (FR-310-2).
                if (removed) outbox?.let { o -> runCatching { o.remove(w.device.deviceId, w.jellyfinId) } }
                scope.launch {
                    val r = runCatching { withTimeout(followUpDeadlineMs) { sink.afterStop(w) } }
                    r.exceptionOrNull()?.let { fe ->
                        if (fe is CancellationException && !currentCoroutineContext().isActive) throw fe
                        Logger.warn("after-stop work for ${w.jellyfinId} device=${w.device.deviceId} failed: ${fe::class.simpleName}: ${fe.message}", "tv")
                    }
                }
            }
            if (w.attempts > 1) Logger.info("${w.kind.name.lowercase()} write for ${w.jellyfinId} device=${w.device.deviceId} landed on attempt ${w.attempts} (wait ${wait} ms, request ${request} ms)", "tv")
            return
        }
        val reason = e?.let { "${it::class.simpleName}: ${it.message}" } ?: "not acknowledged"
        lastFailure = "${w.kind.name.lowercase()} ${w.jellyfinId} device=${w.device.deviceId}: $reason"
        if (!stillCurrent) {
            // A newer tick replaced this one while it was in flight — dropped silently, not lost.
            return
        }
        val age = clock() - w.enqueuedAt
        if (w.kind == Kind.STOP && age > STOP_MAX_AGE_MS) {
            mutex.withLock { pending.remove(PlaybackKey(w.device.deviceId, w.jellyfinId)); abandoned++ }
            outbox?.let { o -> runCatching { o.remove(w.device.deviceId, w.jellyfinId) } }
            Logger.error("stop write for ${w.jellyfinId} device=${w.device.deviceId} abandoned after ${w.attempts} attempts over ${age / 1000} s — last: $reason (wait ${wait} ms, request ${request} ms)", "tv")
            return
        }
        val backoff = (baseBackoffMs shl (w.attempts - 1).coerceAtMost(5)).coerceAtMost(maxBackoffMs)
        val jittered = backoff + Random.nextLong(0, (backoff / 4).coerceAtLeast(1))
        w.nextAttemptAt = clock() + jittered
        // FR-219-1 — the deadline is named: which side of the call took the time, and why it failed.
        val cause = if (recorder.timedOut) "outbound pool busy — no permit after ${wait} ms" else "$reason (wait ${wait} ms, request ${request} ms)"
        Logger.warn("${w.kind.name.lowercase()} write for ${w.jellyfinId} device=${w.device.deviceId} failed on attempt ${w.attempts}: $cause; retrying in ${jittered} ms", "tv")
    }

    companion object {
        /** A stop that cannot land in ten minutes is abandoned loudly — the 180 watchdog's own horizon. */
        const val STOP_MAX_AGE_MS = 10 * 60_000L

        /** Phase 310 (FR-310-3) — a write waiting longer than this makes the Dashboard's critical row show. */
        const val STALLED_AFTER_S = 120L
    }
}

/**
 * Phase 310 (owner decision 2) — where queued STOPs wait in SQLite until Jellyfin has them, so a restart or a redeploy
 * never drops one (the 15 writes queued at the 2026-10-07 restart were in memory and are gone). One row per
 * (device, item), last stop wins, as in the writer. Progress ticks are not kept: the next tick supersedes them.
 */
interface PlaybackOutbox {
    suspend fun save(w: PlaybackWriter.PendingWrite)
    suspend fun remove(deviceId: String, jellyfinId: String)
    /** Every saved stop, with its device resolved; a stop whose device is gone is dropped. */
    suspend fun loadAll(): List<PlaybackWriter.PendingWrite>
}

/** What a write does when it runs. Injected so the writer's retry rules are unit-testable without Jellyfin. */
interface PlaybackSink {
    /** Returns true when Jellyfin acknowledged; false or throw ⇒ retried. */
    suspend fun progress(w: PlaybackWriter.PendingWrite): Boolean
    /** The stop itself, and nothing else (phase 310: the after-stop work is [afterStop]). */
    suspend fun stop(w: PlaybackWriter.PendingWrite): Boolean
    /** Phase 310 (FR-310-2) — what a landed stop starts, run off the writer's line; a throw is logged, never retried. */
    suspend fun afterStop(w: PlaybackWriter.PendingWrite) {}
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun nowMonoMs(): Long = platform.posix.time(null) * 1000L
