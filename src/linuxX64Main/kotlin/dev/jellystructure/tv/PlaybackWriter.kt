package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.log.Logger
import dev.jellystructure.ops.GateClass
import dev.jellystructure.ops.GateWaitRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    ) { var attempts: Int = 0; var nextAttemptAt: Long = enqueuedAt }

    class Stats(
        val queued: Int,
        val retrying: Int,
        val landed: Long,
        val superseded: Long,
        val abandoned: Long,
        val lastFailure: String?,
    ) {
        fun toJson(): String =
            """{"queued":$queued,"retrying":$retrying,"landed":$landed,"superseded":$superseded,"abandoned":$abandoned,""" +
                """"last_failure":${lastFailure?.let { "\"" + it.replace("\"", "'") + "\"" } ?: "null"}}"""
    }

    private val mutex = Mutex()
    private val pending = LinkedHashMap<PlaybackKey, PendingWrite>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var seq = 0L
    private var landed = 0L
    private var superseded = 0L
    private var abandoned = 0L
    private var lastFailure: String? = null
    private val job: Job = scope.launch(GateClass.INTERACTIVE) { drain() }

    suspend fun enqueueProgress(device: DeviceData, jellyfinId: String, positionMs: Long, isPaused: Boolean) =
        enqueue(Kind.PROGRESS, device, jellyfinId, positionMs, isPaused, null)

    suspend fun enqueueStop(device: DeviceData, jellyfinId: String, positionMs: Long, jellyfinPlaySessionId: String?) =
        enqueue(Kind.STOP, device, jellyfinId, positionMs, false, jellyfinPlaySessionId)

    private suspend fun enqueue(kind: Kind, device: DeviceData, jellyfinId: String, positionMs: Long, isPaused: Boolean, psid: String?) {
        val key = PlaybackKey(device.deviceId, jellyfinId)
        mutex.withLock {
            val existing = pending[key]
            // A stop already waiting must not be overwritten by a straggling progress tick for the same
            // key; anything else is last-position-wins, and the write it replaces was never "lost".
            if (existing != null && existing.kind == Kind.STOP && kind == Kind.PROGRESS) return
            if (existing != null) superseded++
            pending[key] = PendingWrite(kind, device, jellyfinId, positionMs, isPaused, psid ?: existing?.jellyfinPlaySessionId, clock(), ++seq)
        }
        signal.trySend(Unit)
    }

    /** True once nothing is waiting — for tests and for a graceful shutdown. */
    suspend fun isIdle(): Boolean = mutex.withLock { pending.isEmpty() }

    suspend fun stats(): Stats = mutex.withLock {
        Stats(pending.size, pending.values.count { it.attempts > 0 }, landed, superseded, abandoned, lastFailure)
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
        val result = runCatching { withContext(recorder) { if (w.kind == Kind.PROGRESS) sink.progress(w) else sink.stop(w) } }
        val total = t0.elapsedNow().inWholeMilliseconds
        val wait = recorder.waitedMs
        val request = (total - wait).coerceAtLeast(0)
        val e = result.exceptionOrNull()
        if (e is CancellationException) throw e   // 210's rule: never swallow the writer's own cancellation
        val ok = result.getOrDefault(false)
        val stillCurrent = mutex.withLock { pending[PlaybackKey(w.device.deviceId, w.jellyfinId)] === w }
        if (ok) {
            mutex.withLock { if (pending[PlaybackKey(w.device.deviceId, w.jellyfinId)] === w) pending.remove(PlaybackKey(w.device.deviceId, w.jellyfinId)); landed++ }
            if (w.attempts > 1) Logger.info("${w.kind.name.lowercase()} write for ${w.jellyfinId} device=${w.device.deviceId} landed on attempt ${w.attempts} (wait ${wait} ms, request ${request} ms)", "tv")
            return
        }
        val reason = e?.message ?: "not acknowledged"
        lastFailure = "${w.kind.name.lowercase()} ${w.jellyfinId} device=${w.device.deviceId}: $reason"
        if (!stillCurrent) {
            // A newer tick replaced this one while it was in flight — dropped silently, not lost.
            return
        }
        val age = clock() - w.enqueuedAt
        if (w.kind == Kind.STOP && age > STOP_MAX_AGE_MS) {
            mutex.withLock { pending.remove(PlaybackKey(w.device.deviceId, w.jellyfinId)); abandoned++ }
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
    }
}

/** What a write does when it runs. Injected so the writer's retry rules are unit-testable without Jellyfin. */
interface PlaybackSink {
    /** Returns true when Jellyfin acknowledged; false or throw ⇒ retried. */
    suspend fun progress(w: PlaybackWriter.PendingWrite): Boolean
    suspend fun stop(w: PlaybackWriter.PendingWrite): Boolean
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun nowMonoMs(): Long = platform.posix.time(null) * 1000L
