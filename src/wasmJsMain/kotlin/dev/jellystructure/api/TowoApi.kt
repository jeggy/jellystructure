package dev.jellystructure.api

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TowoSettings(
    val quotaWatchEnabled: Boolean = true,
    val quotaWatchIntervalMs: Long = 6L * 60 * 60 * 1000,
    val armNewSessionsDefault: Boolean = false,
    val defaultPermissionProfile: String = "normal",
    val defaultMaxTurns: Long = 40,
    val notifyPermissionRequested: Boolean = true,
    val notifyPausedQuota: Boolean = true,
    val notifyResumed: Boolean = true,
    val notifyErrored: Boolean = true,
    val notifyLowQuota: Boolean = true,
    val lowQuotaThresholdPct: Long = 20,
    val permissionTimeoutMs: Long = 30L * 60 * 1000,
    val permissionTimeoutReason: String = "No response within the timeout — auto-denied by Towo.",
    val runnerConnectUrl: String = "",
    val githubToken: String = "",
)

@Serializable
data class TowoRunner(
    val id: String,
    val name: String,
    val hostLabel: String? = null,
    val authMode: String? = null,
    val agentSdkVersion: String? = null,
    val os: String? = null,
    val claudeAuthOk: Boolean = false,
    val allowedRoots: List<String> = emptyList(),
    val createdAt: Long = 0,
    val lastSeenAt: Long? = null,
    val mirrorErrorAt: Long? = null,
    val mirrorErrorMessage: String? = null,
)

@Serializable
data class TowoFolder(
    val id: String,
    val runnerId: String,
    val name: String,
    val absPath: String,
    val pinned: Boolean = false,
    val defaultPermissionProfile: String = "normal",
    val discoveredAt: Long = 0,
)

@Serializable
data class TowoSession(
    val id: String,
    val runnerId: String,
    val folderId: String? = null,
    val folderPath: String? = null,
    val permissionProfile: String = "normal",
    val title: String? = null,
    val tag: String? = null,
    val status: String = "starting",
    val maxTurns: Long = 40,
    val maxTurnsSource: String = "default",
    val numTurns: Long = 0,
    val continueAfterReset: Boolean = false,
    val resumeAt: Long? = null,
    val lastErrorSubtype: String? = null,
    val createdAt: Long = 0,
    val lastActivityAt: Long = 0,
)

@Serializable
data class TowoPermissionRequest(
    val id: String,
    val sessionId: String,
    val toolName: String,
    val inputJson: String,
    val title: String? = null,
    val displayName: String? = null,
    val requestedAt: Long = 0,
    val decidedAt: Long? = null,
    val decision: String? = null,
    val reason: String? = null,
)

@Serializable
data class TowoQuotaStatus(
    val runnerId: String,
    val rateLimitType: String,
    val status: String,
    val resetsAt: Long? = null,
    val utilization: Double? = null,
    val observedAt: Long = 0,
)

@Serializable
data class TowoEnrollResponse(val enrollmentToken: String, val expiresAt: Long, val commands: Map<String, String>)

/** Phase 162 (Towo) — client for the control-plane REST surface (spec §6). */
object TowoApi {
    suspend fun getSettings(): TowoSettings = runCatching {
        httpClient.get("/api/towo/settings").body<TowoSettings>()
    }.getOrDefault(TowoSettings())

    suspend fun updateSettings(settings: TowoSettings): Boolean = runCatching {
        httpClient.put("/api/towo/settings") {
            contentType(ContentType.Application.Json)
            setBody(settings)
        }.status == HttpStatusCode.NoContent
    }.getOrDefault(false)

    suspend fun runners(): List<TowoRunner> = runCatching {
        httpClient.get("/api/towo/runners").body<List<TowoRunner>>()
    }.getOrDefault(emptyList())

    suspend fun runner(id: String): TowoRunner? = runCatching {
        httpClient.get("/api/towo/runners/$id").body<TowoRunner>()
    }.getOrNull()

    suspend fun runnerFolders(id: String): List<TowoFolder> = runCatching {
        httpClient.get("/api/towo/runners/$id/folders").body<List<TowoFolder>>()
    }.getOrDefault(emptyList())

    /** No dedicated "all folders" REST endpoint — aggregated client-side across runners, fine at
     *  the spec's stated scale ("a handful of sessions on 2-3 hosts"). */
    suspend fun allFolders(): List<TowoFolder> = runners().flatMap { runnerFolders(it.id) }

