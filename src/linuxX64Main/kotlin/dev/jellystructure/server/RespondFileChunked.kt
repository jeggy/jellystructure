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
