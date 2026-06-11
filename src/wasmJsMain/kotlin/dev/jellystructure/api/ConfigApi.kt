package dev.jellystructure.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    @SerialName("api_keys") val apiKeys: ApiKeys = ApiKeys(),
    val paths: Paths = Paths(),
    @SerialName("language_rules") val languageRules: LanguageRules = LanguageRules(),
    val behavior: Behavior = Behavior(),
)

@Serializable
data class ApiKeys(
    @SerialName("tmdb_v3_key") val tmdbV3Key: String = "",
    @SerialName("jellyfin_token") val jellyfinToken: String = "",
    @SerialName("jellyfin_url") val jellyfinUrl: String = "",
)

@Serializable
data class Paths(
    @SerialName("movies_dir") val moviesDir: String = "",
    @SerialName("tv_dir") val tvDir: String = "",
)

@Serializable
data class LanguageRules(
    @SerialName("audio_cascade") val audioCascade: List<String> = listOf("en"),
    @SerialName("sub_cascade") val subCascade: List<String> = listOf("en"),
)

@Serializable
data class Behavior(
    @SerialName("overwrite_nfo") val overwriteNfo: Boolean = false,
    @SerialName("fetch_images") val fetchImages: Boolean = true,
)

@Serializable
data class ConnectionTestResult(val jellyfin: Boolean, val tmdb: Boolean)

object ConfigApi {
    suspend fun get(): AppConfig? = runCatching {
        httpClient.get("/api/config").body<AppConfig>()
    }.getOrNull()

    suspend fun save(config: AppConfig): Boolean = runCatching {
        val response = httpClient.put("/api/config") {
            contentType(ContentType.Application.Json)
            setBody(config)
        }
        response.status == HttpStatusCode.NoContent
    }.getOrDefault(false)

    suspend fun testConnections(): ConnectionTestResult? = runCatching {
        httpClient.post("/api/connections/test").body<ConnectionTestResult>()
    }.getOrNull()
}
