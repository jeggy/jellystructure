package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.shared.tv.TvApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** What asking the server for a song's (or part's) stream came to. */
internal sealed interface StartResolve<out T> {
    /** [failures] earlier attempts found the server unreachable; [waitedMs] is how long the viewer waited in all. */
    data class Resolved<T>(val value: T, val failures: Int, val waitedMs: Long) : StartResolve<T>
    /** [network] — the server was never reached (R322's sheet after the window); false — it answered with an error (R237: sheet at once). */
    data class Failed(val error: Throwable, val network: Boolean, val failures: Int, val waitedMs: Long) : StartResolve<Nothing>
}

/**
 * DEB-3599 (FR-3, FR-5) — a start that cannot reach the server is a network failure, and it gets the restart a player's
 * network failure gets, by itself, without a click. Asking is repeated every [retryMs] until the server answers or
 * [windowMs] have passed since the start; the repeats are one restart. A server that answers with an error (an HTTP
 * status) is not retried: it cannot succeed by waiting (R237). Cancelling the start (a skip, another song) is not a failure.
 *
 * [onUnreachable] is called for the first failed attempt, so the log has its line while the viewer waits.
 */
internal suspend fun <T> resolveStart(
    windowMs: Long = 45_000L,
    retryMs: Long = 3_000L,
    now: () -> Long = System::currentTimeMillis,
    pause: suspend (Long) -> Unit = { delay(it) },
    onUnreachable: (Throwable) -> Unit = {},
    ask: suspend () -> T?,
): StartResolve<T> {
    val began = now()
    var failures = 0
    while (true) {
        val error: Throwable = try {
            val answer = ask()
            if (answer != null) return StartResolve.Resolved(answer, failures, now() - began)
            IllegalStateException("no client")
        } catch (e: CancellationException) {
            throw e
        } catch (e: TvApiError.Http) {
            return StartResolve.Failed(e, network = false, failures = failures, waitedMs = now() - began)
        } catch (e: Throwable) {
            e
        }
        if (failures == 0) onUnreachable(error)
        failures++
        val waited = now() - began
        if (waited + retryMs > windowMs) return StartResolve.Failed(error, network = true, failures = failures, waitedMs = waited)
        pause(retryMs)
    }
}
