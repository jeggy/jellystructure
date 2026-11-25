package dev.jellystructure.auth

import dev.jellystructure.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * DEB-5540 (FR-3) — the one place a `POST /Library/Refresh` goes when it is not the closing refresh of a
 * `RunTarget.Library` run. On this Jellyfin that call cancels a running library scan and starts a new one, so an admin
 * save must not post it per press.
 *
 * Every [request] marks a refresh as owed. It fires [debounce] after the **last** request, and never while a Jellyfin
 * library scan is running ([scanRunning]): it waits that scan out ([scanPoll] apart), then fires once, and only if a
 * request is still owed. Requests that arrive while it fires are owed again and get their own debounce. A failed fire is
 * logged and dropped — the pipeline's own hourly scan finds the title anyway.
 */
internal class CoalescedLibraryRefresh(
    private val scope: CoroutineScope,
    private val scanRunning: suspend (baseUrl: String, token: String) -> Boolean,
    private val fire: suspend (baseUrl: String, token: String) -> Boolean,
    private val debounce: Duration = 60.seconds,
    private val scanPoll: Duration = 15.seconds,
    /** Milliseconds, monotonic. Injected so a test can run the 60 s on virtual time. */
    private val nowMs: () -> Long,
) {
    private val lock = Mutex()
    private var owed = 0
    private var lastRequestMs = 0L
    private var credentials: Pair<String, String>? = null
    private var draining = false

    suspend fun request(baseUrl: String, token: String) {
        val start = lock.withLock {
            owed++
            lastRequestMs = nowMs()
            credentials = baseUrl to token
            (!draining).also { if (it) draining = true }
        }
        if (start) scope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            while (true) {
                val wait = lock.withLock { lastRequestMs + debounce.inWholeMilliseconds - nowMs() }
                if (wait <= 0) break
                delay(wait)
            }
            val (url, token) = lock.withLock { credentials } ?: return
            while (runCatching { scanRunning(url, token) }.getOrDefault(false)) delay(scanPoll)
            val taken = lock.withLock { owed }
            val ok = runCatching { fire(url, token) }.getOrDefault(false)
            if (!ok) Logger.warn("Jellyfin coalesced library refresh failed (dropped)")
            lock.withLock {
                owed -= taken
                if (owed <= 0) { owed = 0; draining = false; return }
            }
        }
    }
}
