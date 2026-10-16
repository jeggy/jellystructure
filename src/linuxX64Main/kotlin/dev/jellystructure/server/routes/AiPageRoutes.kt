package dev.jellystructure.server.routes

import dev.jellystructure.config.AppConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/** DEB-1253 (FR-2) — the tabs the admin's AI page has, in page order. */
@Serializable
data class AiPageDto(val tabs: List<String>)

/**
 * DEB-1253 (FR-1, FR-2) — which tabs the AI page has: Support · Fetchers · Suggestions, each only while its own gate
 * holds. Empty when `[ai] api_key` is blank. The key test is "non-blank" (320 FR-320-1), not `ai.enabled`: switching AI
 * off, a revoked key or a spent limit leave the page where it is. Support and Fetchers are not built, so Suggestions
 * (Seerr connected, FR-274-1) is the only tab today.
 */
fun aiPageTabs(config: AppConfig, suggestionsAvailable: Boolean): List<String> {
    if (config.ai.apiKey.isBlank()) return emptyList()
    return buildList { if (suggestionsAvailable) add("suggestions") }
}

/** `GET /api/ai/page` — 404 means "no AI page": no sidebar entry and no route (FR-2). Admin-only; not on the Ravilo wire. */
fun Route.aiPageRoutes(config: () -> AppConfig, suggestionsAvailable: () -> Boolean) {
    get("/ai/page") {
        val tabs = aiPageTabs(config(), suggestionsAvailable())
        if (tabs.isEmpty()) call.respond(HttpStatusCode.NotFound) else call.respond(AiPageDto(tabs))
    }
}
