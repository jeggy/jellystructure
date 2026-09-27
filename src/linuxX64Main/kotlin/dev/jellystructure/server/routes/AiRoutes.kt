package dev.jellystructure.server.routes

import dev.jellystructure.ai.AiJobs
import dev.jellystructure.ai.AnthropicClient
import dev.jellystructure.config.ConfigStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class AiKeyTestResult(val result: String, val detail: String)

/**
 * Phase 270 (dev review item 1) — the AI tab's two reads. The key itself is never read back: `GET
 * /api/config` masks it like every secret, so the tab's *…abcd* hint, each job's spend this month, its last
 * run and a month's estimate come from [AiJobs.status] here.
 */
fun Route.aiRoutes(configStore: ConfigStore) {
    get("/ai/status") {
        val jobs = AiJobs.current ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "AI jobs are not running"))
        call.respond(jobs.status(AiJobs.viewerCount()))
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
