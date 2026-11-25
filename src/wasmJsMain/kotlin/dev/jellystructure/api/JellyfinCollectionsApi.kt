package dev.jellystructure.api

import dev.jellystructure.encodeURIComponent
import dev.jellystructure.model.AddJellyfinCollectionItemsRequest
import dev.jellystructure.model.CreateJellyfinCollectionRequest
import dev.jellystructure.model.JellyfinCollectionDto
import dev.jellystructure.model.JellyfinCollectionSuggestionDto
import dev.jellystructure.model.JellyfinCollectionsPageDto
import dev.jellystructure.model.RenameJellyfinCollectionRequest
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** DEB-4075 (DEB-4032, FR-5) — what a write answered: the collection as Jellyfin now holds it, or the route's own sentence. */
class JcAnswer(val collection: JellyfinCollectionDto?, val error: String?) {
    val ok: Boolean get() = error == null
}

/** DEB-4075 (DEB-4032, FR-5/FR-7) — the *Jellyfin collections* page's calls. Titles are named by jellystructure media id. */
object JellyfinCollectionsApi {
    private const val BASE = "/api/jellyfin-collections"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun page(): JellyfinCollectionsPageDto? = runCatching { httpClient.get(BASE).takeIf { it.status.isSuccess() }?.body<JellyfinCollectionsPageDto>() }.getOrNull()

    suspend fun suggestions(): List<JellyfinCollectionSuggestionDto>? =
        runCatching { httpClient.get("$BASE/suggestions").takeIf { it.status.isSuccess() }?.body<List<JellyfinCollectionSuggestionDto>>() }.getOrNull()

    suspend fun create(name: String, items: List<String>): JcAnswer = answer {
        httpClient.post(BASE) { contentType(ContentType.Application.Json); setBody(CreateJellyfinCollectionRequest(name, items)) }
    }

    suspend fun rename(id: String, name: String): JcAnswer = answer {
        httpClient.patch("$BASE/${encodeURIComponent(id)}") { contentType(ContentType.Application.Json); setBody(RenameJellyfinCollectionRequest(name)) }
    }

    suspend fun add(id: String, items: List<String>): JcAnswer = answer {
        httpClient.post("$BASE/${encodeURIComponent(id)}/items") { contentType(ContentType.Application.Json); setBody(AddJellyfinCollectionItemsRequest(items)) }
    }

    suspend fun remove(id: String, mediaId: String): JcAnswer = answer {
        httpClient.delete("$BASE/${encodeURIComponent(id)}/items/${encodeURIComponent(mediaId)}")
    }

    suspend fun delete(id: String): JcAnswer = answer { httpClient.delete("$BASE/${encodeURIComponent(id)}") }

    private suspend fun answer(call: suspend () -> HttpResponse): JcAnswer {
        val r = try { call() } catch (e: CancellationException) { throw e } catch (e: Throwable) { return JcAnswer(null, "The server didn't answer. Nothing was changed.") }
        if (!r.status.isSuccess()) {
            val text = runCatching { r.bodyAsText() }.getOrDefault("")
            val sentence = runCatching { (json.parseToJsonElement(text) as JsonObject)["error"]?.jsonPrimitive?.content }.getOrNull()
            return JcAnswer(null, sentence?.takeIf { it.isNotBlank() } ?: "The server refused (${r.status.value}). Nothing was changed.")
        }
        // A delete answers 204 with no body; every other write answers the collection.
        return JcAnswer(runCatching { r.body<JellyfinCollectionDto>() }.getOrNull(), null)
    }
}
