package dev.jellystructure.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.httpMethod
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

// DEB-1508 (FR-3) — a request that presented Hervør's support seat token carries the token's id here, and no SessionKey.
// The route reads it to write as HERVOR; a handler that finds it knows the call is the seat's and not an admin's.
val SupportSeatKey = AttributeKey<String>("SupportSeatToken")

/** DEB-1508 (FR-3) — what a `jss_` bearer comes to. Unknown or revoked → 401; live with the switch off → 403; live and on → the call is judged against [SEAT_ROUTES]. */
// Plain objects, compared with `==`: on Kotlin/Native a `data object` answered `is` for its sibling (`Disabled is Invalid` read true).
sealed interface SeatTokenCheck {
    object Invalid : SeatTokenCheck
    object Disabled : SeatTokenCheck
    class Live(val tokenId: String) : SeatTokenCheck
}

const val SEAT_TOKEN_PREFIX = "jss_"

private val SEAT_TICKET = Regex("^/api/support/tickets/[^/]+$")
private val SEAT_TICKET_MESSAGES = Regex("^/api/support/tickets/[^/]+/messages$")
private val SEAT_TICKET_FORWARD = Regex("^/api/support/tickets/[^/]+/forward$")

/**
 * DEB-1508 (FR-4) — the five calls the seat token may make, and nothing else. Method and the *decoded* routing path, the same
 * one every other branch matches on. A ticket outside the seat's reach on one of these is the route's 404, not this 403.
 */
internal fun seatMayCall(method: io.ktor.http.HttpMethod, path: String): Boolean = when (method) {
    io.ktor.http.HttpMethod.Get -> path == "/api/support/summary" || path == "/api/support/tickets" || SEAT_TICKET.matches(path)
    io.ktor.http.HttpMethod.Post -> SEAT_TICKET_MESSAGES.matches(path) || SEAT_TICKET_FORWARD.matches(path)
    else -> false
}

// Phase 236 (FR-236-3, dev review item 5) — every /api/remote/ route now accepts either credential;
// each one reads this one attribute instead of ApiKeyAttr directly. deviceId is null for an API-key
// caller (an API key is bound to a user, not a device) and non-null for a device-token caller —
// viaApiKey is what /api/remote/pair refuses (FR-236-2: an API key has no Jellyfin user token to copy
// onto a receiver's row).
// (Line comments on purpose: Kotlin block comments nest, so a literal "/*" inside "/api/remote/**"
// would open an inner comment that never closes and breaks the whole file — the exact hazard
// web-static-server/Main.kt's own file header already documents.)
data class RemoteCaller(val jellyfinUserId: String, val deviceId: String?, val viaApiKey: Boolean)
val RemoteCallerAttr = AttributeKey<RemoteCaller>("RemoteCaller")

