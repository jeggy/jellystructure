package dev.jellystructure.ops

import dev.jellystructure.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 310 (FR-310-4) — `withTimeoutOrNull` that never lets a timeout leak out.
 *
 * kotlinx.coroutines 1.11's `withTimeoutOrNull` rethrows a `TimeoutCancellationException` that is not its own
 * (`e.coroutine !== coroutine`). On 2026-10-06 one such — handed across requests by Ktor's Curl engine — escaped the
 * Continue Watching build's `withTimeoutOrNull(6000)` instead of becoming null, and the same cause ended the playback
 * writer. This returns null on its own timeout **and** on any cancellation that escapes from inside while the caller's
 * own coroutine is still running; only the caller's own cancellation propagates.
 */
suspend fun <T> boundedOrNull(timeoutMs: Long, what: String = "a bounded call", block: suspend CoroutineScope.() -> T): T? = try {
    withTimeoutOrNull(timeoutMs, block)
} catch (e: CancellationException) {
    if (!currentCoroutineContext().isActive) throw e
    Logger.warn("$what: a foreign cancellation escaped (${e::class.simpleName}: ${e.message}) — treated as a timeout (310)", "ops")
    null
}
