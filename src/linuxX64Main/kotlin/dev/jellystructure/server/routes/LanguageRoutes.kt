package dev.jellystructure.server.routes

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.resolver.LanguageResolver
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class ResolveResult(val priority: List<String>, val fallback: String)

fun Route.languageRoutes(configStore: ConfigStore) {
    route("/language") {
        // GET /api/language/resolve?languages=fo,da&fallback=en
        // Returns the priority list the resolver would produce for those track languages.
        get("/resolve") {
            val raw = call.request.queryParameters["languages"] ?: ""
            val trackLanguages = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val fallback = call.request.queryParameters["fallback"]
                ?: configStore.current.languageRules.fallbackLanguage
            val priority = LanguageResolver.priorityList(trackLanguages, fallback)
            call.respond(ResolveResult(priority = priority, fallback = fallback))
        }
    }
}
