package dev.jellystructure.server.routes

import dev.jellystructure.media.JsTag
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.LogoDownloader
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaKind
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class MetadataEntry(
    val name: String,
    val count: Int,
    val tmdbId: Int? = null,
    val logoPath: String? = null,
    val hasLogo: Boolean = false,
)

@Serializable
data class LogoFetchResult(val ok: Boolean, val cached: Boolean, val detail: String? = null)

@Serializable
data class BatchLogoResult(val fetched: Int, val skipped: Int, val failed: Int)

@Serializable
data class TagsResponse(
    val jsTags: List<JsTagWithCount>,
    val otherTags: List<MetadataEntry>,
)

@Serializable
data class JsTagWithCount(
    val name: String,
    val color: String,
    val description: String,
    val count: Int,
)

@Serializable
data class CreateTagRequest(val name: String, val color: String = "#6b7280", val description: String = "")

@Serializable
data class UpdateTagRequest(val color: String? = null, val description: String? = null)

fun Route.metadataRoutes(store: MediaStore, tagStore: JsTagStore, logoDownloader: LogoDownloader) {
    route("/metadata") {
        get("/studios") {
            val sort = call.request.queryParameters["sort"] ?: "count"
            val all = store.allItems()
            val grouped = all.mapNotNull { it.studio?.takeIf { s -> s.isNotBlank() }?.let { s ->
                Triple(s, it.studioTmdbId, it.studioLogoPath)
            }}.groupBy { it.first }
            val entries = grouped.map { (name, items) ->
                MetadataEntry(
                    name = name, count = items.size,
                    tmdbId = items.firstOrNull()?.second, logoPath = items.firstOrNull()?.third,
                    hasLogo = logoDownloader.hasLogo("studios", name),
                )
            }
            val sorted = if (sort == "name") entries.sortedBy { it.name.lowercase() } else entries.sortedByDescending { it.count }
            call.respond(sorted)
        }

        get("/networks") {
            val sort = call.request.queryParameters["sort"] ?: "count"
            val all = store.allItems().filter { it.kind == MediaKind.TV_SHOW }
            val grouped = all.mapNotNull { it.network?.takeIf { n -> n.isNotBlank() }?.let { n ->
                Triple(n, it.networkTmdbId, it.networkLogoPath)
            }}.groupBy { it.first }
            val entries = grouped.map { (name, items) ->
                MetadataEntry(
                    name = name, count = items.size,
                    tmdbId = items.firstOrNull()?.second, logoPath = items.firstOrNull()?.third,
                    hasLogo = logoDownloader.hasLogo("networks", name),
                )
            }
            val sorted = if (sort == "name") entries.sortedBy { it.name.lowercase() } else entries.sortedByDescending { it.count }
            call.respond(sorted)
        }

        // --- Studio artwork ---
        route("/studios") {
            post("/artwork/batch") {
                val all = store.allItems()
                val studios = all.mapNotNull { it.studio?.takeIf { s -> s.isNotBlank() }?.let { s ->
                    Triple(s, it.studioTmdbId, it.studioLogoPath)
                }}.distinctBy { it.first }
                val result = logoDownloader.batchFetchStudios(studios)
                call.respond(BatchLogoResult(result.fetched, result.skipped, result.failed))
            }
            route("/{name}") {
                get("/artwork") {
                    val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val bytes = logoDownloader.serveLogo("studios", name)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondBytes(bytes, ContentType.Image.PNG)
                }
                post("/artwork") {
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val all = store.allItems()
                    val item = all.firstOrNull { it.studio == name }
                    val cached = logoDownloader.hasLogo("studios", name)
                    if (cached) { call.respond(LogoFetchResult(ok = true, cached = true)); return@post }
                    val ok = logoDownloader.fetchStudioLogo(name, item?.studioTmdbId, item?.studioLogoPath)
                    call.respond(LogoFetchResult(ok = ok, cached = false, detail = if (!ok) "no logo available" else null))
                }
            }
        }

        // --- Network artwork ---
        route("/networks") {
            post("/artwork/batch") {
                val all = store.allItems().filter { it.kind == MediaKind.TV_SHOW }
                val networks = all.mapNotNull { it.network?.takeIf { n -> n.isNotBlank() }?.let { n ->
                    Pair(n, it.networkLogoPath)
                }}.distinctBy { it.first }
                val result = logoDownloader.batchFetchNetworks(networks)
                call.respond(BatchLogoResult(result.fetched, result.skipped, result.failed))
            }
            route("/{name}") {
                get("/artwork") {
                    val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val bytes = logoDownloader.serveLogo("networks", name)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondBytes(bytes, ContentType.Image.PNG)
                }
                post("/artwork") {
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val all = store.allItems().filter { it.kind == MediaKind.TV_SHOW }
                    val item = all.firstOrNull { it.network == name }
                    val cached = logoDownloader.hasLogo("networks", name)
                    if (cached) { call.respond(LogoFetchResult(ok = true, cached = true)); return@post }
                    val logoPath = item?.networkLogoPath
                    if (logoPath.isNullOrBlank()) {
                        call.respond(LogoFetchResult(ok = false, cached = false, detail = "no logo available for this network"))
                        return@post
                    }
                    val ok = logoDownloader.fetchNetworkLogo(name, logoPath)
                    call.respond(LogoFetchResult(ok = ok, cached = false, detail = if (!ok) "download failed" else null))
                }
            }
        }

        get("/genres") {
            val sort = call.request.queryParameters["sort"] ?: "count"
            val counts = mutableMapOf<String, Int>()
            for (item in store.allItems()) {
                for (genre in item.genres) {
                    if (genre.isNotBlank()) counts[genre] = (counts[genre] ?: 0) + 1
                }
            }
            val entries = counts.map { (name, count) -> MetadataEntry(name = name, count = count) }
            val sorted = if (sort == "name") entries.sortedBy { it.name.lowercase() } else entries.sortedByDescending { it.count }
            call.respond(sorted)
        }

        get("/tags") {
            val sort = call.request.queryParameters["sort"] ?: "count"
            val jsTagNames = tagStore.nameSet()
            val tagCounts = mutableMapOf<String, Int>()
            for (item in store.allItems()) {
                for (tag in item.tags) {
                    if (tag.isNotBlank()) tagCounts[tag] = (tagCounts[tag] ?: 0) + 1
                }
            }
            val jsTags = tagStore.all().map { jsTag ->
                JsTagWithCount(name = jsTag.name, color = jsTag.color, description = jsTag.description, count = tagCounts[jsTag.name] ?: 0)
            }.let { list -> if (sort == "name") list.sortedBy { it.name.lowercase() } else list.sortedByDescending { it.count } }
            val otherTags = tagCounts.filter { it.key !in jsTagNames }
                .map { (name, count) -> MetadataEntry(name = name, count = count) }
                .let { list -> if (sort == "name") list.sortedBy { it.name.lowercase() } else list.sortedByDescending { it.count } }
            call.respond(TagsResponse(jsTags = jsTags, otherTags = otherTags))
        }
    }

    route("/tags") {
        get {
            call.respond(tagStore.all())
        }
        post {
            val req = call.receive<CreateTagRequest>()
            val tag = JsTag(name = req.name.trim(), color = req.color, description = req.description)
            if (tag.name.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name is required"))
            val ok = tagStore.create(tag)
            if (!ok) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "tag '${tag.name}' already exists"))
            call.respond(HttpStatusCode.Created, tag)
        }
        route("/{name}") {
            patch {
                val name = call.parameters["name"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
                val req = call.receive<UpdateTagRequest>()
                val ok = tagStore.update(name, req.color, req.description)
                if (!ok) return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(tagStore.get(name)!!)
            }
            delete {
                val name = call.parameters["name"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val ok = tagStore.delete(name)
                if (!ok) return@delete call.respond(HttpStatusCode.NotFound)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
