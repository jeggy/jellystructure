package dev.jellystructure.server.routes

import dev.jellystructure.advisor.JellyfinAdvisorService
import dev.jellystructure.advisor.MemoryBudgetService
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JellyfinUserDto(
    val id: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
data class MemoryBudgetRequest(@SerialName("budget_gb") val budgetGb: Double)

fun Route.jellyfinRoutes(configStore: ConfigStore, jellyfinClient: JellyfinClient) {
    route("/jellyfin") {
        get("/libraries") {
            val config = configStore.current
            if (config.apiKeys.jellyfinUrl.isBlank() || config.apiKeys.jellyfinToken.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Jellyfin URL and token are required"))
                return@get
            }
            val libraries = jellyfinClient.getLibraries(config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken)
            call.respond(libraries)
        }

        get("/users") {
            val config = configStore.current
            if (config.apiKeys.jellyfinUrl.isBlank() || config.apiKeys.jellyfinToken.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Jellyfin not configured"))
                return@get
            }
            val users = jellyfinClient.getUsers(config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken)
                .map { JellyfinUserDto(id = it.id, displayName = it.name) }
            call.respond(users)
        }

        // Phase 212 — Settings → Libraries' Jellyfin settings advisor.
        get("/advisor") {
            call.respond(JellyfinAdvisorService.findings(jellyfinClient, configStore.current))
        }

        // Phase 244 FR-244-4 — Re-check: runs ONLY the exposure probe, bypassing the advisor's
        // 5-minute cache, so an operator who has just set Known proxies gets the answer to the question
        // they actually asked rather than a cached one from before the change.
        get("/exposure-recheck") {
            call.respond(JellyfinAdvisorService.exposureRecheck(jellyfinClient, configStore.current))
        }

        // Phase 215 — the memory budget calculator: one number in, concrete changes out.
        post("/memory-budget") {
            val req = runCatching { call.receive<MemoryBudgetRequest>() }.getOrNull()
            if (req == null || req.budgetGb <= 0) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "budget_gb must be a positive number"))
                return@post
            }
            call.respond(MemoryBudgetService.calculate(req.budgetGb, jellyfinClient, configStore.current))
        }
    }
}
