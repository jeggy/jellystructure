package dev.jellystructure.server

import io.ktor.http.HttpMethod
import io.ktor.server.plugins.cors.CORSConfig
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

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
 * Phase 247 (FR-247-3, FR-247-4) — the widget policy: the shared policy, plus the Tizen widget's origin.
 *
 * A packaged Tizen widget's WebSocket handshake (10.0) and every non-GET request (5.0, Chromium 63) carry
 * `Origin: file://`, which the shared policy refuses with a 403 — so a paired Samsung TV could never receive
 * a `play_item` and could never sign in. The routes it is installed over read only a device token or an API
 * key (AuthPlugin's token branches), never a cookie, so an origin on them gates nothing a caller could not
 * already do with the token; the cookie-authenticated admin `/ws` and the admin routes under `/api/tv/admin` keep the shared policy.
 *
 * The shared policy is restated rather than inherited because a child route's CORS REPLACES its
 * parent's (measured) — without it these routes would silently drop CORS_ALLOWED_ORIGINS.
 */
internal fun CORSConfig.tizenWidgetPolicy(extraOrigins: List<String>) {
    jellystructurePolicy(extraOrigins)
    allowOrigins { it == TIZEN_WIDGET_ORIGIN }
}

/**
 * Phase 247 (FR-247-5) — an origin Chromium 63 (Tizen 5.0) sends on a fetch/XHR POST from a packaged widget, measured
 * on the 5.0 emulator 2026-11-16 (DEB-4721): the literal `null`, where its WebSocket handshake sends [TIZEN_WIDGET_ORIGIN].
 * Any web page can forge it (a sandboxed iframe), so it is admitted only on routes that read no cookie — see
 * [tizenWidgetNullPolicy] — and the condition is pinned by `CorsPolicyTest`.
 */
internal const val OPAQUE_ORIGIN = "null"

/**
 * Phase 247 (FR-247-5) — the widget policy, plus [OPAQUE_ORIGIN]. Over `/api/tv` only, never over a route that takes a
 * password or reads a cookie: a page that forges `null` could otherwise spread password guesses across the per-address
 * rate limit of its visitors. `/api/remote` stays on [tizenWidgetPolicy].
 */
internal fun CORSConfig.tizenWidgetNullPolicy(extraOrigins: List<String>) {
    jellystructurePolicy(extraOrigins)
    allowOrigins { it == TIZEN_WIDGET_ORIGIN || it == OPAQUE_ORIGIN }
}

/**
 * Phase 247 (FR-247-4, FR-247-5) — installs the policies the way the server wants them: the shared one at [this] (the
 * routing root); over `/api/tv` the `null` policy, with `/api/tv/admin` back on the shared one (it authenticates by
 * cookie) and `/api/tv/login` and `/api/tv/account/password` (the two that take a password) on the `file://`-only widget
 * policy; and the `file://`-only widget policy over `/api/remote`. A route that ever reads a cookie under `/api/tv`
 * re-installs a narrower policy the way these do. Called before the `/api` routes are registered so the nodes exist
 * first; the routes then join them by path.
 */
internal fun Route.installCorsPolicies(extraOrigins: List<String>) {
    install(CORS) { jellystructurePolicy(extraOrigins) }
    route("/api/tv") {
        install(CORS) { tizenWidgetNullPolicy(extraOrigins) }
        route("/admin") { install(CORS) { jellystructurePolicy(extraOrigins) } }
        route("/login") { install(CORS) { tizenWidgetPolicy(extraOrigins) } }
        route("/account/password") { install(CORS) { tizenWidgetPolicy(extraOrigins) } }
    }
    route("/api/remote") { install(CORS) { tizenWidgetPolicy(extraOrigins) } }
}

/** CORS_ALLOWED_ORIGINS, parsed once for both policies. */
internal fun corsAllowedOrigins(): List<String> =
    dev.jellystructure.env("CORS_ALLOWED_ORIGINS", "").split(",").map { it.trim() }.filter { it.isNotBlank() }
