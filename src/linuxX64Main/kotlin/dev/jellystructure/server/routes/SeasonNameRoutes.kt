package dev.jellystructure.server.routes

import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.seasonNameFor
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** DEB-5321 (FR-4) — `PUT /api/media/{id}/season-names/{season}`'s body. */
@Serializable
private data class SeasonNameRequest(val name: String)

/** DEB-5321 (FR-5) — one season of a series as the admin reads it. */
@Serializable
data class SeasonNameEntry(
    val season: Int,
    /** FR-2's value. */
    val name: String,
    /** `admin` · `jellyfin` · `default`. */
    val source: String,
)

internal const val SEASON_NAME_MAX_CHARS = 100

/** One season per season-number that has an episode, ascending. Specials (0) sorts first: the admin reads numbers. */
internal fun seasonNamesOf(item: MediaItem): List<SeasonNameEntry> =
    item.episodes.map { it.seasonNumber ?: 0 }.distinct().sorted().map { n ->
        val named = seasonNameFor(item, n)
        SeasonNameEntry(n, named.name, named.source.wire)
    }

/** Null when [name] (already trimmed) is storable, else the reason it is not. */
internal fun seasonNameRefusal(name: String): String? {
    // A code point counts once, so an emoji is one character, not two UTF-16 units.
    if (name.count { !it.isLowSurrogate() } > SEASON_NAME_MAX_CHARS) return "a season name is at most $SEASON_NAME_MAX_CHARS characters"
    if (name.any { it.isISOControl() || it == ' ' || it == ' ' }) return "a season name has no control character or line break"
    return null
}

/**
 * DEB-5321 (FR-4) — stores the admin's name for one season of a TV series. Registered inside `/api/media/{id}`.
 * An empty name clears it. Writes with the carry-forward guard off: this is the one route that changes the map.
 */
internal fun Route.seasonNameRoutes(store: MediaStore, mediaHistory: MediaHistory) {
    put("/season-names/{season}") {
        val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
        val item = store.resolve(id) ?: return@put call.respond(HttpStatusCode.NotFound)
        if (item.kind != MediaKind.TV_SHOW) {
            return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "only a TV series has seasons"))
        }
        val season = call.parameters["season"]?.toIntOrNull()?.takeIf { it >= 0 }
            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid season"))
        val req = runCatching { call.receive<SeasonNameRequest>() }.getOrElse {
            return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid request"))
        }
        val name = req.name.trim()
        seasonNameRefusal(name)?.let { return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
        if (item.episodes.none { (it.seasonNumber ?: 0) == season }) {
            return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "no episode of this series is in season $season"))
        }
        val before = seasonNameFor(item, season)
        val overrides = if (name.isEmpty()) item.seasonNameOverrides - season else item.seasonNameOverrides + (season to name)
        if (overrides != item.seasonNameOverrides) {
            val saved = item.copy(seasonNameOverrides = overrides)
            store.updateOne(saved, respectSeasonNameOverrides = false)
            mediaHistory.record(
                id, "season_name_set",
                "season $season: ${if (name.isEmpty()) "cleared" else "\"$name\""} (was \"${before.name}\", ${before.source.wire})",
            )
            return@put call.respond(seasonNamesOf(saved))
        }
        call.respond(seasonNamesOf(item))
    }
}
