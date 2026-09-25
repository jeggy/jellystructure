package dev.jellystructure.shared.tv

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * R305 — [TvApiClient]'s one funnel for REST calls: each runs in a coroutine of its own and the caller
 * awaits it, so a caller that is cancelled stops waiting at once while the request is cancelled from a
 * background thread.
 *
 * Why it has to be this way (FR-R305-1): Ktor attaches a request's job to the CALLER's job, so the
 * caller's cancellation cancels the request synchronously on the cancelling thread, and on Android the
 * `Android` engine's response channel closes its socket in that handler. A Compose effect is cancelled on
 * the main thread; closing a TLS socket there is `NetworkOnMainThreadException`, which Android turns into
 * a dead process — seen on the Pixel 9 relaunching after a process death, 300 ms after start, from inside
 * Home's feed request (see the spec for the trace).
 *
 * FR-R305-2 — the request is still cancelled, never left to run for nobody. FR-R305-3 — its result and
 * its exceptions reach the caller exactly as a direct call's would: `await` rethrows them.
 *
 * Only the four calls [TvApiClient] makes, with the same shape as Ktor's own extensions, so every call
 * site reads as it did.
 */
internal class DetachedHttp(private val client: HttpClient, private val calls: DetachedCalls = DetachedCalls()) {
    suspend fun get(urlString: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse = calls.run { client.get(urlString, block) }
    suspend fun post(urlString: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse = calls.run { client.post(urlString, block) }
    suspend fun put(urlString: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse = calls.run { client.put(urlString, block) }
    suspend fun delete(urlString: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse = calls.run { client.delete(urlString, block) }
}

/** R305 — the detaching itself, apart from HTTP so it can be tested without an engine. */
internal class DetachedCalls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun <T> run(block: suspend () -> T): T {
        val request = scope.async { block() }
        try {
            return request.await()
        } catch (e: CancellationException) {
            // The CALLER was cancelled (a cancelled request surfaces as its own exception from await,
            // and is already finished, so cancelling it again is a no-op). Cancel it from here, on a
            // background thread, never synchronously on the caller's.
            if (request.isActive) scope.launch { request.cancel(e) }
            throw e
        }
    }
}
