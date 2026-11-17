package dev.jellystructure.samsung

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

/** DEB-4842 FR-4: read only the distributor signature's leaf certificate from a Tizen ZIP package. */
internal object WgtCertificate {
    private const val MAX_PACKAGE = 20 * 1024 * 1024
    private const val MAX_SIGNATURE = 256 * 1024
    private val certificate = Regex("<X509Certificate\\s*>([A-Za-z0-9+/=\\s]+)</X509Certificate>")
    private val duid = Regex("URN:tizen:deviceid=([A-Z0-9]{13})")

    /** Null means the package/signature was invalid; an empty set is a valid certificate with no device ids. */
    fun deviceIds(packageBytes: ByteArray): Set<String>? {
        if (packageBytes.size !in 22..MAX_PACKAGE) return null
        val xml = zipEntry(packageBytes, "signature1.xml")?.decodeToString() ?: return null
        val encoded = certificate.find(xml)?.groupValues?.get(1) ?: return null
        val der = decodeBase64(encoded) ?: return null
        if (der.size < 4 || der[0] != 0x30.toByte()) return null
        return duid.findAll(der.decodeToString()).map { it.groupValues[1] }.toSet()
    }

    /** Uses the central directory: signed WGTs may have a data descriptor after each local file header. */
    private fun zipEntry(zip: ByteArray, wanted: String): ByteArray? {
        val end = (zip.size - 22 downTo maxOf(0, zip.size - 65557)).firstOrNull { zip.u32(it) == 0x06054b50L } ?: return null
        val entries = zip.u16(end + 10) ?: return null
        val directory = zip.u32(end + 16)?.toInt() ?: return null
        if (entries > 10000 || directory < 0 || directory >= end) return null
        var pos = directory
        repeat(entries) {
            if (zip.u32(pos) != 0x02014b50L) return null
            val method = zip.u16(pos + 10) ?: return null
            val compressed = zip.u32(pos + 20)?.toInt() ?: return null
            val expanded = zip.u32(pos + 24)?.toInt() ?: return null
            val nameLen = zip.u16(pos + 28) ?: return null
            val extraLen = zip.u16(pos + 30) ?: return null
            val commentLen = zip.u16(pos + 32) ?: return null
            val local = zip.u32(pos + 42)?.toInt() ?: return null
            val next = pos.toLong() + 46 + nameLen + extraLen + commentLen
            if (next > end || next <= pos) return null
            val name = zip.copyOfRange(pos + 46, pos + 46 + nameLen).decodeToString()
            if (name == wanted) {
                if (compressed < 0 || expanded !in 1..MAX_SIGNATURE || compressed > MAX_SIGNATURE || local < 0 || zip.u32(local) != 0x04034b50L) return null
                val localName = zip.u16(local + 26) ?: return null
                val localExtra = zip.u16(local + 28) ?: return null
                val start = local.toLong() + 30 + localName + localExtra
                if (start < 0 || start + compressed > zip.size) return null
                val payload = zip.copyOfRange(start.toInt(), start.toInt() + compressed)
                return when (method) {
                    0 -> payload.takeIf { it.size == expanded }
                    8 -> inflateRaw(payload, expanded)
                    else -> null
                }
            }
            pos = next.toInt()
        }
        return null
    }

    private fun ByteArray.u16(at: Int): Int? =
        if (at < 0 || at.toLong() + 2 > size) null else (this[at].toInt() and 255) or ((this[at + 1].toInt() and 255) shl 8)

    private fun ByteArray.u32(at: Int): Long? {
        val low = u16(at) ?: return null
        val high = u16(at + 2) ?: return null
        return low.toLong() or (high.toLong() shl 16)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun inflateRaw(input: ByteArray, expected: Int): ByteArray? = memScoped {
        if (input.isEmpty()) return@memScoped null
        val stream = alloc<z_stream>()
        stream.zalloc = null; stream.zfree = null; stream.opaque = null
        if (inflateInit2(stream.ptr, -15) != Z_OK) return@memScoped null
        try {
            val output = ByteArray(expected)
            var valid = false
            input.usePinned { source ->
                output.usePinned { target ->
                    stream.next_in = source.addressOf(0).reinterpret()
                    stream.avail_in = input.size.convert()
                    stream.next_out = target.addressOf(0).reinterpret()
                    stream.avail_out = expected.convert()
                    valid = inflate(stream.ptr, 0) == Z_STREAM_END && stream.avail_out.toInt() == 0
                }
            }
            if (valid) output else null
        } finally {
            inflateEnd(stream.ptr)
        }
    }

    private fun decodeBase64(value: String): ByteArray? {
        val s = value.filterNot(Char::isWhitespace)
        if (s.length < 8 || s.length % 4 != 0 || s.length > MAX_SIGNATURE * 2) return null
        val out = ArrayList<Byte>(s.length * 3 / 4)
        for (i in s.indices step 4) {
            val a = digit(s[i]); val b = digit(s[i + 1]); val c = if (s[i + 2] == '=') 0 else digit(s[i + 2]); val d = if (s[i + 3] == '=') 0 else digit(s[i + 3])
            if (a < 0 || b < 0 || c < 0 || d < 0 || (s[i + 2] == '=' && s[i + 3] != '=') || (i + 4 != s.length && (s[i + 2] == '=' || s[i + 3] == '='))) return null
            val bits = (a shl 18) or (b shl 12) or (c shl 6) or d
            out.add((bits shr 16).toByte())
            if (s[i + 2] != '=') out.add((bits shr 8).toByte())
            if (s[i + 3] != '=') out.add(bits.toByte())
        }
        return out.toByteArray()
    }

    private fun digit(ch: Char): Int = when (ch) {
        in 'A'..'Z' -> ch - 'A'
        in 'a'..'z' -> ch - 'a' + 26
        in '0'..'9' -> ch - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }
}
