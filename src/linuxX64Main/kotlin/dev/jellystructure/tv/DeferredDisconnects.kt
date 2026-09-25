package dev.jellystructure.tv

import dev.jellystructure.ops.SpinLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 256 (FR-256-4, dev review item 3) — a reconnect is not a new Jellyfin session. An events-socket
 * close schedules the device's bridge `disconnect` [graceMs] out (one cancellable job per device); a
 * reconnect inside the grace cancels it, and since the bridge's `connect` is already idempotent the
 * bridge simply carries on — no `SessionEnded`/`SessionStarted` pair, no second `postCapabilities`.
 * Pure scheduling: what [action] does is the bridge's business, which is what makes this testable.
 */
class DeferredDisconnects(
    private val scope: CoroutineScope,
    private val graceMs: Long = 90_000L,
    private val action: (deviceId: String) -> Unit,
) {
    private val lock = SpinLock()
    private val pending = HashMap<String, Job>()

    /** Schedules [deviceId]'s disconnect; a second schedule replaces the first (the clock restarts). */
    fun schedule(deviceId: String) {
        val job = scope.launch {
            delay(graceMs)
            val stillMine = lock.withLock { pending[deviceId]?.let { if (it === this.coroutineContext[Job]) { pending.remove(deviceId); true } else false } ?: false }
            if (stillMine) action(deviceId)
        }
        val previous = lock.withLock { pending.put(deviceId, job) }
        previous?.cancel()
    }

    /** True when a disconnect was pending and is now cancelled — the device came back in time. */
    fun cancel(deviceId: String): Boolean {
        val job = lock.withLock { pending.remove(deviceId) } ?: return false
        job.cancel()
        return true
    }

    fun isPending(deviceId: String): Boolean = lock.withLock { pending.containsKey(deviceId) }
}
