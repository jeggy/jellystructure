package dev.jellystructure.ravilo.castv2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * R330 (FR-R330-1) — TLS to port 8009. A Cast device presents a certificate of its own making, so its chain is not
 * checked: every sender does this (the device is found on the local network, and nothing secret crosses — the
 * hand-off code is short-lived and single-use, 218 FR-218-9).
 */
actual suspend fun openCastTransport(host: String, port: Int, timeoutMs: Long): CastTransport = withContext(Dispatchers.IO) {
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf<TrustManager>(AcceptDeviceCertificate), SecureRandom())
    val raw = java.net.Socket()
    // Amendment 2026-10-12 — the handshake gets the same bound as the connect. A device that accepts TCP on 8009 and
    // then answers nothing (an overloaded or half-asleep Chromecast) used to hold this call for as long as it held the
    // socket, with `soTimeout = 0` set before the handshake; every rejoin ladder waits for this call to come back, so
    // the link sat at RECONNECTING and no later try ever ran. Reads after the handshake are unbounded again — the
    // protocol is quiet between a device's own reports.
    val transport = try {
        raw.connect(InetSocketAddress(host, port), timeoutMs.toInt())
        raw.tcpNoDelay = true
        raw.soTimeout = timeoutMs.toInt()
        val socket = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        socket.startHandshake()
        socket.soTimeout = 0
        TlsCastTransport(socket)
    } catch (e: Throwable) {
        runCatching { raw.close() }   // a failed open leaves nothing behind
        throw e
    }
    // The caller may have given up while a stalled device was still shaking hands (`CastAvailability.ask` wraps this
    // in a timeout, and cancellation cannot interrupt a blocking handshake): close what was built rather than hand
    // back a socket and a reader thread nobody will ever close.
    if (!isActive) {
        transport.close()
        throw CancellationException("the cast transport to $host:$port was no longer wanted")
    }
    transport
}

private object AcceptDeviceCertificate : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private class TlsCastTransport(private val socket: SSLSocket) : CastTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    private val out = socket.outputStream
    override val incoming: ReceiveChannel<ByteArray> = channel

    init {
        scope.launch {
            val buf = ByteArray(16 * 1024)
            try {
                val input = socket.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) channel.send(buf.copyOf(n))
                }
            } catch (_: Exception) {
            } finally {
                channel.close()
            }
        }
    }

    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(out) { out.write(bytes); out.flush() }
    }

    override fun close() {
        runCatching { socket.close() }
        channel.close()
        scope.cancel()
    }
}
