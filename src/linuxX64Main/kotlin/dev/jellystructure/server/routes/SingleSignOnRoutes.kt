package dev.jellystructure.server.routes

import dev.jellystructure.auth.LoginRateLimiter
import dev.jellystructure.shared.RaviloHeaders
import dev.jellystructure.shared.tv.AdminSsoStartRequest
import dev.jellystructure.shared.tv.AdminSsoStartResponse
import dev.jellystructure.shared.tv.SsoClaimRequest
import dev.jellystructure.shared.tv.SsoProviders
import dev.jellystructure.shared.tv.SsoStartRequest
import dev.jellystructure.shared.tv.SsoStartResponse
import dev.jellystructure.shared.tv.TvSignInUnavailable
import dev.jellystructure.tv.SingleSignOnPages
import dev.jellystructure.tv.SingleSignOnService
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.queryString
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * DEB-2233 (FR-16) — this slice's two buckets, each its own limiter, neither of them `/api/tv/login`'s: a start is 10 a minute per
 * address, a claim 40 a minute per address with a success refunded, as `cast-redeem` does, so only failed claims count.
 */
class SsoLimits(
    val start: LoginRateLimiter = LoginRateLimiter(maxAttempts = 10),
    val claim: LoginRateLimiter = LoginRateLimiter(maxAttempts = 40),
)

// FR-15: nothing below names the server software behind sign-in, a plugin, a directory or a status code. A client may one day show a
// body; the viewer's copy is the client's own and classifies by status.
private const val TOO_MANY = "Too many attempts — try again in a minute"
private const val INVALID = "Invalid request"
private const val NOT_VALID = "That sign-in is not valid any more"

/** The cookie that binds an admin attempt to the browser that started it (FR-9, FR-11): the attempt's secret, ten minutes, never readable by a script. */
const val SSO_ADMIN_COOKIE = "js_sso"

/**
 * DEB-2233 (FR-8, FR-9, FR-11, FR-13) — the routes under `/api`. All five are open (AuthPlugin's OPEN_API_PATHS): a client signing in holds
 * no credential yet. the viewers' three are covered by that list's `/api/tv/login` prefix; the admin's two by their own entries.
 */
fun Route.singleSignOnApiRoutes(service: SingleSignOnService, limits: SsoLimits = SsoLimits()) {
    fun ApplicationCall.clientKey() = LoginRateLimiter.clientKey(request.origin.remoteHost, request.headers["X-Forwarded-For"])

    suspend fun ApplicationCall.tooMany() {
        response.header(HttpHeaders.RetryAfter, "60")
        respond(HttpStatusCode.TooManyRequests, mapOf("error" to TOO_MANY))
    }

    // FR-8 — the same list for both: the plugin's names, `[]` when there is none.
    get("/tv/login/providers") { call.respond(SsoProviders(service.providers())) }
    get("/auth/providers") { call.respond(SsoProviders(service.providers())) }

    post("/tv/login/sso/start") {
        if (!limits.start.tryAcquire(call.clientKey())) return@post call.tooMany()
        val req = runCatching { call.receive<SsoStartRequest>() }.getOrElse { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to INVALID)) }
        val version = call.request.headers[RaviloHeaders.VERSION]
        when (val r = service.startDevice(req.provider, req.deviceId, req.deviceName, req.platform, req.returnTo, version)) {
            is SingleSignOnService.Start.Ok -> call.respond(SsoStartResponse(authorizeUrl = r.authorizeUrl, attemptId = r.attemptId, secret = r.secret))
            SingleSignOnService.Start.Unavailable -> call.respond(HttpStatusCode.ServiceUnavailable, TvSignInUnavailable(reason = "unavailable"))
            SingleSignOnService.Start.Invalid -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to INVALID))
        }
    }

    post("/tv/login/sso/claim") {
        val key = "sso-claim:${call.clientKey()}"
        if (!limits.claim.tryAcquire(key)) return@post call.tooMany()
        val req = runCatching { call.receive<SsoClaimRequest>() }.getOrElse { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to INVALID)) }
        val result = service.claim(req.attemptId, req.secret, req.handoff)
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to NOT_VALID))
        limits.claim.refund(key)
        call.respond(result)
    }

    post("/auth/sso/start") {
        if (!limits.start.tryAcquire(call.clientKey())) return@post call.tooMany()
        val req = runCatching { call.receive<AdminSsoStartRequest>() }.getOrElse { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to INVALID)) }
        when (val r = service.startAdmin(req.provider)) {
            is SingleSignOnService.Start.Ok -> {
                call.response.cookies.append(
                    Cookie(
                        name = SSO_ADMIN_COOKIE, value = r.secret, httpOnly = true,
                        secure = dev.jellystructure.env("COOKIE_SECURE", "0") == "1",
                        // The return is a top-level GET from the identity provider's site: Lax carries the cookie, and only there.
                        path = "/sso/", maxAge = (SingleSignOnService.ATTEMPT_TTL_MS / 1000).toInt(), extensions = mapOf("SameSite" to "Lax"),
                    ),
                )
                call.respond(AdminSsoStartResponse(authorizeUrl = r.authorizeUrl))
            }
            SingleSignOnService.Start.Unavailable -> call.respond(HttpStatusCode.ServiceUnavailable, TvSignInUnavailable(reason = "unavailable"))
            SingleSignOnService.Start.Invalid -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to INVALID))
        }
    }
}

/**
 * DEB-2233 (FR-10, FR-13) — `GET /sso/OID/redirect/{provider}` on the backend's public origin, which the plugin fixes the path of. NOT under
 * `/api`: it is the address the identity provider sends the viewer's browser to. Open, because a browser coming back from the identity
 * provider holds nothing of ours — the attempt's `state` is what it proves, and an unknown one is never sent on to the plugin.
 */
fun Route.singleSignOnReturnRoute(service: SingleSignOnService) {
    get("/sso/OID/redirect/{provider}") {
        val provider = call.parameters["provider"].orEmpty()
        val params = call.request.queryParameters.entries().associate { (k, v) -> k to v.firstOrNull().orEmpty() }
        val lang = SingleSignOnPages.languageFor(call.request.headers[HttpHeaders.AcceptLanguage])
        // The cookie is spent whatever the outcome: it has served its one attempt.
        val cookie = call.request.cookies[SSO_ADMIN_COOKIE]
        if (cookie != null) {
            call.response.cookies.append(Cookie(name = SSO_ADMIN_COOKIE, value = "", path = "/sso/", maxAge = 0, httpOnly = true, secure = dev.jellystructure.env("COOKIE_SECURE", "0") == "1"))
        }
        // The address, the code and the state never travel on to another site from this page.
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header(HttpHeaders.CacheControl, "no-store")
        when (val outcome = service.handleReturn(provider, params, call.request.queryString(), cookie)) {
            is SingleSignOnService.Outcome.AdminSignedIn -> {
                call.setAdminSessionCookie(outcome.sessionToken)
                call.response.header(HttpHeaders.Location, "/admin")
                call.respond(HttpStatusCode.Found)
            }
            is SingleSignOnService.Outcome.Page -> call.respondText(
                SingleSignOnPages.render(outcome.kind, lang, outcome.admin, outcome.handBack, outcome.open),
                ContentType.parse("text/html; charset=utf-8"),
                HttpStatusCode.fromValue(SingleSignOnPages.statusFor(outcome.kind)),
            )
        }
    }
}
