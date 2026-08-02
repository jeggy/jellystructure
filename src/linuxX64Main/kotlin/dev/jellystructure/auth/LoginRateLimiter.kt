package dev.jellystructure.auth

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
 * Security fix (2026-08-02 review, finding H4) — there was NO rate limiting, lockout, or delay
 * anywhere on any authentication path (`/api/auth/login`, `/api/tv/login`). Each login is a
 * synchronous proxy to Jellyfin's own AuthenticateByName, so an internet-exposed instance was
 * unbounded online password guessing against every Jellyfin account — and used jellystructure as an
 * anonymising amplifier in front of Jellyfin's own (possibly rate-limited) login.
 *
 * `ktor-server-rate-limit` isn't in this project's dependency set (and Kotlin/Native artifact
 * availability for it is unverified) — this is a small, dependency-free, fixed-window limiter in the
 * same hand-rolled style as [dev.jellystructure.ops.FdWatchdog]/`ProcessGate`, sized for a login
 * endpoint (low request volume, no perf pressure) rather than the hot request path.
 *
 * Keyed by client IP. **Caveat**: without a reverse proxy in front that sanitises/sets a real client
 * IP header, the fallback is the raw TCP peer address — which is exactly right for a direct-exposed
 * instance, but becomes "the proxy's IP for everyone" once a reverse proxy sits in front. See the
 * deployment guide (specs/research-reports/) for the recommended proxy configuration.
 */
class LoginRateLimiter(
    private val maxAttempts: Int = 5,
    private val windowMs: Long = 60_000L,
) {
    private data class Window(var count: Int, var windowStart: Long)

    private val mutex = Mutex()
    private val attempts = HashMap<String, Window>()

    // Bounded cleanup — this map is only ever touched by login-rate calls (low volume), but an
    // internet-facing instance sees attempts from many distinct IPs over time; sweep opportunistically
    // rather than let it grow forever.
    private var lastSweep = 0L
    private val sweepIntervalMs = 5 * 60_000L

    /** Returns true if [key] (typically the client IP) is currently allowed to attempt a login. */
    suspend fun tryAcquire(key: String): Boolean = mutex.withLock {
        val now = nowMs()
        if (now - lastSweep > sweepIntervalMs) {
            attempts.entries.removeAll { now - it.value.windowStart > windowMs }
            lastSweep = now
        }
        val w = attempts.getOrPut(key) { Window(0, now) }
        if (now - w.windowStart > windowMs) {
            w.count = 0
            w.windowStart = now
        }
        if (w.count >= maxAttempts) return@withLock false
        w.count++
        true
    }

    /** Best-effort client IP: prefer a proxy-set header if present, else the raw TCP peer. See the
     *  class doc — this is meaningful rate limiting only once a reverse proxy is correctly configured
     *  to overwrite (not append to) X-Forwarded-For with the real client address. */
    companion object {
        fun clientKey(remoteHost: String, forwardedFor: String?): String =
            forwardedFor?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: remoteHost
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
