package dev.jellystructure.server.routes

import dev.jellystructure.chart.ChartIngestService
import dev.jellystructure.chart.ChartRegistry
import dev.jellystructure.chart.ChartStore
import dev.jellystructure.config.ConfigStore
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
}
