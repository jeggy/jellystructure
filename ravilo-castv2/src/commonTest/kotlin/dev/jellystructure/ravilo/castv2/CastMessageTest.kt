package dev.jellystructure.ravilo.castv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CastMessageTest {
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Golden bytes from an independent encoder (Python, field by field), not from this one.
    private val connect = CastMessage(DEFAULT_SENDER, PLATFORM_RECEIVER, CastNamespaces.CONNECTION, """{"type":"CONNECT"}""")
    private val connectBody = "0800120873656e6465722d301a0a72656365697665722d30222875726e3a782d636173743a636f6d2e676f6f676c652e63617374" +
        "2e74702e636f6e6e656374696f6e280032127b2274797065223a22434f4e4e454354227d"

    @Test
    fun `a message encodes to the golden bytes and frames with a big-endian length`() {
        assertEquals(connectBody, hex(connect.encode()))
        assertEquals("00000058$connectBody", hex(connect.frame()))
    }

    @Test
    fun `a payload longer than 127 bytes takes a two-byte length`() {
        val payload = """{"type":"LOAD","x":"${"a".repeat(200)}"}"""
        val m = CastMessage("s", "d", "n", payload)
        val body = m.encode()
        assertEquals("08001201731a016422016e280032de017b227479", hex(body.copyOf(20)))
        assertEquals(238, body.size)
        assertEquals(m, CastMessage.decode(body))
    }

    @Test
    fun `decoding reads every field back and skips ones it does not know`() {
        assertEquals(connect, CastMessage.decode(bytes(connectBody)))
        // field 15, varint 1 — unknown, skipped
        val withUnknown = bytes(connectBody) + byteArrayOf(0x78, 0x01)
        assertEquals(connect, CastMessage.decode(withUnknown))
        val binary = CastMessage("a", "b", "ns", payloadBinary = byteArrayOf(1, 2, 3))
        assertEquals(1, binary.payloadType)
        assertEquals(binary, CastMessage.decode(binary.encode()))
    }

    @Test
    fun `a truncated body is refused`() {
        assertFailsWith<IllegalArgumentException> { CastMessage.decode(bytes(connectBody).copyOf(20)) }
    }

    @Test
    fun `the frame reader reassembles messages from any split`() {
        val a = connect.frame()
        val b = CastMessage(PLATFORM_RECEIVER, DEFAULT_SENDER, CastNamespaces.HEARTBEAT, """{"type":"PONG"}""").frame()
        val stream = a + b
        for (cut in 1 until stream.size) {
            val r = CastFrameReader()
            val got = r.feed(stream.copyOf(cut)) + r.feed(stream.copyOfRange(cut, stream.size))
            assertEquals(2, got.size, "cut at $cut")
            assertEquals("""{"type":"PONG"}""", got[1].payloadUtf8)
        }
        val r = CastFrameReader()
        assertTrue(stream.toList().flatMap { r.feed(byteArrayOf(it)) }.size == 2, "one byte at a time")
    }

    @Test
    fun `a frame larger than the limit is not Cast`() {
        assertFailsWith<IllegalArgumentException> { CastFrameReader(maxFrame = 16).feed(connect.frame()) }
    }
}
