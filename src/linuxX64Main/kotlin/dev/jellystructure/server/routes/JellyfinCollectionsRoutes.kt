package dev.jellystructure.server.routes

import dev.jellystructure.auth.CollectionDeleteResult
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.JellyfinCollection
import dev.jellystructure.media.JellyfinCollections
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.collectionSuggestions
import dev.jellystructure.model.AddJellyfinCollectionItemsRequest
import dev.jellystructure.model.CreateJellyfinCollectionRequest
import dev.jellystructure.model.JellyfinCollectionDto
import dev.jellystructure.model.JellyfinCollectionMemberDto
import dev.jellystructure.model.JellyfinCollectionSuggestionDto
import dev.jellystructure.model.JellyfinCollectionsPageDto
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.RenameJellyfinCollectionRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** FR-5 — the longest a collection's name may be. */
internal const val COLLECTION_NAME_MAX = 200

/**
 * DEB-4075 (DEB-4032, FR-5..FR-7) — the admin's Jellyfin collections. Jellyfin is the only store: every write goes to Jellyfin and is
 * answered with the state [JellyfinCollections] re-read from it straight after (never echoed from the request). Titles are named by
 * jellystructure media id. A deletion goes through [JellyfinCollections.delete] (FR-2) and nowhere else.
 */
