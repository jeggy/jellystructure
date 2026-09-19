package dev.jellystructure.webstatic

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.request.path
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.posix.getenv

// FR-167-5 — a plain Kotlin static-file server, no reverse-proxy technology baked in ("simple Kotlin
// services that people on their own can put behind caddy or whatever"). Deliberately not a dependent of
// the root project (which would drag in the whole backend's SQLDelight/config/media stack for what is
// just "serve some files") — this is the entire service.
//
// Serving logic (path-traversal guard, ETag, per-extension content type, SPA index.html fallback,
// immutable-vs-revalidated caching, precompressed-sibling negotiation, the security header set) mirrors
// Server.kt's /tv/** path exactly (phase 235) — kept as a deliberate small duplication rather than a
// shared dependency, for the same "no coupling to the big project" reason above; phase 235's own e2e
// spec asserts both paths agree.
// (Note: this file-header intentionally uses `//` throughout, not `/** */` — a KDoc block comment
// nests in Kotlin, and a literal "/tv/**" or "composeResources/**" inside one opens an unterminated
// inner comment that swallows the rest of the file. Found live building this exact comment.)
@OptIn(ExperimentalForeignApi::class)
private fun env(name: String, default: String): String = getenv(name)?.toKString() ?: default

// FR-235-1 (dev review item 1) — only a file whose *name* carries a real content hash may be cached
// forever: today that's the two `<hash>.wasm` files. `ravilo.js` (a fixed name) and everything under
// composeResources/** (stable names — fonts, flags, …) are not, and must never be marked immutable, or
// a changed string table/flag/font would never reach a browser that had already cached the old one.
private val HASHED_NAME = Regex("[0-9a-f]{16,}")

// FR-235-2/4 — compress-on-the-wire-eligible extensions; .gz/.br siblings (precompressed at build time
// by :precompressWasmJsDistribution) carry the *underlying* type below, never their own.
private val COMPRESSIBLE_EXTENSIONS = setOf("wasm", "js", "mjs", "css", "json", "webmanifest", "svg", "html", "txt", "map")

