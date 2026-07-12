package dev.jellystructure.server

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.ApplicationSendPipeline
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateBound
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2
import platform.zlib.z_stream

// Bug fix: below this, gzip's own framing overhead (a ~20-byte gzip header/trailer plus deflate's
// own bookkeeping) outweighs the savings on already-small responses.
private const val GZIP_MIN_BYTES = 860

/**
 * Ktor's `Compression` plugin has no linuxX64 klib variant (confirmed against the 3.5.0 Gradle
 * module metadata — it publishes only jvmApiElements/jvmRuntimeElements), so every JSON/text
 * response — Library grid, Ravilo home/browse/detail with its per-episode data — went out
 * uncompressed regardless of size, and that cost scales directly with the library. Hooks into the
 * same `ApplicationSendPipeline.ContentEncoding` phase the real plugin would use — verified against
 * Ktor's own source (`BaseApplicationResponse.resetFrom` resets every per-call response pipeline
 * from this Application-level one, so registering the interceptor once here covers every route).
 * Skips anything that isn't a `ByteArrayContent` (i.e. streamed content — none of this codebase's
 * routes produce that today) and anything under [GZIP_MIN_BYTES].
 */
fun Application.installGzipCompression() {
    sendPipeline.intercept(ApplicationSendPipeline.ContentEncoding) { message ->
        val original = message as? OutgoingContent.ByteArrayContent ?: return@intercept
        val acceptsGzip = call.request.headers[HttpHeaders.AcceptEncoding]
            ?.split(",")?.any { it.trim().startsWith("gzip") } == true
        if (!acceptsGzip) return@intercept
        val bytes = original.bytes()
        if (bytes.size < GZIP_MIN_BYTES) return@intercept
        val compressed = gzipCompress(bytes) ?: return@intercept
        proceedWith(object : OutgoingContent.ByteArrayContent() {
            override val contentType = original.contentType
            override val status = original.status
            override val contentLength = compressed.size.toLong()
            override val headers = Headers.build {
                appendAll(original.headers)
                append(HttpHeaders.ContentEncoding, "gzip")
            }
            override fun bytes() = compressed
        })
    }
}

/**
 * Ktor's `Compression` plugin has no linuxX64 klib variant (confirmed against the 3.5.0 Gradle
 * module metadata — it publishes only jvmApiElements/jvmRuntimeElements), so gzip is hand-rolled
 * here via Kotlin/Native's bundled zlib platform library (ships with the compiler for this target,
 * `-lz` linked automatically — see `zlib.def` in the Kotlin/Native distribution's platform libs).
 * `windowBits = 15 + 16` (MAX_WBITS + 16) tells zlib to emit the gzip container (magic bytes, CRC32,
 * size trailer) instead of the plain zlib format `compress()`/`compress2()` would produce — gzip is
 * what a `Content-Encoding: gzip` response and every HTTP client's decoder expects.
 *
 * One-shot (not streaming): every caller already has the full response body in memory as a
 * `ByteArray` before this runs, so there's no benefit to the incremental deflate API here.
 *
 * Returns null on any zlib failure (caller falls back to sending the original, uncompressed bytes —
 * gzip is always an optional transport optimization, never load-bearing for correctness).
 */
@OptIn(ExperimentalForeignApi::class)
fun gzipCompress(input: ByteArray): ByteArray? {
    if (input.isEmpty()) return null
    return memScoped {
        val strm = alloc<z_stream>()
        strm.zalloc = null
        strm.zfree = null
        strm.opaque = null
        val initRc = deflateInit2(strm.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 15 + 16, 8, Z_DEFAULT_STRATEGY)
        if (initRc != Z_OK) return@memScoped null

        val bound = deflateBound(strm.ptr, input.size.convert()).toInt()
        val output = ByteArray(bound)
        var producedLen = -1
        input.usePinned { inPinned ->
            output.usePinned { outPinned ->
                strm.next_in = inPinned.addressOf(0).reinterpret()
                strm.avail_in = input.size.convert()
                strm.next_out = outPinned.addressOf(0).reinterpret()
                strm.avail_out = bound.convert()
                val rc = deflate(strm.ptr, Z_FINISH)
                if (rc == Z_STREAM_END) producedLen = bound - strm.avail_out.toInt()
            }
        }
        deflateEnd(strm.ptr)
        if (producedLen < 0) null else output.copyOf(producedLen)
    }
}