    suspend fun runnerQuota(id: String): List<TowoQuotaStatus> = runCatching {
        httpClient.get("/api/towo/runners/$id/quota").body<List<TowoQuotaStatus>>()
    }.getOrDefault(emptyList())

    suspend fun enrollRunner(name: String): TowoEnrollResponse? = runCatching {
        httpClient.post("/api/towo/runners/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":${name.jsonEsc()}}""")
        }.body<TowoEnrollResponse>()
    }.getOrNull()

    suspend fun deleteRunner(id: String): Boolean = runCatching {
        httpClient.delete("/api/towo/runners/$id").status == HttpStatusCode.NoContent
    }.getOrDefault(false)

    suspend fun sessions(runnerId: String? = null, status: String? = null): List<TowoSession> = runCatching {
        httpClient.get("/api/towo/sessions") {
            url { runnerId?.let { parameters.append("runnerId", it) }; status?.let { parameters.append("status", it) } }
        }.body<List<TowoSession>>()
    }.getOrDefault(emptyList())

    suspend fun session(id: String): TowoSession? = runCatching {
        httpClient.get("/api/towo/sessions/$id").body<TowoSession>()
    }.getOrNull()

    suspend fun createSession(folderId: String?, path: String?, prompt: String, permissionProfile: String = "normal", maxTurns: Long? = null): Boolean = runCatching {
        httpClient.post("/api/towo/sessions") {
            contentType(ContentType.Application.Json)
            setBody(buildString {
                append("{")
                append("\"prompt\":${prompt.jsonEsc()}")
                folderId?.let { append(",\"folderId\":${it.jsonEsc()}") }
                path?.let { append(",\"path\":${it.jsonEsc()}") }
                append(",\"permissionProfile\":${permissionProfile.jsonEsc()}")
                maxTurns?.let { append(",\"maxTurns\":$it") }
                append("}")
            })
        }.status == HttpStatusCode.Accepted
    }.getOrDefault(false)

    /** Raw JsonElements — the transcript is opaque SDK messages, rendered generically (spec build-order
     *  step 5's own framing: this backend only ever inspects the fields it needs, never the whole shape). */
    suspend fun messages(sessionId: String): List<JsonElement> = runCatching {
        httpClient.get("/api/towo/sessions/$sessionId/messages").body<List<JsonElement>>()
    }.getOrDefault(emptyList())

    suspend fun sendMessage(sessionId: String, text: String): Boolean = runCatching {
        httpClient.post("/api/towo/sessions/$sessionId/messages") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":${text.jsonEsc()}}""")
        }.status == HttpStatusCode.Accepted
    }.getOrDefault(false)

    suspend fun interrupt(sessionId: String): Boolean = runCatching {
        httpClient.post("/api/towo/sessions/$sessionId/interrupt").status == HttpStatusCode.Accepted
    }.getOrDefault(false)

    suspend fun resumeNow(sessionId: String, prompt: String = "Continue."): Boolean = runCatching {
        httpClient.post("/api/towo/sessions/$sessionId/resume") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":${prompt.jsonEsc()}}""")
        }.status == HttpStatusCode.Accepted
    }.getOrDefault(false)

    suspend fun updateSession(sessionId: String, continueAfterReset: Boolean? = null, maxTurns: Long? = null): Boolean = runCatching {
        httpClient.patch("/api/towo/sessions/$sessionId") {
            contentType(ContentType.Application.Json)
            setBody(buildString {
                append("{")
                var first = true
                continueAfterReset?.let { append("\"continueAfterReset\":$it"); first = false }
                maxTurns?.let { if (!first) append(","); append("\"maxTurns\":$it") }
                append("}")
            })
        }.status == HttpStatusCode.NoContent
    }.getOrDefault(false)

    suspend fun deleteSession(id: String): Boolean = runCatching {
        httpClient.delete("/api/towo/sessions/$id").status == HttpStatusCode.NoContent
    }.getOrDefault(false)

    suspend fun pendingPermissions(): List<TowoPermissionRequest> = runCatching {
        httpClient.get("/api/towo/permissions").body<List<TowoPermissionRequest>>()
    }.getOrDefault(emptyList())

    suspend fun decidePermission(id: String, decision: String, reason: String? = null): Boolean = runCatching {
        httpClient.post("/api/towo/permissions/$id") {
            contentType(ContentType.Application.Json)
            setBody(buildString {
                append("{\"decision\":${decision.jsonEsc()}")
                reason?.let { append(",\"reason\":${it.jsonEsc()}") }
                append("}")
            })
        }.status == HttpStatusCode.NoContent
    }.getOrDefault(false)
}

private fun String.jsonEsc() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
