package dev.jellystructure.server.routes

import dev.jellystructure.config.AppConfig
import dev.jellystructure.torrent.SeedingCheckResult
import dev.jellystructure.torrent.SeedingGuard
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/**
 * 315 FR-315-6 — the enqueue-time fail-fast (109 FR A.3) of the four routes that queue a rename-based remux: delete a
 * track and reorder tracks, for a film and for an episode. It asks the question the queued job asks, `inPlace = false`:
 * a remux writes a new library file, so a name outside the library does not refuse it; a library path that is itself a
 * torrent's content path does. Answers 409 (the guard's own sentence) or 503 and returns true when it refused.
 */
suspend fun ApplicationCall.refuseRemuxIfSeeded(seedingGuard: SeedingGuard, path: String, config: AppConfig): Boolean =
    when (val guard = seedingGuard.check(path, config, inPlace = false, countRefusal = true)) {
        is SeedingCheckResult.Blocked -> { respond(HttpStatusCode.Conflict, mapOf("error" to guard.message)); true }
        is SeedingCheckResult.Unreachable -> { respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "qBittorrent unreachable: ${guard.reason}")); true }
        else -> false
    }
