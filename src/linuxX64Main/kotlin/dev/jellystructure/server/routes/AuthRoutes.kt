package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.LoginRateLimiter
import dev.jellystructure.auth.LoginRequest
import dev.jellystructure.log.Logger
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.auth.SessionService
import dev.jellystructure.auth.UserProfile
import dev.jellystructure.config.ConfigStore
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
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
    // Security fix (2026-08-02 review, finding H4) — was completely unbounded, letting the internet
    // brute-force every Jellyfin account through this proxy. See LoginRateLimiter's doc comment.
    loginRateLimiter: LoginRateLimiter,
) {
    route("/auth") {
        post("/login") {
            val creds = call.receive<LoginRequest>()
            val config = configStore.current

            val clientKey = LoginRateLimiter.clientKey(call.request.origin.remoteHost, call.request.headers["X-Forwarded-For"])
            if (!loginRateLimiter.tryAcquire(clientKey)) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Too many login attempts — try again in a minute"))
                return@post
            }

            if (config.apiKeys.jellyfinUrl.isBlank()) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("error" to "Jellyfin URL not configured — complete setup first"),
                )
                return@post
            }

            val authAttempt = runCatching {
                jellyfinClient.authenticateByName(
                    config.apiKeys.jellyfinUrl,
                    creds.username,
                    creds.password,
                )
            }
            if (authAttempt.isFailure) {
                val e = authAttempt.exceptionOrNull()
                Logger.info("jellyfin: ${config.apiKeys.jellyfinUrl}")
                Logger.warn("Jellyfin auth error: ${e?.message}")
                // Security fix (L6) — e?.message used to be forwarded to the client verbatim. Bad
                // credentials raise IllegalArgumentException (authenticateByName's own contract, same
                // one /api/tv/login already keys off) with a safe, authored message — anything else is
                // a transport failure whose exception text can embed the internal Jellyfin URL/host.
                val safeMessage = if (e is IllegalArgumentException) e.message else null
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to (safeMessage ?: "Authentication failed")),
                )
                return@post
            }
            val authResult = authAttempt.getOrThrow()

            if (!authResult.user.policy.isAdministrator) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "Jellystructure requires a Jellyfin administrator account"),
                )
                return@post
            }

            // Security fix (H4) — there was no authentication audit trail at all; a compromise left no
            // trace. Username only, never the password/token.
            Logger.info("Admin login succeeded for user '${authResult.user.name}'", "auth")

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
                    // Security fix (2026-08-02 review, finding M2) — was never set (defaults false),
                    // so a 7-day admin session token would go out in cleartext over any accidental
                    // plain-HTTP reach (a misconfigured vhost, an http:// bookmark, no HSTS on first
                    // visit). This server itself only ever speaks plain HTTP (see D1's deployment
                    // guide for why TLS termination belongs at a reverse proxy, not in-process) — the
                    // Secure attribute is enforced by the BROWSER based on the page's own origin
                    // scheme, so it's correct and safe to set even though the Kotlin/Native process
                    // never sees TLS directly, as long as a proxy is actually terminating it. Default
                    // off (matches today's LAN-only, no-proxy deployments, where forcing Secure would
                    // silently break login by having the browser refuse to send the cookie back over
                    // http://) — set COOKIE_SECURE=1 once a TLS-terminating reverse proxy is in front.
                    secure = dev.jellystructure.env("COOKIE_SECURE", "0") == "1",
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
                Cookie(
                    name = "js_session", value = "", path = "/", maxAge = 0, httpOnly = true,
                    secure = dev.jellystructure.env("COOKIE_SECURE", "0") == "1",
                ),
            )
            call.respond(HttpStatusCode.NoContent)
        }

        get("/me") {
            val session = call.attributes[SessionKey]
            call.respond(UserProfile(session.jellyfinUserId, session.jellyfinUsername))
        }
    }
}
