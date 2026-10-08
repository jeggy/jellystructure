package dev.jellystructure.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Phase 313 (dev review item 6b) — a file sent in 256 KB pieces instead of read whole into one `ByteArray`: a 2 s 4K
 * segment is ~5 MB per request, and allocating that per request churns Kotlin/Native's GC (phase 228).
 */
/**
 * Phase 314b — a file a player reads like any progressive stream: `Accept-Ranges: bytes`, and a single `Range` answered
 * with 206 and only those bytes (Media3 seeks inside a Matroska sidecar by range requests). No range ⇒ the whole file.
 */
suspend fun respondFileRange(call: ApplicationCall, path: String, contentType: ContentType) {
    val p = Path(path)
    val size = SystemFileSystem.metadataOrNull(p)?.size ?: return call.respond(HttpStatusCode.NotFound)
    val header = call.request.headers[io.ktor.http.HttpHeaders.Range]
    val range = dev.jellystructure.tv.parseByteRange(header, size)
    call.response.headers.append(io.ktor.http.HttpHeaders.AcceptRanges, "bytes")
    if (header != null && range == null) {
        call.response.headers.append(io.ktor.http.HttpHeaders.ContentRange, "bytes */$size")
        return call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
    }
    val start = range?.first ?: 0L
    val length = (range?.last ?: (size - 1)) - start + 1
    if (range != null) call.response.headers.append(io.ktor.http.HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/$size")
    call.respondBytesWriter(contentType, if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK, length) {
        val buf = ByteArray(256 * 1024)
        SystemFileSystem.source(p).buffered().use { source ->
            if (start > 0) withContext(Dispatchers.IO) { source.skip(start) }
            var left = length
            while (left > 0) {
                val n = withContext(Dispatchers.IO) { source.readAtMostTo(buf, 0, minOf(buf.size.toLong(), left).toInt()) }
                if (n <= 0) break
                writeFully(buf, 0, n)
                left -= n
            }
        }
    }
}

suspend fun respondFileChunked(call: ApplicationCall, path: String, contentType: ContentType) {
    val p = Path(path)
    val size = SystemFileSystem.metadataOrNull(p)?.size ?: return call.respond(HttpStatusCode.NotFound)
    call.respondBytesWriter(contentType, HttpStatusCode.OK, size) {
        val buf = ByteArray(256 * 1024)
        SystemFileSystem.source(p).buffered().use { source ->
            while (true) {
                val n = withContext(Dispatchers.IO) { source.readAtMostTo(buf) }
                if (n <= 0) break
                writeFully(buf, 0, n)
            }
        }
    }
}
