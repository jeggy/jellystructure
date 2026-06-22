package dev.jellystructure.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey

val SessionKey = AttributeKey<SessionData>("JsSession")
val DeviceKey = AttributeKey<DeviceData>("RaviloDevice")

private val OPEN_API_PATHS = listOf(
    "/api/auth/login",
    "/api/setup",
    "/api/tv/pair/start",
    "/api/tv/pair/poll",
    // /api/tv/pair/approve is open at the plugin level; the route handler checks for
    // a valid cookie session or direct Jellyfin credentials itself.
    "/api/tv/pair/approve",
    // /api/tv/events is the live-config WebSocket (R33); browsers can't send a bearer header on the
    // handshake, so the route validates a device token from the query string itself.
    "/api/tv/events",
)

fun Application.installAuthPlugin(
    sessionService: SessionService,
    validateDeviceToken: ((String) -> DeviceData?)? = null,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()

        if (!path.startsWith("/api/")) {
            proceed()
            return@intercept
        }

        if (OPEN_API_PATHS.any { path.startsWith(it) }) {
            proceed()
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
