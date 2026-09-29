package dev.jellystructure.ravilo.castv2

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * R330 (FR-R330-1, D1) — the one platform-shaped part of the protocol: a TLS byte stream to a device's port 8009.
 * [incoming] closes when the socket does. The JVM actual accepts the device's self-signed certificate, as every
 * sender does; a later `linuxX64` actual makes the backend a sender too (286's road C).
 */
interface CastTransport {
    val incoming: ReceiveChannel<ByteArray>
    suspend fun send(bytes: ByteArray)
    fun close()
}

expect suspend fun openCastTransport(host: String, port: Int, timeoutMs: Long = 5_000L): CastTransport