private val OPEN_API_PATHS = listOf(
    "/api/auth/login",
    "/api/setup",
    // Phase 141 — no device token exists yet at sign-in; the route itself authenticates the
    // credentials against Jellyfin before minting one. Retires /api/tv/pair/{start,poll,approve}.
    "/api/tv/login",
    // Phase 218 (FR-218-9) — a Chromecast receiver redeems its hand-off code here to GET its device token,
    // so it cannot present one yet. The code is single-use, minutes-lived and minted by an authenticated
    // phone session; the route is rate-limited by the same LoginRateLimiter as /api/tv/login.
    "/api/tv/cast/redeem",
    // /api/tv/events is the live-config WebSocket (R33); browsers can't send a bearer header on the
    // handshake, so the route validates a device token from the query string itself.
    "/api/tv/events",
    // Phase 236 (FR-236-2, dev review item 1) — the receiver-shows-a-code pairing flow. Both open
    // (a receiver holds no credential yet) and rate-limited by the same LoginRateLimiter as /tv/login,
    // same reasoning as /tv/cast/redeem above.
    "/api/tv/screen/code",
    "/api/tv/screen/claim",
    // DEB-2233 (FR-2) — a TV that holds no credential mints a sign-in code and polls for the approval. Only these two: the same
    // prefix's preview and approve routes take a device token. Each has its own rate-limit bucket (FR-16), not /api/tv/login's.
    "/api/tv/signin/code",
    "/api/tv/signin/poll",
    // Phase 236 (FR-236-3) — the API-caller status stream is a WebSocket; same reasoning as
    // /api/tv/events above (a browser/HA client can't set a header on the handshake), validated from
    // the query string inside the route itself, either credential.
    "/api/remote/events",
    // /api/tv/channel-logos/<file> serves channel-button brand logos (R36); not sensitive, and the TV
    // <img>/Coil loader can't attach a device token. Admin upload/list stays at /api/tv/admin/...
    "/api/tv/channel-logos/",
    // /api/tv/image/{itemId}/{type} is the R85 Jellyfin image proxy cache. Images are not sensitive
    // and Coil can't attach a device token to image requests.
    "/api/tv/image/",
    // Phase 147 — Live TV channel logos (proxied from Jellyfin's ImageTags.Primary); same reasoning as
    // channel-logos/image above — not sensitive, and an <img>/Coil request can't carry a device token.
    "/api/tv/livetv/logo/",
    // R291 (FR-R291-2) — the composed HLS master a player fetches for a transcode with several audio
    // tracks. Media3/hls.js cannot attach a device token to a playlist fetch; the path's 128-bit random
    // id is the capability, lives as long as the ticket, and what it returns carries only Jellyfin's own
    // credential the ticket's `hls_url` already carries.
    "/api/tv/stream/",
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
    // Phase 224 (FR-224-2): the request's X-Ravilo-Version / X-Ravilo-Platform ride along so the device
    // row can be updated on change — the token is durable across app updates, so this is the only
    // moment the backend ever learns which build is speaking.
    validateDeviceToken: ((token: String, appVersion: String?, platform: String?) -> DeviceData?)? = null,
    // Phase 111 — least-privilege by design: an API key is accepted ONLY for /api/remote/**, never for
    // the admin/media/TV surfaces. Widening that is a deliberate future decision, not a default.
    validateApiKey: ((String) -> ApiKeyData?)? = null,
    // DEB-1508 (FR-3) — Hervør's support seat token. Null (the tests that do not care) fails closed: every `jss_` bearer is a 401.
    validateSeatToken: ((String) -> SeatTokenCheck)? = null,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        // R225 amendment (2026-09-18) — a CORS preflight never carries credentials (the browser strips
        // Authorization and cookies from it by design), so asking it for a token answers 401 to every
        // authenticated route and the browser then blocks the real request: a cross-origin Ravilo web
        // could sign in (an open path) and do nothing else. A preflight runs no handler and returns no
        // data — the CORS plugin answers it, 403 for an origin that is not allowed.
        if (call.request.httpMethod == io.ktor.http.HttpMethod.Options &&
            call.request.headers["Origin"] != null &&
            call.request.headers["Access-Control-Request-Method"] != null
        ) return@intercept
        val rawPath = call.request.path()
        val path = decodeRoutingPath(rawPath)
        if (path == null) {
            // Same malformed-encoding rejection routing itself would apply — fail closed rather
            // than falling through to native-path (raw) matching, which is the bug this fixes.
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Malformed request path"))
            finish()
            return@intercept
        }

        // DEB-1508 (FR-3) — a `jss_` bearer is judged as a seat call on every /api/** path and on the /ws upgrade, before
        // any other branch: the open paths and /api/health included, and never falling through to the cookie branch.
        // That is what makes "anything outside its scope is refused with 403" hold on routes that would otherwise answer
        // 401 to an unknown bearer, or not ask for one at all. An admin's browser never sends a bearer.
        val seatBearer = call.request.headers["Authorization"]
            ?.takeIf { it.startsWith("Bearer $SEAT_TOKEN_PREFIX") }?.removePrefix("Bearer ")
        if (seatBearer != null && (path.startsWith("/api/") || path == "/ws")) {
            val check = validateSeatToken?.invoke(seatBearer) ?: SeatTokenCheck.Invalid
            // Logged as status, code and path — never the token, never its hash, never a body (FR-14).
            suspend fun refuse(status: HttpStatusCode, code: String) {
                dev.jellystructure.log.Logger.warn("Support seat call refused: ${status.value} $code ${call.request.httpMethod.value} $path", "support")
                call.respond(status, mapOf("error" to code))
                finish()
            }
            when {
                check === SeatTokenCheck.Invalid -> refuse(HttpStatusCode.Unauthorized, "seat_token_invalid")
                check === SeatTokenCheck.Disabled -> refuse(HttpStatusCode.Forbidden, "hervor_disabled")
                check !is SeatTokenCheck.Live || !seatMayCall(call.request.httpMethod, path) -> refuse(HttpStatusCode.Forbidden, "not_for_seat")
                else -> {
                    call.attributes.put(SupportSeatKey, check.tokenId)
                    proceed()
                }
            }
            return@intercept
        }

        if (!path.startsWith("/api/")) {
            proceed()
            return@intercept
        }

        // Exact match, not prefix — the lightweight liveness probe (fd_count/high_water_mark only,
        // see Server.kt's own "for a monitoring scraper" comment) is meant to be hit by container/
        // orchestration health checks that can't carry a session cookie. /api/health/full is a
        // DIFFERENT, deliberately admin-only endpoint (exposes *arr/TMDB connectivity + config state)
        // and must NOT be swept in by a prefix match here.
        if (path == "/api/health") {
            proceed()
            return@intercept
        }

        if (OPEN_API_PATHS.any { path.startsWith(it) }) {
            proceed()
            return@intercept
        }

        // Phase 236 (FR-236-3, dev review item 5) — one device-control API, two credentials: an API key
        // (bound to a user) or a Ravilo device token (bound to a device+user) — both `Bearer`, tried in
        // that order so an existing Home Assistant integration's request shape is unchanged.
        if (path.startsWith("/api/remote/") && (validateApiKey != null || validateDeviceToken != null)) {
            val bearer = call.request.headers["Authorization"]
                ?.takeIf { it.startsWith("Bearer ") }
                ?.removePrefix("Bearer ")
                ?: call.request.headers["X-JS-Api-Key"]

            val keyData = bearer?.let { validateApiKey?.invoke(it) }
            if (keyData != null) {
                call.attributes.put(ApiKeyAttr, keyData)
                call.attributes.put(RemoteCallerAttr, RemoteCaller(keyData.jellyfinUserId, deviceId = null, viaApiKey = true))
                proceed()
                return@intercept
            }

            val device = bearer?.let {
                validateDeviceToken?.invoke(
                    it,
                    call.request.headers[dev.jellystructure.shared.RaviloHeaders.VERSION],
                    call.request.headers[dev.jellystructure.shared.RaviloHeaders.PLATFORM],
                )
            }
            if (device != null) {
                call.attributes.put(DeviceKey, device)
                call.attributes.put(RemoteCallerAttr, RemoteCaller(device.jellyfinUserId, deviceId = device.deviceId, viaApiKey = false))
                proceed()
                return@intercept
            }

            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing API key or device token"))
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

            val device = bearer?.let {
                validateDeviceToken(
                    it,
                    call.request.headers[dev.jellystructure.shared.RaviloHeaders.VERSION],
                    call.request.headers[dev.jellystructure.shared.RaviloHeaders.PLATFORM],
                )
            }
            if (device != null) {
                call.attributes.put(DeviceKey, device)
                proceed()
                return@intercept
            }

            // DEB-2233 (FR-3) — an API key on the sign-in approval is a known credential used where it has no meaning: it acts as a
            // user but holds no sign-in of its own to approve with, so it is refused rather than treated as unauthenticated.
            if (path.startsWith("/api/tv/signin/") && bearer?.let { validateApiKey?.invoke(it) } != null) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "This credential can't sign a TV in"))
                finish()
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
