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
    // R225 — ravilo-web has no env access of its own (compiled wasmJs); this is the only runtime
    // process in front of it, and the natural place to hand it an operator-configured default server
    // (e.g. the demo stack, where ravilo-web and its backend are on different origins so the wasmJs
    // actual's same-origin fallback is wrong). Unset ⇒ index.html is byte-identical to before this
    // phase, no behavior change.
    val defaultServerUrl = env("DEFAULT_SERVER_URL", "").ifBlank { null }
    // Phase 224 (FR-224-6) — the version this image was published as (BUILD_VERSION → RAVILO_VERSION),
    // handed to the wasm app the same way: one inline assignment ahead of its bundle. R252 FR-R252-3
    // reads it in preference to the version compiled into the bundle, which in an image is always
    // "dev" (no .git in the build context, by design — it keeps a release build a cache hit).
    val raviloVersion = env("RAVILO_VERSION", "").ifBlank { null }

    embeddedServer(
        CIO,
        configure = {
            connectors.add(EngineConnectorBuilder().apply { this.port = port })
            connectionIdleTimeoutSeconds = 10
        },
    ) {
        routing {
            get("{...}") {
                call.serveStaticFile(dir, call.request.path(), defaultServerUrl, raviloVersion)
            }
        }
    }.start(wait = true)
}

private suspend fun ApplicationCall.serveStaticFile(dir: String, requestPath: String, defaultServerUrl: String?, raviloVersion: String?) {
    val rel = requestPath.trimStart('/').ifEmpty { "index.html" }

    if (".." in rel) {
        respond(HttpStatusCode.BadRequest)
        return
    }

    val target = Path("$dir/$rel")
    if (SystemFileSystem.exists(target)) {
        if (rel == "index.html") {
            serveBytes(injectRuntimeConfig(readFile(target), defaultServerUrl, raviloVersion), rel)
        } else {
            serveBytes(readFile(target), rel)
        }
        return
    }

    // SPA fallback — Ravilo web routes on the URL hash, which never reaches the server, so any
    // unmatched path is just "the app itself, at a deep link" — never cache index.html (it references
    // the current content-hashed bundle by name).
    val index = Path("$dir/index.html")
    if (SystemFileSystem.exists(index)) {
        response.cacheControl(CacheControl.NoCache(null))
        respondBytes(injectRuntimeConfig(readFile(index), defaultServerUrl, raviloVersion), ContentType.Text.Html)
    } else {
        respond(HttpStatusCode.NotFound)
    }
}

// R225 FR-R225-2 — one inline script ahead of the app bundle's own <script> tag; every other asset
// (ravilo.js, the .wasm files, composeResources/**) is untouched and stays content-addressed/cacheable.
// Phase 224 (FR-224-6) extends the same script with window.__RAVILO_VERSION__ when the image carries
// one. Neither set ⇒ index.html is byte-identical to the bundle's own.
private fun injectRuntimeConfig(indexBytes: ByteArray, defaultServerUrl: String?, raviloVersion: String?): ByteArray {
    if (defaultServerUrl == null && raviloVersion == null) return indexBytes
    val html = indexBytes.decodeToString()
    fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val assignments = buildList {
        defaultServerUrl?.let { add("window.__RAVILO_DEFAULT_SERVER__=${jsString(it)};") }
        raviloVersion?.let { add("window.__RAVILO_VERSION__=${jsString(it)};") }
    }.joinToString("")
    val script = "<script>$assignments</script>\n    "
    return html.replace("<script src=\"ravilo.js\">", script + "<script src=\"ravilo.js\">").encodeToByteArray()
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
