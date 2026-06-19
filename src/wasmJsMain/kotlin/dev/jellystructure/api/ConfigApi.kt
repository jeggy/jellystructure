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
    @SerialName("language_rules") val languageRules: LanguageRules = LanguageRules(),
    val behavior: Behavior = Behavior(),
    val libraries: List<LibraryMapping> = emptyList(),
)

@Serializable
data class ApiKeys(
    @SerialName("tmdb_v3_key") val tmdbV3Key: String = "",
    @SerialName("jellyfin_token") val jellyfinToken: String = "",
    @SerialName("jellyfin_url") val jellyfinUrl: String = "",
)

@Serializable
data class LanguageRules(
    @SerialName("fallback_language") val fallbackLanguage: String = "en",
)

@Serializable
data class Behavior(
    @SerialName("overwrite_nfo") val overwriteNfo: Boolean = false,
    @SerialName("fetch_images") val fetchImages: Boolean = true,
    @SerialName("watch_enabled") val watchEnabled: Boolean = false,
    @SerialName("tell_jellyfin") val tellJellyfin: Boolean = true,
    @SerialName("scan_workers") val scanWorkers: Int = 1,
    @SerialName("scan_threads") val scanThreads: Int = 4,
    @SerialName("scan_interval_hours") val scanIntervalHours: Int = 0,
    @SerialName("notifications_webhook") val notificationsWebhook: String = "",
)

@Serializable
data class LibraryMapping(
    @SerialName("jellyfin_id") val jellyfinId: String = "",
    val name: String = "",
    @SerialName("collection_type") val collectionType: String = "",
    @SerialName("jellyfin_path") val jellyfinPath: String = "",
    @SerialName("local_path") val localPath: String = "",
    val skip: Boolean = false,
    @SerialName("fallback_language") val fallbackLanguage: String? = null,
)

@Serializable
data class JellyfinLibrary(
    @SerialName("ItemId") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("Locations") val locations: List<String> = emptyList(),
)

@Serializable
data class ConnectionTestResult(val jellyfin: Boolean, val tmdb: Boolean)

@Serializable
data class ConfigResponse(
    val config: AppConfig,
    val effectiveScanThreads: Int,
)

@Serializable
data class LibraryPathDiag(
    val name: String,
    val jellyfinPath: String = "",
    val localPath: String = "",
    val matchPrefix: String,
    val localExists: Boolean,
)

object ConfigApi {
    suspend fun get(): ConfigResponse? = runCatching {
        httpClient.get("/api/config").body<ConfigResponse>()
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

    suspend fun getJellyfinLibraries(): List<JellyfinLibrary>? = runCatching {
        httpClient.get("/api/jellyfin/libraries").body<List<JellyfinLibrary>>()
    }.getOrNull()

    suspend fun pathCheck(): List<LibraryPathDiag>? = runCatching {
        httpClient.get("/api/config/path-check").body<List<LibraryPathDiag>>()
    }.getOrNull()

    suspend fun getHealthFull(): HealthReport? = runCatching {
        httpClient.get("/api/health/full").body<HealthReport>()
    }.getOrNull()
}

@Serializable
data class HealthCheck(val name: String, val ok: Boolean, val detail: String)

@Serializable
data class HealthReport(val checks: List<HealthCheck>)
