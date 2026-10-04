package dev.jellystructure.seerr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 282 — the Seerr half of phase 186's removal cascade (FR-186-6), as pure rules so it is testable without a
 * Seerr (there is no `MockEngine` in `linuxX64Test`).
 *
 * Seerr 3.5.0 (PR #3385) answers **409** to `POST /request/{id}/decline` for any request that is not pending, while
 * `DELETE /request/{id}` is not guarded. So only a pending request is declined (FR-282-1); everything else is just
 * deleted. A refusal is logged at info, a failed delete at warn, and the cascade always runs to the end
 * (FR-186-6's "defensive" rule).
 */

/** Seerr's `MediaRequestStatus`: 1 pending, 2 approved, 3 declined, 4 failed, 5 completed. */
internal const val SEERR_REQUEST_PENDING = 1

/** One call the removal makes against Seerr, in order. */
internal sealed class SeerrCall {
    data class Decline(val requestId: Int) : SeerrCall()
    data class DeleteRequest(val requestId: Int) : SeerrCall()
    data class DeleteMedia(val mediaId: Int) : SeerrCall()
}

/** FR-282-2 — what a decline came to. */
sealed class SeerrDecline {
    data object Declined : SeerrDecline()
    /** 409: the request was not pending (Seerr's state changed between our read and our call). */
    data class Refused(val message: String) : SeerrDecline()
    data class Failed(val detail: String) : SeerrDecline()
}

/** FR-282-1 — decline-then-delete for a pending request, delete only for every other status (an absent status reads
 *  0), in Seerr's own order; then the media row when there is one. */
internal fun seerrRemovalCalls(requests: List<SeerrRequestRef>, mediaId: Int): List<SeerrCall> = buildList {
    for (req in requests) {
        if (req.status == SEERR_REQUEST_PENDING) add(SeerrCall.Decline(req.id))
        add(SeerrCall.DeleteRequest(req.id))
    }
    if (mediaId != 0) add(SeerrCall.DeleteMedia(mediaId))
}

/** FR-282-2 — 200/204 declined; 409 refused with the body's `message` (else the raw body); anything else failed. */
internal fun declineOutcome(status: Int, body: String?): SeerrDecline = when (status) {
    200, 204 -> SeerrDecline.Declined
    409 -> SeerrDecline.Refused(seerrMessage(body))
    else -> SeerrDecline.Failed("HTTP $status")
}

private fun seerrMessage(body: String?): String {
    if (body.isNullOrEmpty()) return ""
    val msg = runCatching {
        (Json.parseToJsonElement(body) as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return msg ?: body
}

/** The result of one executed call. */
internal sealed class SeerrCallResult {
    data object Ok : SeerrCallResult()
    data class Refused(val message: String) : SeerrCallResult()
    data class Failed(val detail: String) : SeerrCallResult()
}

internal enum class SeerrLogLevel { INFO, WARN }

internal data class SeerrRemovalLog(val level: SeerrLogLevel, val message: String)

/**
 * Runs [calls] through [exec] in order and returns the lines to log. Never stops early: a refused or failed decline
 * is followed by the delete that actually removes the request, and a failed delete is followed by the rest of the
 * plan. A thrown call counts as failed. Messages carry ids and Seerr's own text only, never a title.
 */
internal suspend fun runSeerrRemoval(
    calls: List<SeerrCall>,
    exec: suspend (SeerrCall) -> SeerrCallResult,
): List<SeerrRemovalLog> {
    val out = mutableListOf<SeerrRemovalLog>()
    for (call in calls) {
        val result = try {
            exec(call)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            SeerrCallResult.Failed(e.message ?: e::class.simpleName ?: "error")
        }
        when (call) {
            is SeerrCall.Decline -> when (result) {
                is SeerrCallResult.Refused -> out += SeerrRemovalLog(
                    SeerrLogLevel.INFO,
                    "seerr: request ${call.requestId} not declined (no longer pending): ${result.message}; deleting it",
                )
                // A lean (spec's tests): the delete that follows is what removes the request, so info, never warn.
                is SeerrCallResult.Failed -> out += SeerrRemovalLog(
                    SeerrLogLevel.INFO,
                    "seerr: declining request ${call.requestId} failed (${result.detail}); deleting it",
                )
                SeerrCallResult.Ok -> Unit
            }
            is SeerrCall.DeleteRequest -> if (result !is SeerrCallResult.Ok) out += SeerrRemovalLog(
                SeerrLogLevel.WARN,
                "seerr: deleting request ${call.requestId} failed (${result.detailText()}); Seerr may still hold it",
            )
            is SeerrCall.DeleteMedia -> if (result !is SeerrCallResult.Ok) out += SeerrRemovalLog(
                SeerrLogLevel.WARN,
                "seerr: deleting media ${call.mediaId} failed (${result.detailText()}); Seerr may still hold it",
            )
        }
    }
    return out
}

private fun SeerrCallResult.detailText(): String = when (this) {
    SeerrCallResult.Ok -> "ok"
    is SeerrCallResult.Refused -> message
    is SeerrCallResult.Failed -> detail
}
