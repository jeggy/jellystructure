package dev.jellystructure.ravilo.ui.desktop

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.connection.asBufferedSocket
import okhttp3.internal.http2.Header
import okhttp3.internal.http2.Http2Connection
import okhttp3.internal.http2.Http2Stream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-3336 (R383, FR-R383-2, FR-R383-4) — a live HTTP/2 connection whose writes start failing with `NoRouteToHostException`
 * (macOS withdrawing Local Network from a socket it had allowed) ends the call at once. Without [DeadConnectionGuard] OkHttp
 * retried on the same connection for ever, one leaked `Http2Stream` per try, until the heap was full.
 *
 * The server is OkHttp's own HTTP/2 connection in server mode over a real loopback socket, and the client's sockets refuse a
 * write on command, so the whole path from `execute()` to the wire is the shipped one.
 */
class DeadConnectionGuardTest {
    /** Opens a loopback HTTP/2 server answering 200; [block] gets the URL and the switch that makes client writes fail. */
    private fun <T> withBrokenableServer(guard: Boolean, block: (url: String, client: OkHttpClient, breakWrites: () -> Unit, writeFailures: AtomicInteger, connects: AtomicInteger) -> T): T {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val acceptor = Thread {
            runCatching {
                while (true) {
                    val socket = server.accept()
                    Http2Connection.Builder(false, TaskRunner.INSTANCE)
                        .socket(socket.asBufferedSocket(), "test-server")
                        .listener(object : Http2Connection.Listener() {
                            override fun onStream(stream: Http2Stream) {
                                stream.writeHeaders(listOf(Header(":status", "200")), outFinished = true, flushHeaders = true)
                            }
                        })
                        .build().start(true)
                }
            }
        }.apply { isDaemon = true; start() }

        val broken = AtomicBoolean(false)
        val writeFailures = AtomicInteger()
        val connects = AtomicInteger()
        fun wrap(): Socket = object : Socket() {
            override fun getOutputStream(): OutputStream {
                val inner = super.getOutputStream()
                return object : FilterOutputStream(inner) {
                    private fun check() {
                        if (broken.get()) { writeFailures.incrementAndGet(); throw NoRouteToHostException("No route to host (Host unreachable)") }
                    }
                    override fun write(b: Int) { check(); inner.write(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) { check(); inner.write(b, off, len) }
                }
            }
        }
        val factory = object : SocketFactory() {
            override fun createSocket(): Socket = wrap().also { connects.incrementAndGet() }
            override fun createSocket(host: String, port: Int): Socket = createSocket().also { it.connect(InetSocketAddress(host, port)) }
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = createSocket(host, port)
            override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().also { it.connect(InetSocketAddress(host, port)) }
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = createSocket(host, port)
        }
        val client = OkHttpClient.Builder().socketFactory(factory).protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
            .also { if (guard) it.addNetworkInterceptor(DeadConnectionGuard) }.build()
        try {
            return block("http://127.0.0.1:${server.localPort}/x", client, { broken.set(true) }, writeFailures, connects)
        } finally {
            client.connectionPool.evictAll()
            server.close()
            acceptor.interrupt()
        }
    }

    private fun OkHttpClient.get(url: String): Int = newCall(Request.Builder().url(url).build()).execute().use { it.code }

    @Test
    fun `a write that fails on a live HTTP2 connection fails the call and does not retry for ever`() =
        withBrokenableServer(guard = true) { url, client, breakWrites, writeFailures, _ ->
            assertEquals(200, client.get(url), "the connection works before the writes start failing")
            breakWrites()
            var failure: Throwable? = null
            val call = Thread { failure = runCatching { client.get(url) }.exceptionOrNull() }.apply { isDaemon = true; start() }
            call.join(10_000)
            val stillRunning = call.isAlive
            if (stillRunning) client.dispatcher.cancelAll().also { call.join(5_000) }   // a failing run must not leave the spin behind
            assertFalse(stillRunning, "the call returned within 10 s; it was still retrying after ${writeFailures.get()} failed writes")
            assertTrue(failure is IOException, "the call failed with an IOException: $failure")
            assertTrue(writeFailures.get() <= 5, "a handful of tries at most, not a retry loop: ${writeFailures.get()} failed writes")
        }

    @Test
    fun `a viewer's next call after the network comes back opens a new connection and succeeds`() =
        withBrokenableServer(guard = true) { url, client, breakWrites, _, connects ->
            assertEquals(200, client.get(url))
            assertEquals(1, connects.get())
            breakWrites()
            runCatching { client.get(url) }
            assertTrue(client.connectionPool.connectionCount() == 0, "the broken connection left the pool: ${client.connectionPool.connectionCount()}")
        }

    @Test
    fun `calls on a healthy connection share it and pass through untouched`() =
        withBrokenableServer(guard = true) { url, client, _, writeFailures, connects ->
            repeat(5) { assertEquals(200, client.get(url)) }
            assertEquals(1, connects.get(), "one HTTP/2 connection carries all five")
            assertEquals(0, writeFailures.get())
        }
}
