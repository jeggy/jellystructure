package dev.jellystructure.webstatic

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.posix.getenv

/**
 * FR-167-5 — a plain Kotlin static-file server, no reverse-proxy technology baked in ("simple Kotlin
 * services that people on their own can put behind caddy or whatever"). Deliberately not a dependent of
 * the root project (which would drag in the whole backend's SQLDelight/config/media stack for what is
 * just "serve some files") — this is the entire service.
 *
 * Serving logic (path-traversal guard, ETag, per-extension content type, SPA index.html fallback with
 * no-cache) mirrors `Server.kt`'s already-proven `serveFrontendFile`/`serveStaticBytes` exactly; kept as
 * a deliberate small duplication rather than a shared dependency, for the same "no coupling to the big
 * project" reason above.
 */
@OptIn(ExperimentalForeignApi::class)
private fun env(name: String, default: String): String = getenv(name)?.toKString() ?: default

fun main() {
    val dir = env("STATIC_DIR", "/srv")
    val port = env("SERVER_PORT", "8080").toIntOrNull() ?: 8080

    embeddedServer(
        CIO,
        configure = {
            connectors.add(EngineConnectorBuilder().apply { this.port = port })
            connectionIdleTimeoutSeconds = 10
        },
    ) {
        routing {
            get("{...}") {
                call.serveStaticFile(dir, call.request.path())
            }
        }
    }.start(wait = true)
}

private suspend fun ApplicationCall.serveStaticFile(dir: String, requestPath: String) {
    val rel = requestPath.trimStart('/').ifEmpty { "index.html" }

    if (".." in rel) {
        respond(HttpStatusCode.BadRequest)
        return
    }

    val target = Path("$dir/$rel")
    if (SystemFileSystem.exists(target)) {
        serveBytes(readFile(target), rel)
        return
    }

    // SPA fallback — Ravilo web routes on the URL hash, which never reaches the server, so any
    // unmatched path is just "the app itself, at a deep link" — never cache index.html (it references
    // the current content-hashed bundle by name).
    val index = Path("$dir/index.html")
    if (SystemFileSystem.exists(index)) {
        response.cacheControl(CacheControl.NoCache(null))
        respondBytes(readFile(index), ContentType.Text.Html)
    } else {
        respond(HttpStatusCode.NotFound)
    }
}

// kotlinx-io's SystemFileSystem.source() has no finalizer — an unclosed source leaks one FD per call.
// .use{} scoping is mandatory (same rule this repo's FileIo.kt documents and enforces for the main
// backend); this module is small enough not to need its own copy of that whole helper object.
private fun readFile(path: Path): ByteArray =
    SystemFileSystem.source(path).buffered().use { it.readByteArray() }

private suspend fun ApplicationCall.serveBytes(bytes: ByteArray, rel: String) {
    val etag = "\"${bytes.crc32Hex()}\""
    if (request.headers[HttpHeaders.IfNoneMatch] == etag) {
        respond(HttpStatusCode.NotModified)
        return
    }
    response.headers.append(HttpHeaders.ETag, etag)
    if (rel == "index.html") {
        response.cacheControl(CacheControl.NoCache(null))
    } else {
        response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 3600, mustRevalidate = true))
    }
    respondBytes(bytes, contentTypeFor(rel))
}

private fun ByteArray.crc32Hex(): String {
    var crc = 0xFFFFFFFFL
    for (b in this) {
        var v = ((crc xor b.toLong().and(0xFF)) and 0xFF).toInt()
        repeat(8) { v = if (v and 1 != 0) (v ushr 1) xor 0xEDB88320.toInt() else v ushr 1 }
        crc = (crc ushr 8) xor v.toLong().and(0xFFFFFFFFL)
    }
    return (crc xor 0xFFFFFFFFL).toString(16).padStart(8, '0')
}

private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.').lowercase()) {
    "html"        -> ContentType.Text.Html
    "css"         -> ContentType.Text.CSS
    "js", "mjs"   -> ContentType.Application.JavaScript
    "wasm"        -> ContentType.parse("application/wasm")
    "json"        -> ContentType.Application.Json
    "png"         -> ContentType.Image.PNG
    "jpg", "jpeg" -> ContentType.Image.JPEG
    "svg"         -> ContentType.Image.SVG
    "ico"         -> ContentType.parse("image/x-icon")
    "woff2"       -> ContentType.parse("font/woff2")
    else          -> ContentType.Application.OctetStream
}
