package dev.jellystructure.towo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Events pushed to the browser over `/api/towo/stream` (spec §6). */
@Serializable
sealed class TowoEvent {
    @Serializable @SerialName("session.status")
    data class SessionStatus(val sessionId: String, val status: String, val resumeAt: Long? = null, val errorSubtype: String? = null) : TowoEvent()

    @Serializable @SerialName("message.raw")
    data class MessageRaw(val sessionId: String, val message: JsonElement) : TowoEvent()

    @Serializable @SerialName("permission.requested")
    data class PermissionRequested(val requestId: String, val sessionId: String, val toolName: String, val input: JsonElement, val title: String? = null, val displayName: String? = null) : TowoEvent()

    @Serializable @SerialName("permission.resolved")
    data class PermissionResolved(val requestId: String, val decision: String) : TowoEvent()

    @Serializable @SerialName("session.result")
    data class SessionResult(val sessionId: String, val subtype: String) : TowoEvent()

    @Serializable @SerialName("quota.updated")
    data class QuotaUpdated(val runnerId: String, val rateLimitType: String, val status: String, val resetsAt: Long?, val utilization: Double?) : TowoEvent()

    @Serializable @SerialName("runner.status")
    data class RunnerStatus(val runnerId: String, val status: String, val claudeAuth: String? = null) : TowoEvent()

    @Serializable @SerialName("runner.enrolled")
    data class RunnerEnrolled(val runnerId: String, val name: String) : TowoEvent()
}
