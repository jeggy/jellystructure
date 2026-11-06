package dev.jellystructure.api

import dev.jellystructure.encodeURIComponent
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable
data class MetadataEntry(
    val name: String,
    val count: Int,
    val tmdbId: Int? = null,
    val logoPath: String? = null,
    val hasLogo: Boolean = false,
    /** Phase 271 (FR-271-8) — genres only; see the server's `MetadataEntry`. */
    val labels: List<GenreLabelEntry> = emptyList(),
    val handAdded: Boolean = false,
)

/** Phase 271 (FR-271-8) — one label of a genre, the languages it is used in, and how many titles store it. */
@Serializable
data class GenreLabelEntry(val label: String, val languages: List<String> = emptyList(), val titles: Int = 0)

@Serializable
data class BatchLogoResult(val fetched: Int, val skipped: Int, val failed: Int)

@Serializable
data class JsTag(
    val name: String,
    val color: String = "#6b7280",
    val description: String = "",
    /** DEB-3701 FR-1 — empty = *All*. */
    val scopes: List<String> = emptyList(),
)

@Serializable
data class JsTagWithCount(
    val name: String,
    val color: String,
    val description: String,
    val count: Int,
    val scopes: List<String> = emptyList(),
    /** DEB-3701 FR-4 — items that carry the tag outside its scope. */
    val outsideScope: Int = 0,
    val outsideByKind: Map<String, Int> = emptyMap(),
    /** DEB-3704 FR-8 — tagged songs and albums no longer in the library. */
    val goneMusic: Int = 0,
)

@Serializable
data class TagsResponse(
    val jsTags: List<JsTagWithCount>,
    val otherTags: List<MetadataEntry>,
)

@Serializable
data class CreateTagRequest(val name: String, val color: String = "#6b7280", val description: String = "", val scopes: List<String> = emptyList())

@Serializable
data class UpdateTagRequest(val color: String? = null, val description: String? = null, val scopes: List<String>? = null)

@Serializable
data class OutsideScopeResponse(val outside: Int = 0, val byKind: Map<String, Int> = emptyMap())

@Serializable
data class RemoveOutsideScopeResponse(val removed: Int = 0)

// Phase 155 — one cascade-resolved certification code, its normalized age (null = unmapped, the
// stepper defaults to 18) and how many items resolve to it.
@Serializable
data class AgeRatingRow(val code: String, val system: String? = null, val itemCount: Int, val age: Int? = null)

@Serializable
data class AgeRatingsResponse(val mapped: List<AgeRatingRow>, val unmapped: List<AgeRatingRow>, val cascadeConfigured: Boolean)

@Serializable
data class SetAgeRatingRequest(val code: String, val age: Int)

object MetadataApi {
    suspend fun getStudios(sort: String = "count"): List<MetadataEntry>? = runCatching {
        httpClient.get("/api/metadata/studios?sort=$sort").body<List<MetadataEntry>>()
    }.getOrNull()

    suspend fun getNetworks(sort: String = "count"): List<MetadataEntry>? = runCatching {
        httpClient.get("/api/metadata/networks?sort=$sort").body<List<MetadataEntry>>()
    }.getOrNull()

    suspend fun getGenres(sort: String = "count"): List<MetadataEntry>? = runCatching {
        httpClient.get("/api/metadata/genres?sort=$sort").body<List<MetadataEntry>>()
    }.getOrNull()

    suspend fun getTags(sort: String = "count"): TagsResponse? = runCatching {
        httpClient.get("/api/metadata/tags?sort=$sort").body<TagsResponse>()
    }.getOrNull()

    suspend fun getAllJsTags(): List<JsTag>? = runCatching {
        httpClient.get("/api/tags").body<List<JsTag>>()
    }.getOrNull()

    suspend fun getAgeRatings(): AgeRatingsResponse? = runCatching {
        httpClient.get("/api/metadata/age-ratings").body<AgeRatingsResponse>()
    }.getOrNull()

    suspend fun setAgeRating(code: String, age: Int): Boolean = runCatching {
        httpClient.post("/api/metadata/age-ratings") {
            contentType(ContentType.Application.Json)
            setBody(SetAgeRatingRequest(code, age))
        }.status == HttpStatusCode.OK
    }.getOrDefault(false)

    suspend fun suggestAgeRatings(): AgeRatingsResponse? = runCatching {
        httpClient.post("/api/metadata/age-ratings/suggest").body<AgeRatingsResponse>()
    }.getOrNull()

    suspend fun createTag(name: String, color: String, description: String, scopes: List<String> = emptyList()): JsTag? = runCatching {
        val resp = httpClient.post("/api/tags") {
            contentType(ContentType.Application.Json)
            setBody(CreateTagRequest(name, color, description, scopes))
        }
        if (resp.status == HttpStatusCode.Created) resp.body<JsTag>() else null
    }.getOrNull()

    suspend fun updateTag(name: String, color: String?, description: String?, scopes: List<String>? = null): JsTag? = runCatching {
        httpClient.patch("/api/tags/${encodeURIComponent(name)}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateTagRequest(color, description, scopes))
        }.body<JsTag>()
    }.getOrNull()

    /** DEB-3701 FR-4 — items that carry the tag outside [scopes] (`null` = the stored scope). */
    suspend fun tagOutsideScope(name: String, scopes: List<String>? = null): OutsideScopeResponse? = runCatching {
        val q = scopes?.let { "?scopes=${it.joinToString(",")}" } ?: ""
        httpClient.get("/api/tags/${encodeURIComponent(name)}/outside-scope$q").body<OutsideScopeResponse>()
    }.getOrNull()

    suspend fun removeTagOutsideScope(name: String): Int? = runCatching {
        httpClient.post("/api/tags/${encodeURIComponent(name)}/remove-outside-scope").body<RemoveOutsideScopeResponse>().removed
    }.getOrNull()

    suspend fun deleteTag(name: String): Boolean = runCatching {
        httpClient.delete("/api/tags/${encodeURIComponent(name)}").status == HttpStatusCode.NoContent
    }.getOrElse { false }

    suspend fun fetchLogoBatch(kind: String): BatchLogoResult? = runCatching {
        httpClient.post("/api/metadata/$kind/artwork/batch").body<BatchLogoResult>()
    }.getOrNull()
}