fun main() {
    val dir = env("STATIC_DIR", "/srv")
    val port = env("SERVER_PORT", "8080").toIntOrNull() ?: 8080
    // R225 — ravilo-web has no env access of its own (compiled wasmJs); this is the only runtime
    // process in front of it, and the natural place to hand it an operator-configured default server
    // (e.g. the demo stack, where ravilo-web and its backend are on different origins so the wasmJs
    // actual's same-origin fallback is wrong). Unset ⇒ /runtime-config.js is empty, no behavior change.
    val defaultServerUrl = env("DEFAULT_SERVER_URL", "").ifBlank { null }
    // Phase 224 (FR-224-6) — the version this image was published as (BUILD_VERSION → RAVILO_VERSION),
    // handed to the wasm app the same way. R252 FR-R252-3 reads it in preference to the version
    // compiled into the bundle, which in an image is always "dev" (no .git in the build context, by
    // design — it keeps a release build a cache hit).
    val raviloVersion = env("RAVILO_VERSION", "").ifBlank { null }
    // Phase 249 — see cspHeader()'s own doc comment. Default off; no real deployment sets this.
    val allowHttpConnect = env("CSP_ALLOW_HTTP_CONNECT", "").isNotBlank()

    embeddedServer(
        CIO,
        configure = {
            connectors.add(EngineConnectorBuilder().apply { this.port = port })
            connectionIdleTimeoutSeconds = 10
        },
    ) {
        // FR-235-5 — every GET route answers HEAD with the same headers and no body.
        install(AutoHeadResponse)

        // FR-235-6 — the same security posture as the backend's Server.kt sends for /tv/**, so the two
        // ways of serving this bundle can't drift; manifest-src/worker-src are the two additions this
        // phase specifically wants explicit (R263's manifest + service worker).
        intercept(ApplicationCallPipeline.Plugins) {
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("Referrer-Policy", "strict-origin-when-cross-origin")
            call.response.headers.append("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
            call.response.headers.append("X-Frame-Options", "DENY")
            call.response.headers.append("Content-Security-Policy", cspHeader(allowHttpConnect))
            proceed()
        }

        routing {
            // FR-235-8 — generated per request from the two env vars; application/javascript, no-cache,
            // empty when neither is set. Registered ahead of the catch-all so it never falls through to
            // the SPA-fallback / 404 logic below (there is no such file on disk).
            get("/runtime-config.js") {
                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondText(runtimeConfigJs(defaultServerUrl, raviloVersion), ContentType.Application.JavaScript)
            }
            get("{...}") {
                call.serveStaticFile(dir, call.request.path())
            }
        }
    }.start(wait = true)
}

// FR-235-8 — replaces the old HTML-mutating injectRuntimeConfig: index.html is now served byte-identical
// to the bundle's own in every deployment, and this is what index.html's static
// <script src="runtime-config.js"> tag loads instead.
private fun runtimeConfigJs(defaultServerUrl: String?, raviloVersion: String?): String {
    fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    return buildList {
        defaultServerUrl?.let { add("window.__RAVILO_DEFAULT_SERVER__=${jsString(it)};") }
        raviloVersion?.let { add("window.__RAVILO_VERSION__=${jsString(it)};") }
    }.joinToString("")
}

private suspend fun ApplicationCall.serveStaticFile(dir: String, requestPath: String) {
    val rel = requestPath.trimStart('/').ifEmpty { "index.html" }

    if (".." in rel) {
        respond(HttpStatusCode.BadRequest)
        return
    }

    val target = Path("$dir/$rel")
    if (SystemFileSystem.exists(target)) {
        serveBytes(dir, rel)
        return
    }

    // FR-235-3 — the SPA fallback applies only to routes: an asset-shaped path (its last segment
    // contains a '.') that doesn't exist is a real 404, never index.html. Ravilo routes on the URL
    // hash, which never reaches the server, so an extension-less path is just "the app itself, at a
    // deep link".
    if ('.' in rel.substringAfterLast('/')) {
        respond(HttpStatusCode.NotFound)
        return
    }
    if (SystemFileSystem.exists(Path("$dir/index.html"))) {
        serveBytes(dir, "index.html")
    } else {
        respond(HttpStatusCode.NotFound)
    }
}

// kotlinx-io's SystemFileSystem.source() has no finalizer — an unclosed source leaks one FD per call.
// .use{} scoping is mandatory (same rule this repo's FileIo.kt documents and enforces for the main
// backend); this module is small enough not to need its own copy of that whole helper object.
private fun readFile(path: Path): ByteArray =
    SystemFileSystem.source(path).buffered().use { it.readByteArray() }

/** Parses an Accept-Encoding header into the bare coding tokens ("br, gzip;q=0.8" → {"br","gzip"}) —
 *  q-values are not distinguished; a client offering both is assumed to prefer them in list order,
 *  which every real browser already does (br before gzip). */
private fun acceptedEncodings(header: String?): Set<String> =
    header?.split(",")?.map { it.substringBefore(';').trim().lowercase() }?.toSet() ?: emptySet()

private suspend fun ApplicationCall.serveBytes(dir: String, rel: String) {
    val accepted = acceptedEncodings(request.headers[HttpHeaders.AcceptEncoding])
    // FR-235-2 — precompressed siblings only (:precompressWasmJsDistribution, build time); never
    // compress at request time on a Kotlin/Native process with no zlib binding reachable from here.
    val (bytes, contentEncoding, etagSuffix) = when {
        rel.substringAfterLast('.') in COMPRESSIBLE_EXTENSIONS && "br" in accepted && SystemFileSystem.exists(Path("$dir/$rel.br")) ->
            Triple(readFile(Path("$dir/$rel.br")), "br", "-br")
        rel.substringAfterLast('.') in COMPRESSIBLE_EXTENSIONS && "gzip" in accepted && SystemFileSystem.exists(Path("$dir/$rel.gz")) ->
            Triple(readFile(Path("$dir/$rel.gz")), "gzip", "-gz")
        else -> Triple(readFile(Path("$dir/$rel")), null, "")
    }

    // FR-235-2 — a cache sitting between an encoding-aware server and a client must know the response
    // varies by this header, always (not only when a sibling was actually served) — a first request
    // with no Accept-Encoding must not poison a shared cache for one that has it.
    response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
    if (contentEncoding != null) response.headers.append(HttpHeaders.ContentEncoding, contentEncoding)

    // FR-235-1 — immutable iff the *logical* filename (never the .gz/.br sibling's own name) carries a
    // real content hash; index.html/manifest.webmanifest/sw.js/boot.js/runtime-config.js and every
    // stable-named asset (ravilo.js, composeResources/**) revalidate every time (an ETag 304 is cheap;
    // R263's service worker removes even that). Ktor's own CacheControl.MaxAge has no `immutable` field
    // (checked against its 3.6.0 class file — maxAgeSeconds/proxyMaxAgeSeconds/mustRevalidate/
    // proxyRevalidate/visibility only), so the header is built by hand instead of via response.cacheControl().
    response.headers.append(
        HttpHeaders.CacheControl,
        if (HASHED_NAME.containsMatchIn(rel.substringAfterLast('/'))) "public, max-age=31536000, immutable" else "no-cache",
    )

    // Dev review item 4 — headers (Cache-Control, Vary) must be set before a 304 short-circuit, not
    // after; the old code returned before ever reaching them.
    val etag = "\"${bytes.crc32Hex()}$etagSuffix\""
    if (request.headers[HttpHeaders.IfNoneMatch] == etag) {
        respond(HttpStatusCode.NotModified)
        return
    }
    response.headers.append(HttpHeaders.ETag, etag)
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

// FR-235-4 — a .gz/.br sibling is stripped back to its underlying path before this runs (serveBytes
// looks up contentTypeFor(rel), never rel + ".gz"), so this only ever needs to know the real types.
private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.').lowercase()) {
    "html"         -> ContentType.Text.Html
    "css"          -> ContentType.Text.CSS
    "js", "mjs"    -> ContentType.Application.JavaScript
    "wasm"         -> ContentType.parse("application/wasm")
    "json", "map"  -> ContentType.Application.Json
    "webmanifest"  -> ContentType.parse("application/manifest+json")
    "txt"          -> ContentType.Text.Plain
    "png"          -> ContentType.Image.PNG
    "jpg", "jpeg"  -> ContentType.Image.JPEG
    "svg"          -> ContentType.Image.SVG
    "ico"          -> ContentType.parse("image/x-icon")
    "woff2"        -> ContentType.parse("font/woff2")
    else           -> ContentType.Application.OctetStream
}

// FR-235-6 — byte-for-byte Server.kt's own site-wide policy (which the /tv/** path already carries,
// since that intercept isn't scoped by path there either — frame-src's YouTube/Vimeo origins included,
// even though ravilo-web itself never embeds a trailer, because the acceptance test compares this
// against that exact string) plus manifest-src/worker-src (both already fall back to default-src
// 'self'; explicit so a later default-src tightening can't silently break the install — dev review item
// 6). No CDN host: R265 has not shipped yet, so the web player's CDN loads fail under this exactly as
// they already fail on /tv/** today, which is the point (dev review item 3) — FR-235-9 self-hosts
// hls.js/JASSUB in this same release so nothing actually breaks.
//
// Phase 249 — [allowHttpConnect] is off for every real deployment (ravilo-web included), so the
// emitted string stays byte-for-byte what it always was, preserving the comparison above. It exists
// only for this module's OTHER caller — the ravilo-screen test-stack container (247/248) — whose mock
// backend talks plain http, which browsers refuse under connect-src without an explicit http: scheme.
// ravilo-screen is never served over http in production at all (its only real distribution is the
// packaged .wgt), so this changes nothing about ravilo-web's or the backend's real security posture.
internal fun cspHeader(allowHttpConnect: Boolean): String =
    "default-src 'self'; " +
        "script-src 'self' 'wasm-unsafe-eval' 'unsafe-eval'; " +
        "style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data: blob: https:; " +
        "font-src 'self' data:; " +
        "connect-src 'self' ws: wss: https:${if (allowHttpConnect) " http:" else ""}; " +
        "media-src 'self' blob: https:; " +
        "manifest-src 'self'; " +
        "worker-src 'self'; " +
        "frame-src https://www.youtube-nocookie.com https://player.vimeo.com; " +
        "object-src 'none'; " +
        "frame-ancestors 'none'; " +
        "base-uri 'self'"
