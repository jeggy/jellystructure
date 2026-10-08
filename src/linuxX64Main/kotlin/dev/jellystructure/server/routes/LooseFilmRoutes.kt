package dev.jellystructure.server.routes

import dev.jellystructure.media.LooseFilmsService
import dev.jellystructure.model.DashboardRow
import dev.jellystructure.ops.GateClass
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Phase 316 (FR-316-4) — which films the owner picked for Apply (their `key`s); empty = every film still waiting. */
@Serializable
data class LooseFilmsApplyRequest(val keys: List<String> = emptyList())

/**
 * Phase 316 (FR-316-3/-4) — films with no folder of their own. `GET` returns the last overview; `POST /scan` rebuilds it in
 * the background (it reads the disks once, at low priority, and asks qBittorrent and Radarr read-only); `POST /apply` is
 * the owner's press, moving the picked films one at a time in the background.
 */
fun Route.looseFilmRoutes(service: LooseFilmsService, scope: CoroutineScope) {
    route("/loose-films") {
        get {
            val r = service.last()
            if (r == null) call.respond(HttpStatusCode.NoContent) else call.respond(r)
        }
        post("/scan") {
            if (!service.beginScan()) { call.respond(HttpStatusCode.Conflict, mapOf("error" to "A scan or a move is already running")); return@post }
            scope.launch(GateClass.BACKGROUND) { service.scan() }
            call.respond(HttpStatusCode.Accepted, mapOf("ok" to true))
        }
        post("/apply") {
            val req = runCatching { call.receive<LooseFilmsApplyRequest>() }.getOrDefault(LooseFilmsApplyRequest())
            val films = service.last()?.films.orEmpty()
            val keys = (if (req.keys.isEmpty()) films.filter { it.state == "waiting" || it.state == "failed" }.map { it.key } else req.keys).toSet()
            if (keys.isEmpty()) { call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Nothing to move")); return@post }
            if (!service.beginApply()) { call.respond(HttpStatusCode.Conflict, mapOf("error" to "A scan or a move is already running")); return@post }
            scope.launch(GateClass.BACKGROUND) { service.apply(keys) }
            call.respond(HttpStatusCode.Accepted, mapOf("ok" to true))
        }
    }
}

/**
 * FR-316-3 — the Dashboard's warning: films loose in a library root. Nothing at zero (285: zero is silence). Opens the
 * overview on the page.
 */
fun looseFilmsDashboardRow(service: LooseFilmsService?): DashboardRow? {
    val s = service ?: return null
    val applying = s.last()?.applying == true
    val n = runCatching { s.quickCount() }.getOrDefault(0)
    if (n == 0 && !applying) return null
    return DashboardRow(
        id = "loose_films", domain = "films", severity = "warning",
        label = if (applying) "Moving films into folders of their own…" else "$n ${if (n == 1) "film has" else "films have"} no folder of its own",
        sentence = "Their files sit in the library's root folder, so their artwork can show up on other films. Each can be moved into its own folder; torrents keep seeding and Radarr and Jellyfin are told.",
        count = n.takeIf { it > 0 }, unit = "film", fix = "here", action = "Show the films", opens = "loose_films",
    )
}
