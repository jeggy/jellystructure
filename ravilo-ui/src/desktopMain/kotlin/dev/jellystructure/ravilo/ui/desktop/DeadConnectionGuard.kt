package dev.jellystructure.ravilo.ui.desktop

import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import java.net.SocketException

/**
 * DEB-3336 (R383, FR-R383-2, FR-R383-4) — a pooled HTTP/2 connection whose socket can no longer be written to ends with
 * the call that found it out, instead of being retried on for ever.
 *
 * What it stops: OkHttp keeps an HTTP/2 connection in the pool when a single call fails with an `IOException`, because
 * one stream failing says little about the others. When the write itself fails and the read side stays quiet — macOS
 * refusing a Local Network send on a socket it had already allowed, which the server saw as `NoRouteToHostException` —
 * nothing ever marks the connection bad. `RetryAndFollowUpInterceptor` then retries the call on the same connection with
 * no delay, and every try opens a stream (an `Http2Stream`, two `StreamTimeout`s, three `okio.Buffer`s) that the failed
 * write leaves in the connection's map. One `execute()` fills a 1 GB heap in under a minute, which is the Mac's
 * `-Xmx1g`, and the call never returns.
 *
 * What it does: a [SocketException] (a refused or broken send, a reset, an unreachable host) escaping an HTTP/2 exchange
 * closes that connection's socket. The connection's reader fails, closes every stream it holds and leaves the pool, so
 * the retry opens a new connection or the call fails with the exception at once. Registered as a *network* interceptor,
 * which runs once per try, inside the retry loop. HTTP/1.1 needs nothing: OkHttp already retires those connections.
 */
internal object DeadConnectionGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        try {
            chain.proceed(chain.request())
        } catch (e: SocketException) {
            drop(chain.connection())
            throw e
        }

    private fun drop(connection: Connection?) {
        val c = connection ?: return
        if (c.protocol() != Protocol.HTTP_2 && c.protocol() != Protocol.H2_PRIOR_KNOWLEDGE) return
        runCatching { c.socket().close() }
    }
}
