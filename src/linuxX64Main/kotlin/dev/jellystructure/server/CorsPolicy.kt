package dev.jellystructure.server

import io.ktor.http.HttpMethod
import io.ktor.server.plugins.cors.CORSConfig

/**
 * Phase 247 (FR-247-3) — the origin Tizen's web runtime sends on a WebSocket handshake from a packaged
 * widget, measured on the Tizen 10.0 TV emulator 2026-09-25. Its `fetch` sends no Origin at all; only the
 * handshake carries one. No web page can send this value — desktop browsers serialise a `file:` document's
 * origin as `null` — which is why it can be admitted where `null` never may (any page can forge `null`
 * with a sandboxed iframe).
 */
internal const val TIZEN_WIDGET_ORIGIN = "file://"

/**
 * The one CORS policy, installed at the routing root.
 *
 * Security fix (2026-08-02 review, finding M1) — this was `anyHost()` + `allowCredentials = true`.
 * Verified against the Ktor 3.5.0 CORS plugin source
 * (`val headerOrigin = if (allowsAnyHost && !allowCredentials) "*" else origin`): with credentials on,
 * Ktor does NOT send `*` back — it REFLECTS the caller's Origin and adds
 * `Access-Control-Allow-Credentials: true`. Confirmed live during the audit (an
 * Origin: https://evil.example.com preflight got that Origin echoed back). The only thing stopping full
 * cross-origin credentialed access today is `SameSite=Lax` on the js_session cookie — one attribute away
 * from account takeover, with no CSRF token as a second layer.
 *
 * Same-origin requests (the normal deployment: this server serves its own admin/Ravilo-web frontends)
 * need no CORS headers at all. The only legitimate cross-origin case is a separately-hosted dev server
 * (webpack/vite) during local development — allowed explicitly via CORS_ALLOWED_ORIGINS
 * (comma-separated "host:port", e.g. "localhost:8080,localhost:5173"), never via a blanket wildcard.
 */
internal fun CORSConfig.jellystructurePolicy(extraOrigins: List<String>) {
    extraOrigins.forEach { origin -> allowHost(origin, schemes = listOf("http", "https")) }
    allowHeaders { true }              // includes Authorization (Bearer device token), Content-Type, Cookie…
    allowNonSimpleContentTypes = true  // application/json request bodies
    allowMethod(HttpMethod.Get)
    allowMethod(HttpMethod.Head)
    allowMethod(HttpMethod.Post)
    allowMethod(HttpMethod.Put)
    allowMethod(HttpMethod.Delete)
    allowMethod(HttpMethod.Patch)
    allowMethod(HttpMethod.Options)
    allowCredentials = true
}

/**
 * Phase 247 (FR-247-3) — `/api/tv/events` only: the shared policy, plus the Tizen widget's origin.
 *
 * A packaged Tizen widget's WebSocket handshake carries `Origin: file://`, which the shared policy
 * refuses with a 403 — so a paired Samsung TV could never receive a `play_item`. This route reads only a
 * device token (query or `Authorization`), never a cookie, so an origin on it gates nothing a caller
 * could not already do with the token; the cookie-authenticated admin `/ws` keeps the shared policy.
 *
 * The shared policy is restated rather than inherited because a child route's CORS REPLACES its
 * parent's (measured) — without it this route would silently drop CORS_ALLOWED_ORIGINS.
 */
internal fun CORSConfig.tvEventsPolicy(extraOrigins: List<String>) {
    jellystructurePolicy(extraOrigins)
    allowOrigins { it == TIZEN_WIDGET_ORIGIN }
}

/** CORS_ALLOWED_ORIGINS, parsed once for both policies. */
internal fun corsAllowedOrigins(): List<String> =
    dev.jellystructure.env("CORS_ALLOWED_ORIGINS", "").split(",").map { it.trim() }.filter { it.isNotBlank() }
