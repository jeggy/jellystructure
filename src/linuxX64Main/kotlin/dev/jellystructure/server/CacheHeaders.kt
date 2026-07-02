package dev.jellystructure.server

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.headers
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes

// Phase 118 (FR C.2) — "the single biggest fix": every image/artwork route responded with no cache
// headers at all, so every admin page load (Library grid, metadata pages, the TV app) re-fetched every
// poster/logo/person image from scratch — the request storm behind the crash logs' FD pressure.
// Generalizes Server.kt's existing serveStaticBytes ETag pattern for route handlers that resolve their
// bytes dynamically (image proxy cache, TMDB-downloaded artwork) rather than serving a static file.
private fun ByteArray.crc32Hex(): String {
    var crc = 0xFFFFFFFFL
    for (b in this) {
        var v = ((crc xor b.toLong().and(0xFF)) and 0xFF).toInt()
        repeat(8) { v = if (v and 1 != 0) (v ushr 1) xor 0xEDB88320.toInt() else v ushr 1 }
        crc = (crc ushr 8) xor v.toLong().and(0xFFFFFFFFL)
    }
    return (crc xor 0xFFFFFFFFL).toString(16).padStart(8, '0')
}

/** Content-hash ETag + long-lived Cache-Control on an image response; a matching If-None-Match short-
 *  circuits to 304 before the bytes are even sent. Artwork is immutable at a given URL in this app (a
 *  changed poster gets a new path/version, not an in-place overwrite), so a long max-age is safe. */
suspend fun ApplicationCall.respondCachedBytes(bytes: ByteArray, contentType: ContentType, maxAgeSeconds: Int = 86_400) {
    val etag = "\"${bytes.crc32Hex()}\""
    if (request.headers[HttpHeaders.IfNoneMatch] == etag) {
        respond(HttpStatusCode.NotModified)
        return
    }
    response.headers.append(HttpHeaders.ETag, etag)
    response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = maxAgeSeconds, mustRevalidate = false))
    respondBytes(bytes, contentType)
}

/**
 * Phase 118 (FR C.5) — load-shedding before death: above the FD ceiling's danger zone, an image route
 * responds 503 + Retry-After instead of doing the work (which would itself open more FDs — artwork
 * fetch, image-proxy cache reads, etc). The browser/app retries; normal API routes are unaffected since
 * they reuse already-open sockets rather than opening new ones. Returns true if the request was shed
 * (the caller must `return` immediately after).
 */
suspend fun ApplicationCall.shedIfFdCritical(fdWatchdog: dev.jellystructure.ops.FdWatchdog?): Boolean {
    if (fdWatchdog?.isOverShedThreshold != true) return false
    response.headers.append(HttpHeaders.RetryAfter, "5")
    respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "server under FD pressure, retry shortly"))
    return true
}
