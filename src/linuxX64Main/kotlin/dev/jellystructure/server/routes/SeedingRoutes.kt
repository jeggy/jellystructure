package dev.jellystructure.server.routes

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.ops.GateClass
import dev.jellystructure.torrent.SeedingDamageCheck
import dev.jellystructure.torrent.SeedingSnapshot
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Phase 315 (FR-315-4) — the read-only check for torrents an earlier in-place edit may have changed. `POST` starts it
 * in the background (it walks each disk once, at low priority); `GET` returns the last result. Nothing is written,
 * and qBittorrent is only read.
 */
fun Route.seedingRoutes(store: MediaStore, history: MediaHistory, snapshot: SeedingSnapshot, configStore: ConfigStore, scope: CoroutineScope) {
    route("/seeding") {
        get("/damage-check") {
            val r = SeedingDamageCheck.last()
            if (r == null) call.respond(HttpStatusCode.NoContent) else call.respond(r)
        }
        post("/damage-check") {
            if (!SeedingDamageCheck.begin()) { call.respond(HttpStatusCode.Conflict, mapOf("error" to "A check is already running")); return@post }
            scope.launch(GateClass.BACKGROUND) { SeedingDamageCheck.run(store, history, snapshot, configStore.current) }
            call.respond(HttpStatusCode.Accepted, mapOf("ok" to true))
        }
    }
}
