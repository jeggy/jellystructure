package dev.jellystructure.auth

import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey

val SessionKey = AttributeKey<SessionData>("JsSession")
val DeviceKey = AttributeKey<DeviceData>("RaviloDevice")
val ApiKeyAttr = AttributeKey<ApiKeyData>("RaviloApiKey")

private val OPEN_API_PATHS = listOf(
    "/api/auth/login",
    "/api/setup",
    // Phase 141 — no device token exists yet at sign-in; the route itself authenticates the
    // credentials against Jellyfin before minting one. Retires /api/tv/pair/{start,poll,approve}.
    "/api/tv/login",
    // /api/tv/events is the live-config WebSocket (R33); browsers can't send a bearer header on the
    // handshake, so the route validates a device token from the query string itself.
    "/api/tv/events",
    // /api/tv/channel-logos/<file> serves channel-button brand logos (R36); not sensitive, and the TV
    // <img>/Coil loader can't attach a device token. Admin upload/list stays at /api/tv/admin/...
    "/api/tv/channel-logos/",
    // /api/tv/image/{itemId}/{type} is the R85 Jellyfin image proxy cache. Images are not sensitive
    // and Coil can't attach a device token to image requests.
    "/api/tv/image/",
    // Phase 147 — Live TV channel logos (proxied from Jellyfin's ImageTags.Primary); same reasoning as
    // channel-logos/image above — not sensitive, and an <img>/Coil request can't carry a device token.
    "/api/tv/livetv/logo/",
    // Phase 114 — *arr webhooks: authenticated by their own per-install secret query param, since *arr's
    // webhook sender can't attach a cookie/device-token/API-key like every other caller.
    "/api/webhooks/",
)

/**
 * Security fix (2026-08-02 review, finding C1) — [installAuthPlugin] used to prefix-match
 * `call.request.path()` directly, which is Ktor's RAW, still-percent-encoded request path
 * (`ApplicationRequestProperties.kt`: `origin.uri.substringBefore('?')`). The routing layer that
 * actually dispatches the request decodes each `/`-separated segment first
 * (`RoutingResolveContext.parse` → `String.decodeURLPart()`). The two disagreed: a request to
 * `/%61pi/config` doesn't start with `/api/` under raw prefix matching, so the old code let it
 * through with NO auth check at all — while routing decoded it to `/api/config` and dispatched the
 * fully-privileged handler. Full unauthenticated read/write of the entire config, media library,
 * and more.
 *
 * Decoding each segment here — identically to how routing will decode it — closes that gap:
 * whichever route ends up serving the request is exactly the route this plugin authorized. A
 * malformed percent-escape (which routing itself would reject with 400) fails closed here too,
 * before any auth tier is even considered.
 */
private fun decodeRoutingPath(raw: String): String? {
    if (raw.isEmpty() || raw == "/") return raw
    return runCatching { raw.split('/').joinToString("/") { it.decodeURLPart() } }.getOrNull()
}

fun Application.installAuthPlugin(
    sessionService: SessionService,
    validateDeviceToken: ((String) -> DeviceData?)? = null,
    // Phase 111 — least-privilege by design: an API key is accepted ONLY for /api/remote/**, never for
    // the admin/media/TV surfaces. Widening that is a deliberate future decision, not a default.
    validateApiKey: ((String) -> ApiKeyData?)? = null,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        val rawPath = call.request.path()
        val path = decodeRoutingPath(rawPath)
        if (path == null) {
            // Same malformed-encoding rejection routing itself would apply — fail closed rather
            // than falling through to native-path (raw) matching, which is the bug this fixes.
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Malformed request path"))
            finish()
            return@intercept
        }

        if (!path.startsWith("/api/")) {
            proceed()
            return@intercept
        }

        if (OPEN_API_PATHS.any { path.startsWith(it) }) {
            proceed()
            return@intercept
        }

        if (path.startsWith("/api/remote/") && validateApiKey != null) {
            val bearer = call.request.headers["Authorization"]
                ?.takeIf { it.startsWith("Bearer ") }
                ?.removePrefix("Bearer ")
                ?: call.request.headers["X-JS-Api-Key"]

            val keyData = bearer?.let { validateApiKey(it) }
            if (keyData != null) {
                call.attributes.put(ApiKeyAttr, keyData)
                proceed()
                return@intercept
            }

            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing API key"))
            finish()
            return@intercept
        }

        // /api/tv/admin/** is the operator-facing config surface (jellystructure web app); it is
        // authenticated by the admin cookie session, not a TV device token — fall through to the
        // cookie-session branch below.
        // /api/tv/** (excluding admin + the open paths above): must carry a device token.
        if (path.startsWith("/api/tv/") && !path.startsWith("/api/tv/admin/") && validateDeviceToken != null) {
            val bearer = call.request.headers["Authorization"]
                ?.takeIf { it.startsWith("Bearer ") }
                ?.removePrefix("Bearer ")
                ?: call.request.headers["X-Ravilo-Device"]

            val device = bearer?.let { validateDeviceToken(it) }
            if (device != null) {
                call.attributes.put(DeviceKey, device)
                proceed()
                return@intercept
            }

            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing device token"))
            finish()
            return@intercept
        }

        // All other /api/** routes: require cookie session.
        val token = call.request.cookies["js_session"]
        val session = token?.let { sessionService.validate(it) }

        if (session == null) {
            call.respond(
                HttpStatusCode.Unauthorized,
                mapOf("error" to "Not authenticated"),
            )
            finish()
            return@intercept
        }

        call.attributes.put(SessionKey, session)
        proceed()
    }
}
