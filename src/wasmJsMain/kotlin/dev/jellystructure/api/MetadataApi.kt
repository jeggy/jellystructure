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
)

@Serializable
data class JsTag(
    val name: String,
    val color: String = "#6b7280",
    val description: String = "",
)

@Serializable
data class JsTagWithCount(
    val name: String,
    val color: String,
    val description: String,
    val count: Int,
)

@Serializable
data class TagsResponse(
    val jsTags: List<JsTagWithCount>,
    val otherTags: List<MetadataEntry>,
)

@Serializable
data class CreateTagRequest(val name: String, val color: String = "#6b7280", val description: String = "")

@Serializable
data class UpdateTagRequest(val color: String? = null, val description: String? = null)

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

    suspend fun createTag(name: String, color: String, description: String): JsTag? = runCatching {
        val resp = httpClient.post("/api/tags") {
            contentType(ContentType.Application.Json)
            setBody(CreateTagRequest(name, color, description))
        }
        if (resp.status == HttpStatusCode.Created) resp.body<JsTag>() else null
    }.getOrNull()

    suspend fun updateTag(name: String, color: String?, description: String?): JsTag? = runCatching {
        httpClient.patch("/api/tags/${encodeURIComponent(name)}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateTagRequest(color, description))
        }.body<JsTag>()
    }.getOrNull()

    suspend fun deleteTag(name: String): Boolean = runCatching {
        httpClient.delete("/api/tags/${encodeURIComponent(name)}").status == HttpStatusCode.NoContent
    }.getOrElse { false }
}