fun Route.jellyfinCollectionsRoutes(
    collections: JellyfinCollections,
    mediaStore: MediaStore,
    configStore: ConfigStore,
    jellyfinClient: JellyfinClient,
) {
    fun member(item: MediaItem) = JellyfinCollectionMemberDto(item.id, item.title, item.year, item.kind)

    fun dto(c: JellyfinCollection) = JellyfinCollectionDto(
        id = c.id, name = c.name, members = c.titleIds.mapNotNull { mediaStore.get(it) }.map(::member), unmappedCount = c.unmappedCount,
    )

    fun configured(): Boolean = configStore.current.apiKeys.let { it.jellyfinUrl.isNotBlank() && it.jellyfinToken.isNotBlank() }

    suspend fun RoutingContext.refuse(status: HttpStatusCode, message: String) = call.respond(status, mapOf("error" to message))

    /** A trimmed name, or the sentence that refuses it (FR-5 validation). */
    fun checkedName(raw: String): Pair<String?, String?> {
        val name = raw.trim()
        return when {
            name.isEmpty() -> null to "A collection needs a name."
            name.length > COLLECTION_NAME_MAX -> null to "A collection's name can be at most $COLLECTION_NAME_MAX characters."
            else -> name to null
        }
    }

    fun nameTaken(name: String, exceptId: String? = null): Boolean =
        collections.current().collections.any { it.id != exceptId && it.name.trim().equals(name, ignoreCase = true) }

    /** The Jellyfin ids of the named titles, in the order given and without repeats; or the sentence that names the first id that is no title. */
    fun resolve(mediaIds: List<String>): Pair<List<String>?, String?> {
        val out = LinkedHashSet<String>()
        for (id in mediaIds) {
            val item = mediaStore.get(id) ?: return null to "Unknown title \"$id\"."
            val jf = item.jellyfinId?.takeIf { it.isNotBlank() } ?: return null to "\"${item.title}\" isn't in Jellyfin yet, so it can't go in a collection."
            out += jf
        }
        return out.toList() to null
    }

    suspend fun RoutingContext.answer(id: String, status: HttpStatusCode = HttpStatusCode.OK) {
        val c = collections.current().get(id)
            ?: return refuse(HttpStatusCode.BadGateway, "The change was sent to Jellyfin, but the collection could not be read back. Reload the page to see what Jellyfin holds.")
        call.respond(status, dto(c))
    }

    route("/jellyfin-collections") {
        get {
            call.respond(JellyfinCollectionsPageDto(configured(), collections.current().collections.map(::dto)))
        }

        /** FR-7 — read only: it makes nothing. */
        get("/suggestions") {
            val snapshot = collections.current()
            call.respond(collectionSuggestions(mediaStore.allItems(), snapshot).map { f ->
                JellyfinCollectionSuggestionDto(f.id, f.name, f.films.map(::member))
            })
        }

        post {
            val req = call.receive<CreateJellyfinCollectionRequest>()
            val (name, bad) = checkedName(req.name)
            if (name == null) return@post refuse(HttpStatusCode.BadRequest, bad!!)
            if (nameTaken(name)) return@post refuse(HttpStatusCode.Conflict, "A collection called \"$name\" already exists.")
            val (ids, unknown) = resolve(req.items)
            if (ids == null) return@post refuse(HttpStatusCode.BadRequest, unknown!!)
            if (!configured()) return@post refuse(HttpStatusCode.BadGateway, "Jellyfin isn't set up, so the collection was not created.")
            val id = collections.create(name, ids)
                ?: return@post refuse(HttpStatusCode.BadGateway, "Jellyfin didn't create the collection \"$name\". Reload the page to see what it holds.")
            answer(id, HttpStatusCode.Created)
        }

        route("/{id}") {
            patch {
                val id = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
                collections.current().get(id) ?: return@patch call.respond(HttpStatusCode.NotFound)
                val (name, bad) = checkedName(call.receive<RenameJellyfinCollectionRequest>().name)
                if (name == null) return@patch refuse(HttpStatusCode.BadRequest, bad!!)
                if (nameTaken(name, exceptId = id)) return@patch refuse(HttpStatusCode.Conflict, "A collection called \"$name\" already exists.")
                if (!configured()) return@patch refuse(HttpStatusCode.BadGateway, "Jellyfin isn't set up, so the collection was not renamed.")
                // The rename reads the item in a user's context; any Jellyfin user does (FR-1 amendment, 2026-11-25).
                val userId = runCatching { call.attributes[SessionKey].jellyfinUserId }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: configStore.current.apiKeys.let { jellyfinClient.getUsersOrNull(it.jellyfinUrl, it.jellyfinToken) }?.firstOrNull()?.id
                    ?: return@patch refuse(HttpStatusCode.BadGateway, "Jellyfin has no user to read the collection as, so it was not renamed.")
                if (!collections.rename(id, userId, name)) return@patch refuse(HttpStatusCode.BadGateway, "Jellyfin didn't rename the collection. It still holds what it did.")
                answer(id)
            }

            delete {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                collections.current().get(id) ?: return@delete call.respond(HttpStatusCode.NotFound)
                when (collections.delete(id)) {
                    CollectionDeleteResult.DELETED -> call.respond(HttpStatusCode.NoContent)
                    CollectionDeleteResult.NOT_FOUND -> call.respond(HttpStatusCode.NotFound)
                    CollectionDeleteResult.NOT_A_COLLECTION -> refuse(HttpStatusCode.BadGateway, "That is not a collection, so nothing was deleted.")
                    CollectionDeleteResult.FAILED -> refuse(HttpStatusCode.BadGateway, "Jellyfin didn't delete the collection. It still holds what it did.")
                }
            }

            post("/items") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                collections.current().get(id) ?: return@post call.respond(HttpStatusCode.NotFound)
                val (ids, unknown) = resolve(call.receive<AddJellyfinCollectionItemsRequest>().items)
                if (ids == null) return@post refuse(HttpStatusCode.BadRequest, unknown!!)
                if (ids.isEmpty()) return@post answer(id)
                if (!collections.add(id, ids)) return@post refuse(HttpStatusCode.BadGateway, "Jellyfin didn't add every title to the collection. Reload the page to see what it holds.")
                answer(id)
            }

            delete("/items/{mediaId}") {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                collections.current().get(id) ?: return@delete call.respond(HttpStatusCode.NotFound)
                val (ids, unknown) = resolve(listOf(call.parameters["mediaId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)))
                if (ids == null) return@delete refuse(HttpStatusCode.BadRequest, unknown!!)
                if (!collections.remove(id, ids)) return@delete refuse(HttpStatusCode.BadGateway, "Jellyfin didn't remove the title from the collection. It still holds what it did.")
                answer(id)
            }
        }
    }
}
