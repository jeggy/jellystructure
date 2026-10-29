package dev.jellystructure.server.routes

import dev.jellystructure.ai.AiJobs
import dev.jellystructure.ai.AnthropicClient
import dev.jellystructure.ai.AiRequests
import dev.jellystructure.ai.AiViewers
import dev.jellystructure.ai.RerankSwitch
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.tv.RaviloDeviceService
import dev.jellystructure.tv.RecommendationService
import io.ktor.server.application.call
import io.ktor.server.routing.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class AiKeyTestResult(val result: String, val detail: String)

@Serializable
private data class RerankChoice(val rerank: Boolean? = null)

/** FR-5.2 — what *Remove all waiting* removed. */
@Serializable
private data class AiQueueCleared(val job: String, val removed: Int)

private val aiLenientJson = Json { ignoreUnknownKeys = true }

/**
 * Phase 270 (dev review item 1) — the AI tab's two reads. The key itself is never read back: `GET
 * /api/config` masks it like every secret, so the tab's *…abcd* hint, each job's spend this month, its last
 * run and a month's estimate come from [AiJobs.status] here.
 */
fun Route.aiRoutes(configStore: ConfigStore, jellyfinClient: JellyfinClient? = null, deviceService: RaviloDeviceService? = null) {
    // DEB-2826 (DEB-2823, FR-1.7) — every Jellyfin user with the AI re-rank switch and its sentences. A row for a user Jellyfin no
    // longer has is deleted here (FR-1.5). Admin only, like the rest of /api/ai.
    get("/ai/viewers") {
        val client = jellyfinClient ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Jellyfin is not available"))
        val switch = RecommendationService.current?.rerankSwitch ?: AiJobs.current?.rerankSwitch
            ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "AI jobs are not running"))
        val keys = configStore.current.apiKeys
        val users = client.getUsersOrNull(keys.jellyfinUrl, keys.jellyfinToken)
            ?: return@get call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Couldn't read the users from Jellyfin"))
        val present = users.mapTo(HashSet()) { RerankSwitch.key(it.id) }
        switch.stored().keys.filter { it !in present }.forEach { switch.forget(it) }
        call.respond(AiViewers.rows(users.map { it.id to it.name }, deviceService?.allDevices().orEmpty(), switch))
    }

    // `{"rerank": true|false}`. Off also deletes their waiting request and AI order and rebuilds their list without AI; an unknown user is a 404.
    put("/ai/viewers/{userId}") {
        val client = jellyfinClient ?: return@put call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Jellyfin is not available"))
        val service = RecommendationService.current ?: return@put call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Recommendations are not running"))
        val choice = runCatching { aiLenientJson.decodeFromString(RerankChoice.serializer(), call.receiveText()) }.getOrNull()?.rerank
            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Send {\"rerank\": true} or {\"rerank\": false}"))
        val userId = call.parameters["userId"].orEmpty()
        val keys = configStore.current.apiKeys
        val users = client.getUsersOrNull(keys.jellyfinUrl, keys.jellyfinToken)
            ?: return@put call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Couldn't read the users from Jellyfin"))
        val user = users.firstOrNull { RerankSwitch.key(it.id) == RerankSwitch.key(userId) }
            ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such Jellyfin user"))
        val by = runCatching { call.attributes[SessionKey].jellyfinUsername }.getOrNull()?.ifBlank { null } ?: "an admin"
        service.setRerank(user.id, choice, by)
        call.respond(AiViewers.rows(listOf(user.id to user.name), deviceService?.allDevices().orEmpty(), service.rerankSwitch).single())
    }

    // DEB-2826 (FR-5.2) — *Remove all waiting*: every waiting row of one job, and the count. A batch already out is not touched.
    delete("/ai/queue") {
        val jobs = AiJobs.current ?: return@delete call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "AI jobs are not running"))
        val job = call.request.queryParameters["job"].orEmpty()
        if (job !in setOf(AiRequests.RERANK_JOB, AiRequests.THEMES_JOB, AiRequests.CLUSTERS_JOB, AiRequests.SUPPORT_JOB)) {
            return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown job"))
        }
        call.respond(AiQueueCleared(job, jobs.removeAllQueued(job)))
    }

    get("/ai/status") {
        val jobs = AiJobs.current ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "AI jobs are not running"))
        call.respond(jobs.status(AiJobs.viewerCount()))
    }

    // Phase 272 (FR-272-8..11) — Activity's AI card: the queue, the batches out and the last five batches.
    // Reads the database only, never Anthropic, so the Jobs view can poll it with the lanes.
    get("/ai/jobs") {
        val jobs = AiJobs.current ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "AI jobs are not running"))
        call.respond(jobs.jobsView())
    }

    // FR-272-15 — one batch's conversations, fetched only when the admin opens it.
    get("/ai/batches/{id}") {
        val jobs = AiJobs.current ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "AI jobs are not running"))
        val detail = jobs.batchDetail(call.parameters["id"].orEmpty())
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such batch — only the last five are kept"))
        call.respond(detail)
    }

    // FR-272-9 — *Remove* one waiting request.
    delete("/ai/queue/{id}") {
        val jobs = AiJobs.current ?: return@delete call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "AI jobs are not running"))
        val id = call.parameters["id"]?.toLongOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Bad id"))
        if (!jobs.removeQueued(id)) return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "Already sent, or already removed"))
        call.respond(jobs.jobsView())
    }

    // FR-272-10 — *Cancel at Anthropic*: what was produced is still read, applied and paid for.
    post("/ai/batches/{id}/cancel") {
        val jobs = AiJobs.current ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "AI jobs are not running"))
        when (jobs.cancel(call.parameters["id"].orEmpty())) {
            AiJobs.Cancel.SENT -> call.respond(jobs.jobsView())
            AiJobs.Cancel.NOT_FOUND -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such batch"))
            AiJobs.Cancel.ENDED -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "This batch has already ended"))
            AiJobs.Cancel.FAILED -> call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Anthropic didn't take the cancel — try again"))
        }
    }

    // FR-270-1 — *Test key*: a free `GET /v1/models`. The body may carry a key being typed
    // (`{"api_key": "…"}`); blank or the `##KEEP##` sentinel tests the saved one.
    post("/ai/test-key") {
        val typed = runCatching {
            Json.parseToJsonElement(call.receiveText()).jsonObject["api_key"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.trim()
        val key = typed?.takeIf { it.isNotBlank() && it != "##KEEP##" } ?: configStore.current.ai.apiKey
        if (key.isBlank()) return@post call.respond(AiKeyTestResult("missing", "No key entered"))
        val result = when (AnthropicClient().testKey(key)) {
            AnthropicClient.KeyCheck.VALID -> AiKeyTestResult("valid", "Valid")
            AnthropicClient.KeyCheck.INVALID -> AiKeyTestResult("invalid", "Invalid key")
            AnthropicClient.KeyCheck.UNREACHABLE -> AiKeyTestResult("unreachable", "Can't reach Anthropic")
        }
        call.respond(result)
    }
}
