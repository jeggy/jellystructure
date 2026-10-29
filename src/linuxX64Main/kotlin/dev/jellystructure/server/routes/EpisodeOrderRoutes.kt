package dev.jellystructure.server.routes

import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** DEB-2916 (FR-2) — `PUT /api/media/{id}/episode-order`'s body: the admin's choice. */
@Serializable
private data class EpisodeOrderRequest(@SerialName("newest_first") val newestFirst: Boolean)

/** DEB-2916 (FR-2) — the effective value and where it came from, as the PUT answers and `GET /api/media/{id}` carries. */
@Serializable
data class EpisodeOrderAnswer(
    @SerialName("newest_first") val newestFirst: Boolean,
    /** `auto` · `admin` · `off`. */
    @SerialName("newest_first_source") val newestFirstSource: String,
)

internal fun episodeOrderOf(store: MediaStore, item: MediaItem): EpisodeOrderAnswer =
    EpisodeOrderAnswer(store.isNewestFirst(item), store.newestFirstSource(item).wire)

private val itemJson = Json { ignoreUnknownKeys = true }

/**
 * DEB-2916 (FR-2) — `GET /api/media/{id}`'s body: the item as it always was, and for a TV series `newest_first` and
 * `newest_first_source` beside it. Only gained fields; the item's own `newestFirst` (null = automatic) stays on it.
 */
internal fun mediaItemWithEpisodeOrder(store: MediaStore, item: MediaItem): JsonElement {
    val base = itemJson.encodeToJsonElement(MediaItem.serializer(), item)
    if (item.kind != MediaKind.TV_SHOW || base !is JsonObject) return base
    val order = episodeOrderOf(store, item)
    return JsonObject(base + mapOf(
        "newest_first" to JsonPrimitive(order.newestFirst),
        "newest_first_source" to JsonPrimitive(order.newestFirstSource),
    ))
}

/**
 * DEB-2916 (FR-2) — stores the admin's choice of episode order for one TV series. Registered inside `/api/media/{id}`.
 * There is no route back to automatic: once chosen, Auto-fetch never changes it (FR-1's rescan guard carries it).
 */
internal fun Route.episodeOrderRoutes(store: MediaStore, mediaHistory: MediaHistory) {
    put("/episode-order") {
        val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
        val item = store.resolve(id) ?: return@put call.respond(HttpStatusCode.NotFound)
        val req = runCatching { call.receive<EpisodeOrderRequest>() }.getOrElse {
            return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid request"))
        }
        if (item.kind != MediaKind.TV_SHOW) {
            return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "only a TV series has an episode order"))
        }
        val before = episodeOrderOf(store, item)
        val saved = item.copy(newestFirst = req.newestFirst)
        store.updateOne(saved, respectNewestFirst = false)
        val after = episodeOrderOf(store, saved)
        mediaHistory.record(
            id, "episode_order_set",
            "${if (after.newestFirst) "newest first" else "oldest first"} (was ${if (before.newestFirst) "newest first" else "oldest first"}, ${before.newestFirstSource})",
        )
        call.respond(after)
    }
}

internal suspend fun io.ktor.server.application.ApplicationCall.respondJsonElement(element: JsonElement) =
    respondText(Json.encodeToString(JsonElement.serializer(), element), ContentType.Application.Json)
