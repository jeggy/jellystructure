package dev.jellystructure.server.routes

import dev.jellystructure.chart.ChartIngestService
import dev.jellystructure.chart.ChartRegistry
import dev.jellystructure.chart.ChartStore
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.shared.tv.DiscoverCoverageResponse
import dev.jellystructure.shared.tv.ListCoverage
import dev.jellystructure.shared.tv.ProviderCoverage
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Phase 57 — internal Discover API (admin/Ravilo-config side). The TV-facing `/api/tv/discover`
 * composition is R48; these are the building blocks.
 */
fun Route.chartRoutes(registry: ChartRegistry, store: ChartStore, configStore: ConfigStore, ingest: ChartIngestService) {
    // Available chart lists for the config editor (R50).
    get("/discover/lists") {
        val region = call.request.queryParameters["region"]
            ?: configStore.current.discover?.regions?.firstOrNull() ?: "DK"
        val providers = configStore.current.discover?.providers ?: listOf("netflix")
        call.respond(registry.enabled(providers).flatMap { it.availableLists(region) })
    }
    // Resolved entries for one list (consumed by R48).
    get("/discover/list/{id}") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.respond(store.entries(id))
    }
    // Admin: force an immediate ingest (ignores the week-gate).
    post("/discover/refresh") {
        val region = call.request.queryParameters["region"]
            ?: configStore.current.discover?.regions?.firstOrNull() ?: "DK"
        ingest.refresh(region, force = true)
        call.respond(mapOf("ok" to true))
    }
    // R154 — coverage for the config editor's validation banner: does each enabled provider actually
    // have ingested data for this region? Reads the same ChartStore rows the TV feed itself serves, so
    // the editor's verdict can never disagree with runtime behaviour.
    get("/discover/coverage") {
        val cfg = configStore.current
        val region = call.request.queryParameters["region"]
            ?: cfg.discover?.regions?.firstOrNull() ?: "DK"
        val enabledIds = cfg.discover?.providers ?: listOf("netflix")
        val providers = registry.enabled(enabledIds).map { provider ->
            val lists = provider.availableLists(region).map { spec ->
                val entries = store.entries(spec.id)
                val week = store.listWeek(spec.id)
                ListCoverage(
                    listId = spec.id,
                    title = spec.title,
                    covered = entries.isNotEmpty(),
                    reason = when {
                        entries.isNotEmpty() -> null
                        week == null -> "not_ingested"
                        else -> "empty_feed"
                    },
                    entryCount = entries.size,
                )
            }
            ProviderCoverage(provider.id, provider.displayName, covered = lists.any { it.covered }, lists = lists)
        }
        val radarrConnected = cfg.radarr?.enabled == true && cfg.radarr.url.isNotBlank() && cfg.radarr.apiKey.isNotBlank()
        call.respond(DiscoverCoverageResponse(
            region = region,
            providers = providers,
            ingestedRegions = cfg.discover?.regions ?: emptyList(),
            radarrConnected = radarrConnected,
        ))
    }
}
