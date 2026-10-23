package dev.jellystructure.server.routes

import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.LoginRateLimiter
import dev.jellystructure.shared.tv.TvSignInApproveRequest
import dev.jellystructure.shared.tv.TvSignInCodeRequest
import dev.jellystructure.shared.tv.TvSignInCodeResponse
import dev.jellystructure.shared.tv.TvSignInPollRequest
import dev.jellystructure.shared.tv.TvSignInPollWaiting
import dev.jellystructure.shared.tv.TvSignInPreview
import dev.jellystructure.shared.tv.TvSignInUnavailable
import dev.jellystructure.tv.TvSignInService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * DEB-2233 (FR-16) — this slice's buckets, each its own limiter, none of them `/api/tv/login`'s (five a minute per IP, shared
 * with the Cast hand-off and Phase 236's pairing): a TV's poll every 3 s would otherwise answer 429 to most polls, and behind
 * one household address it would block password sign-in too.
 */
class TvSignInLimits(
    /** Code mint: 10 per minute per IP. */
    val mint: LoginRateLimiter = LoginRateLimiter(maxAttempts = 10),
    /** Code poll: 40 per minute per IP (a 3 s poll is 20). */
    val poll: LoginRateLimiter = LoginRateLimiter(maxAttempts = 40),
    /** Approve: 5 per minute per approving device. */
    val approve: LoginRateLimiter = LoginRateLimiter(maxAttempts = 5),
    /** Preview: a code is guessable in principle and a preview says whether one exists, so it is bounded too — 20 per minute per
     *  approving device, its own bucket so a viewer's preview-then-approve never spends the approve allowance. */
    val preview: LoginRateLimiter = LoginRateLimiter(maxAttempts = 20),
)

// FR-15: nothing below names the server software behind sign-in, a plugin, a directory, or a status code. A client may one
// day show a body; the viewer's copy is the client's own and classifies by status.
private const val NOT_VALID = "That code is not valid any more"
private const val TOO_MANY = "Too many attempts — try again in a minute"

/**
 * DEB-2233 (FR-2, FR-3) — `/api/tv/signin/{code,poll}` are open (AuthPlugin's OPEN_API_PATHS: the TV holds no credential);
 * `/api/tv/signin/{preview,approve}` take a device token like every other TV route.
 */
fun Route.tvSignInRoutes(service: TvSignInService, limits: TvSignInLimits = TvSignInLimits()) {
    fun io.ktor.server.application.ApplicationCall.clientKey() =
        LoginRateLimiter.clientKey(request.origin.remoteHost, request.headers["X-Forwarded-For"])

    suspend fun io.ktor.server.application.ApplicationCall.tooMany() {
        response.header(HttpHeaders.RetryAfter, "60")
        respond(HttpStatusCode.TooManyRequests, mapOf("error" to TOO_MANY))
    }

    post("/tv/signin/code") {
        if (!limits.mint.tryAcquire(call.clientKey())) return@post call.tooMany()
        val req = runCatching { call.receive<TvSignInCodeRequest>() }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request"))
        }
        if (req.deviceId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "deviceId is required"))
        val minted = service.mint(req.deviceId, req.deviceName, req.platform)
            ?: return@post call.respond(TvSignInCodeResponse(available = false))
        call.respond(TvSignInCodeResponse(available = true, code = minted.code, expiresAt = minted.expiresAt, secret = minted.secret, linkUrl = minted.linkUrl))
    }

    post("/tv/signin/poll") {
        if (!limits.poll.tryAcquire(call.clientKey())) return@post call.tooMany()
        val req = runCatching { call.receive<TvSignInPollRequest>() }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request"))
        }
        when (val r = service.poll(req.code, req.secret)) {
            null -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to NOT_VALID))
            is TvSignInService.Poll.Waiting -> call.respond(HttpStatusCode.Accepted, TvSignInPollWaiting(state = r.state, approverName = r.approverName))
            is TvSignInService.Poll.Done -> call.respond(r.result)
        }
    }

    get("/tv/signin/preview") {
        val caller = call.attributes[DeviceKey]
        if (!TvSignInService.mayApprove(caller)) return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "This device can't sign a TV in"))
        if (!limits.preview.tryAcquire(caller.deviceId)) return@get call.tooMany()
        val code = call.request.queryParameters["code"].orEmpty()
        val p = service.preview(code, caller) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to NOT_VALID))
        call.respond(TvSignInPreview(deviceName = p.deviceName, platform = p.platform))
    }

    post("/tv/signin/approve") {
        val caller = call.attributes[DeviceKey]
        // A borrowing row has no sign-in of its own. Checked before the bucket and before the body: nothing about it is worth a count.
        if (!TvSignInService.mayApprove(caller)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "This device can't sign a TV in"))
        if (!limits.approve.tryAcquire(caller.deviceId)) return@post call.tooMany()
        val req = runCatching { call.receive<TvSignInApproveRequest>() }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request"))
        }
        when (service.approve(req.code, caller)) {
            TvSignInService.Approval.SIGNED_IN -> call.respond(HttpStatusCode.NoContent)
            TvSignInService.Approval.NOT_FOUND -> call.respond(HttpStatusCode.NotFound, mapOf("error" to NOT_VALID))
            TvSignInService.Approval.USED -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "That code has already been used"))
            TvSignInService.Approval.UNAVAILABLE -> call.respond(HttpStatusCode.ServiceUnavailable, TvSignInUnavailable(reason = "unavailable"))
        }
    }
}
