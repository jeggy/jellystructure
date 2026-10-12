package dev.jellystructure.ravilo.castv2

import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R330 (amendment 2026-10-12) — a rejoin that cannot reach a device must end, not hang. A Cast device that accepts
 * TCP on its port and then answers nothing held [openCastTransport] for as long as it held the socket, because
 * `soTimeout` was 0 before the TLS handshake; both rejoin ladders (start-up, FR-R330-5, and a dropped connection)
 * wait for that call, so the link stayed at RECONNECTING and no later try ran.
 *
 * The stalled device here is a loopback `ServerSocket` that accepts and says nothing — no Mac, no real device.
 */
class CastTransportTimeoutTest {
    private val sockets = mutableListOf<AutoCloseable>()

    @AfterTest fun close() = sockets.forEach { runCatching { it.close() } }

    @Test fun `a device that never answers the handshake does not hang the open`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { sockets += it }
        var accepted: Socket? = null
        val device = Thread({ accepted = runCatching { server.accept() }.getOrNull() }, "stalled-cast-device")
            .apply { isDaemon = true; start() }

        var failure: Throwable? = null
        // On a separate thread, so a regression fails the test instead of hanging the suite: a blocking handshake
        // cannot be cancelled from outside.
        val open = Thread({
            failure = runCatching {
                runBlocking { openCastTransport(server.inetAddress.hostAddress, server.localPort, timeoutMs = 300) }
            }.exceptionOrNull()
        }, "cast-open").apply { isDaemon = true; start() }

        open.join(10_000)
        assertFalse(open.isAlive, "openCastTransport is still shaking hands with a device that answers nothing")
        val why = assertNotNull(failure, "an open that reached nobody must fail, not come back with a transport")
        assertTrue(
            why.toString().contains("timed out", ignoreCase = true) || why is java.net.SocketException,
            "expected the handshake's own timeout; got $why",
        )

        // What it built it closed: the stalled device's socket reaches EOF rather than being left open for ever.
        device.join(2_000)
        val seen = assertNotNull(accepted, "the test's own device never accepted the connection")
        sockets += seen
        seen.soTimeout = 5_000
        val buffer = ByteArray(4096)
        var n = seen.getInputStream().read(buffer)      // the ClientHello, then EOF
        while (n > 0) n = seen.getInputStream().read(buffer)
        assertEquals(-1, n, "the failed open left its socket open on the device")
    }

    @Test fun `a port nobody listens on fails inside the timeout`() {
        // A free port: bound, then let go, so nothing answers at all (the ordinary "device is off" case).
        val free = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val started = System.nanoTime()
        val failure = runCatching {
            runBlocking { openCastTransport(InetAddress.getLoopbackAddress().hostAddress, free, timeoutMs = 300) }
        }.exceptionOrNull()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertNotNull(failure, "a port nobody listens on must fail")
        assertTrue(tookMs < 5_000, "the open took ${tookMs} ms")
    }
}
