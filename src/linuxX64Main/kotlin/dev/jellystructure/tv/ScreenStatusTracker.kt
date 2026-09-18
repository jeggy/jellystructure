package dev.jellystructure.tv

import dev.jellystructure.shared.tv.ScreenStatus
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

/**
 * Phase 236 (FR-236-5) — the last status a screen reported, kept in memory only (dev review item 8b:
 * lost on a backend restart; R264 FR-R264-6's reconnect re-post is what makes this durable in practice,
 * not this store — display state, never written to Jellyfin). [clock] is injectable for the same
 * testability reason [PlaybackTracker] takes one.
 */
class ScreenStatusTracker(private val clock: () -> Long = ::nowMs) {
    data class Entry(val status: ScreenStatus, val reportedAt: Long)

    private val mutex = Mutex()
    private val byDevice = HashMap<String, Entry>()

    suspend fun update(deviceId: String, status: ScreenStatus): Entry = mutex.withLock {
        Entry(status, clock()).also { byDevice[deviceId] = it }
    }

    suspend fun get(deviceId: String): Entry? = mutex.withLock { byDevice[deviceId] }

    /** FR-236-8 — the stop watchdog reap clears status rather than leaving a stale "playing" snapshot
     *  behind; returns a final [ScreenStatus] with `loaded=false` for the caller to fan out to
     *  subscribers (dev review item 8b: "subscribers get a final screen_status with loaded=false"), or
     *  null when nothing was tracked for [deviceId]. */
    suspend fun clear(deviceId: String): ScreenStatus? = mutex.withLock {
        byDevice.remove(deviceId)?.status?.copy(loaded = false, playing = false, buffering = false)
    }
}

/** Singleton, same shape as [playbackTracker] — one process-wide register, read by the remote-devices
 *  list and written by every `/api/tv/playback/status` post. */
internal val screenStatusTracker = ScreenStatusTracker()

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
