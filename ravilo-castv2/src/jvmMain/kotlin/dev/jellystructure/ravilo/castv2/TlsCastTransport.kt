package dev.jellystructure.ravilo.castv2

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    raw.connect(InetSocketAddress(host, port), timeoutMs.toInt())
    raw.tcpNoDelay = true
    val socket = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
    socket.soTimeout = 0
    socket.startHandshake()
    TlsCastTransport(socket)
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
