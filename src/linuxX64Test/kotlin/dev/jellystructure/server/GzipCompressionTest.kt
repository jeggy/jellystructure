package dev.jellystructure.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies gzipCompress() produces a real, standards-conformant gzip stream — since gzip is hand-
 * rolled via zlib cinterop (no Ktor Compression plugin exists for this target), this can't be
 * verified by the compiler alone, and the caller (Server.kt's ContentEncoding interceptor) can't be
 * exercised without actually restarting the live backend. Decoding via zlib's own `inflate` here is a
 * genuine, independent check: `inflate` validates the gzip container (magic bytes, CRC32, size
 * trailer) and would reject malformed output with an error code rather than silently accepting it —
 * it isn't a tautology just because both directions use zlib.
 */
class GzipCompressionTest {

    @OptIn(ExperimentalForeignApi::class)
    private fun gunzipViaZlib(compressed: ByteArray, expectedSize: Int): ByteArray = memScoped {
        val strm = alloc<z_stream>()
        strm.zalloc = null; strm.zfree = null; strm.opaque = null
        val initRc = inflateInit2(strm.ptr, 15 + 16) // MAX_WBITS + 16 → expect (and require) a gzip container
        check(initRc == Z_OK) { "inflateInit2 failed: $initRc" }
        val output = ByteArray(expectedSize)
        var producedLen = -1
        compressed.usePinned { inPinned ->
            output.usePinned { outPinned ->
                strm.next_in = inPinned.addressOf(0).reinterpret()
                strm.avail_in = compressed.size.convert()
                strm.next_out = outPinned.addressOf(0).reinterpret()
                strm.avail_out = expectedSize.convert()
                val rc = inflate(strm.ptr, 0)
                if (rc == Z_STREAM_END || rc == Z_OK) producedLen = expectedSize - strm.avail_out.toInt()
            }
        }
        inflateEnd(strm.ptr)
        check(producedLen >= 0) { "inflate did not report a complete stream" }
        output.copyOf(producedLen)
    }

    @Test
    fun roundTripsRepetitiveTextThroughZlibsOwnInflate() {
        val original = "hello world ".repeat(200).encodeToByteArray()
        val compressed = gzipCompress(original)
        assertTrue(compressed != null && compressed.isNotEmpty())
        assertTrue(compressed.size < original.size, "gzip should meaningfully shrink repetitive text")
        assertContentEquals(original, gunzipViaZlib(compressed, original.size))
    }

    @Test
    fun roundTripsJsonLikePayload() {
        // Representative of what actually gets compressed in production: a JSON array of small objects.
        val original = buildString {
            append("[")
            repeat(300) { i ->
                if (i > 0) append(",")
                append("""{"id":"item-$i","title":"Some Movie Title $i","year":2020,"genre":"Drama"}""")
            }
            append("]")
        }.encodeToByteArray()
        val compressed = gzipCompress(original)
        assertTrue(compressed != null && compressed.isNotEmpty())
        assertTrue(compressed.size < original.size)
        assertContentEquals(original, gunzipViaZlib(compressed, original.size))
    }

    @Test
    fun roundTripsTinyPayload() {
        val original = "{}".encodeToByteArray()
        val compressed = gzipCompress(original)
        assertTrue(compressed != null && compressed.isNotEmpty())
        assertContentEquals(original, gunzipViaZlib(compressed, original.size))
    }

    @Test
    fun returnsNullForEmptyInput() {
        assertNull(gzipCompress(ByteArray(0)))
    }
}
