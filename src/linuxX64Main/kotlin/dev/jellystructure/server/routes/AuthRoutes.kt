package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.LoginRequest
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.auth.SessionService
import dev.jellystructure.auth.UserProfile
import dev.jellystructure.config.ConfigStore
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.authRoutes(
    sessionService: SessionService,
    jellyfinClient: JellyfinClient,
    configStore: ConfigStore,
) {
    route("/auth") {
        post("/login") {
            val creds = call.receive<LoginRequest>()
            val config = configStore.current

            if (config.apiKeys.jellyfinUrl.isBlank()) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("error" to "Jellyfin URL not configured — complete setup first"),
                )
                return@post
            }

            val authResult = runCatching {
                jellyfinClient.authenticateByName(
                    config.apiKeys.jellyfinUrl,
                    creds.username,
                    creds.password,
                )
            }.getOrElse { e ->
                println("jellyfin: ${config.apiKeys.jellyfinUrl}")
                println("[WARN] Jellyfin auth error: ${e.message}")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to (e.message ?: "Authentication failed")),
                )
                return@post
            }

            if (!authResult.user.policy.isAdministrator) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "Jellystructure requires a Jellyfin administrator account"),
                )
                return@post
            }

            val token = sessionService.create(
                authResult.user.id,
                authResult.user.name,
                authResult.accessToken,
            )

            call.response.cookies.append(
                Cookie(
                    name = "js_session",
                    value = token,
                    httpOnly = true,
                    path = "/",
                    maxAge = 7 * 24 * 60 * 60,
                    extensions = mapOf("SameSite" to "Lax"),
                ),
            )
            call.respond(UserProfile(authResult.user.id, authResult.user.name))
        }

        post("/logout") {
            val token = call.request.cookies["js_session"]
            if (token != null) sessionService.revoke(token)
            call.response.cookies.append(
                Cookie(name = "js_session", value = "", path = "/", maxAge = 0, httpOnly = true),
            )
            call.respond(HttpStatusCode.NoContent)
        }

        get("/me") {
            val session = call.attributes[SessionKey]
            call.respond(UserProfile(session.jellyfinUserId, session.jellyfinUsername))
        }
    }
}
