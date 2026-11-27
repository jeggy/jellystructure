package dev.jellystructure.server

// FR-6 (DEB-5847): an /api request whose handler runs over 1 s writes one INFO line naming its route
// template and milliseconds, so slow routes are read from the log. Image, artwork, stream and radio
// routes are excluded (their duration is the client's download speed), and so is /ws.
internal const val SLOW_REQUEST_MS = 1000L

private val excludedParts = listOf("/image/", "/image", "/artwork", "/stream", "/hls/", "/logo", "/radio/", "/poster", "/download")

fun slowRequestLine(method: String, template: String, status: Int, ms: Long): String? {
    if (ms <= SLOW_REQUEST_MS) return null
    if (!template.startsWith("/api/")) return null
    if (excludedParts.any { template.contains(it) }) return null
    return "slow request: $method $template $status ${ms}ms"
}

/** Ktor's `Route.toString()` is `/api/x/{id}/(method:GET)`; keep only the path segments. */
fun routeTemplate(raw: String): String =
    raw.split('/').filterNot { it.startsWith("(") }.joinToString("/").ifEmpty { "/" }
