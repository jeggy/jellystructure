package dev.jellystructure.ravilo.castv2

/**
 * R330 (FR-R330-1, dev review 3) — the one message Cast v2 speaks, as protobuf, encoded by hand: seven fields and
 * nothing else, so no protobuf library. On the wire every message is a 4-byte big-endian length, then this.
 *
 * ```
 * 1 protocol_version  varint  (0 = CASTV2_1_0)
 * 2 source_id         string
 * 3 destination_id    string
 * 4 namespace         string
 * 5 payload_type      varint  (0 = STRING, 1 = BINARY)
 * 6 payload_utf8      string  (optional)
 * 7 payload_binary    bytes   (optional)
 * ```
 */
data class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadUtf8: String? = null,
    val payloadBinary: ByteArray? = null,
    val protocolVersion: Int = 0,
) {
    val payloadType: Int get() = if (payloadBinary != null && payloadUtf8 == null) 1 else 0

    fun encode(): ByteArray {
        val out = ByteSink()
        out.tag(1, WIRE_VARINT); out.varint(protocolVersion.toLong())
        out.tag(2, WIRE_BYTES); out.bytes(sourceId.encodeToByteArray())
        out.tag(3, WIRE_BYTES); out.bytes(destinationId.encodeToByteArray())
        out.tag(4, WIRE_BYTES); out.bytes(namespace.encodeToByteArray())
        out.tag(5, WIRE_VARINT); out.varint(payloadType.toLong())
        payloadUtf8?.let { out.tag(6, WIRE_BYTES); out.bytes(it.encodeToByteArray()) }
        payloadBinary?.let { out.tag(7, WIRE_BYTES); out.bytes(it) }
        return out.toByteArray()
    }

    /** The length-prefixed frame that goes on the socket. */
    fun frame(): ByteArray {
        val body = encode()
        val n = body.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + body
    }

    override fun equals(other: Any?): Boolean = other is CastMessage && sourceId == other.sourceId &&
        destinationId == other.destinationId && namespace == other.namespace && payloadUtf8 == other.payloadUtf8 &&
        (payloadBinary?.contentEquals(other.payloadBinary ?: return false) ?: (other.payloadBinary == null)) &&
        protocolVersion == other.protocolVersion

    override fun hashCode(): Int = listOf(sourceId, destinationId, namespace, payloadUtf8, protocolVersion).hashCode()

    companion object {
        private const val WIRE_VARINT = 0
        private const val WIRE_64 = 1
        private const val WIRE_BYTES = 2
        private const val WIRE_32 = 5

        /** Decodes one message body (no length prefix). Unknown fields are skipped; a malformed body throws. */
        fun decode(bytes: ByteArray): CastMessage {
            val r = ByteSource(bytes)
            var version = 0
            var source = ""; var destination = ""; var namespace = ""
            var utf8: String? = null; var binary: ByteArray? = null
            while (!r.done) {
                val key = r.varint()
                val field = (key ushr 3).toInt()
                when (val wire = (key and 7).toInt()) {
                    WIRE_VARINT -> { val v = r.varint(); if (field == 1) version = v.toInt() }
                    WIRE_BYTES -> {
                        val b = r.bytes()
                        when (field) {
                            2 -> source = b.decodeToString()
                            3 -> destination = b.decodeToString()
                            4 -> namespace = b.decodeToString()
                            6 -> utf8 = b.decodeToString()
                            7 -> binary = b
                        }
                    }
                    WIRE_64 -> r.skip(8)
                    WIRE_32 -> r.skip(4)
                    else -> throw IllegalArgumentException("wire type $wire")
                }
            }
            return CastMessage(source, destination, namespace, utf8, binary, version)
        }
    }
}

/**
 * Turns the socket's bytes, in whatever pieces they arrive, back into messages: a length, then that many bytes.
 * A length above [maxFrame] means the stream is not Cast v2 (or is corrupt) and throws — the session then closes.
 */
class CastFrameReader(private val maxFrame: Int = 64 * 1024) {
    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray, count: Int = chunk.size): List<CastMessage> {
        buf += chunk.copyOf(count)
        val out = mutableListOf<CastMessage>()
        while (buf.size >= 4) {
            val n = ((buf[0].toInt() and 0xff) shl 24) or ((buf[1].toInt() and 0xff) shl 16) or
                ((buf[2].toInt() and 0xff) shl 8) or (buf[3].toInt() and 0xff)
            require(n in 0..maxFrame) { "frame of $n bytes" }
            if (buf.size < 4 + n) break
            out += CastMessage.decode(buf.copyOfRange(4, 4 + n))
            buf = buf.copyOfRange(4 + n, buf.size)
        }
        return out
    }
}

private class ByteSink {
    private var buf = ByteArray(128)
    private var n = 0
    private fun put(b: Int) {
        if (n == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[n++] = b.toByte()
    }
    fun varint(value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) { put(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
        put(v.toInt())
    }
    fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
    fun bytes(b: ByteArray) { varint(b.size.toLong()); b.forEach { put(it.toInt()) } }
    fun toByteArray(): ByteArray = buf.copyOf(n)
}

private class ByteSource(private val b: ByteArray) {
    private var i = 0
    val done: Boolean get() = i >= b.size
    fun varint(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            require(i < b.size) { "truncated varint" }
            val x = b[i++].toInt() and 0xff
            result = result or ((x and 0x7f).toLong() shl shift)
            if (x and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "varint too long" }
        }
    }
    fun bytes(): ByteArray {
        val n = varint().toInt()
        require(n >= 0 && i + n <= b.size) { "truncated field" }
        return b.copyOfRange(i, i + n).also { i += n }
    }
    fun skip(n: Int) { require(i + n <= b.size) { "truncated field" }; i += n }
}
