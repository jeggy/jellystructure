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
data class QBittorrentPathMapping(
    val local: String = "",
    val remote: String = "",
)

@Serializable
data class QBittorrentConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val enabled: Boolean = false,
    @SerialName("no_auth") val noAuth: Boolean = false,
    @SerialName("path_mappings") val pathMappings: List<QBittorrentPathMapping> = emptyList(),
    @SerialName("seeding_cache_ttl") val seedingCacheTtl: Long = 600L,
)

@Serializable
data class TrackerConfig(
    val name: String,
    @SerialName("private") val isPrivate: Boolean = false,
    val hosts: List<String> = emptyList(),
)

@Serializable
data class ArrConfig(
    val enabled: Boolean = false,
    val url: String = "",
    @SerialName("api_key") val apiKey: String = "",
    @SerialName("rescan_after_write") val rescanAfterWrite: Boolean = true,
)

@Serializable
data class PipelineStep(
    val step: String = "",
    val enabled: Boolean = true,
    @SerialName("recheck_unchanged") val recheckUnchanged: Boolean = false,
    @SerialName("refresh_this_year") val refreshThisYear: String = "weekly",
    @SerialName("refresh_1_5y") val refresh1To5y: String = "monthly",
    @SerialName("refresh_older") val refreshOlder: String = "6months",
    val scope: String = "missing",
    val overwrite: Boolean = false,
    val minutes: Int = 5,
    val on: String = "summary",
)

@Serializable
data class ScanConfig(val pipeline: List<PipelineStep> = emptyList())

@Serializable
data class AppConfig(
    @SerialName("api_keys") val apiKeys: ApiKeys = ApiKeys(),
    @SerialName("language_rules") val languageRules: LanguageRules = LanguageRules(),
    val metadata: MetadataConfig = MetadataConfig(),
    val behavior: Behavior = Behavior(),
    val libraries: List<LibraryMapping> = emptyList(),
    val qbittorrent: QBittorrentConfig? = null,
    val radarr: ArrConfig? = null,
    val sonarr: ArrConfig? = null,
    val discover: DiscoverFeedConfig? = null,
    @SerialName("scan_schedule") val scanSchedule: String = "",
    val scan: ScanConfig = ScanConfig(),
    val trackers: List<TrackerConfig> = emptyList(),
)

@Serializable
data class ApiKeys(
    @SerialName("tmdb_v3_key") val tmdbV3Key: String = "",
    @SerialName("jellyfin_token") val jellyfinToken: String = "",
    @SerialName("jellyfin_url") val jellyfinUrl: String = "",
    @SerialName("streaming_availability_key") val streamingAvailabilityKey: String = "",
)

@Serializable
data class DiscoverFeedConfig(
    val enabled: Boolean = false,
    val providers: List<String> = listOf("netflix"),
    val regions: List<String> = listOf("DK"),
    @SerialName("refresh_hours") val refreshHours: Int = 168,
)

@Serializable
data class LanguageRules(
    @SerialName("fallback_language") val fallbackLanguage: String = "en",
)

// Phase 106 — age-rating region cascade: an ordered list of ISO-3166-1 country codes.
@Serializable
data class MetadataConfig(
    @SerialName("age_rating_cascade") val ageRatingCascade: List<String> = emptyList(),
)

@Serializable
data class Behavior(
    @SerialName("overwrite_nfo") val overwriteNfo: Boolean = false,
    @SerialName("fetch_images") val fetchImages: Boolean = true,
    @SerialName("tell_jellyfin") val tellJellyfin: Boolean = true,
    @SerialName("scan_workers") val scanWorkers: Int = 1,
    @SerialName("scan_threads") val scanThreads: Int = 4,
    @SerialName("scan_interval_hours") val scanIntervalHours: Int = 0,
    @SerialName("scan_episode_cap") val scanEpisodeCap: Int = 0,
    @SerialName("tv_image_cache_mb") val tvImageCacheMb: Int = 2048,
    @SerialName("notifications_webhook") val notificationsWebhook: String = "",
    @SerialName("notify_on_scan_done") val notifyOnScanDone: Boolean = true,
    @SerialName("notify_on_no_match") val notifyOnNoMatch: Boolean = false,
    @SerialName("notify_on_write_failed") val notifyOnWriteFailed: Boolean = true,
    @SerialName("notify_on_drift") val notifyOnDrift: Boolean = false,
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

    suspend fun testQBittorrent(url: String, username: String, password: String): QBittorrentTestResult? = runCatching {
        httpClient.post("/api/config/test-qbittorrent") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"${url.replace("\"","\\\"")}","username":"${username.replace("\"","\\\"")}","password":"${password.replace("\"","\\\"")}"}""")
        }.body<QBittorrentTestResult>()
    }.getOrNull()

    suspend fun testRadarr(url: String, apiKey: String): ArrTestResult? = testArr("radarr", url, apiKey)
    suspend fun testSonarr(url: String, apiKey: String): ArrTestResult? = testArr("sonarr", url, apiKey)
    private suspend fun testArr(kind: String, url: String, apiKey: String): ArrTestResult? = runCatching {
        httpClient.post("/api/config/test-$kind") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"${url.replace("\"","\\\"")}","apiKey":"${apiKey.replace("\"","\\\"")}"}""")
        }.body<ArrTestResult>()
    }.getOrNull()

    /** kind = "radarr" | "sonarr". Uses the saved creds on the server. */
    suspend fun getArrRootFolders(kind: String): List<String> = runCatching {
        httpClient.get("/api/config/$kind/root-folders").body<List<String>>()
    }.getOrDefault(emptyList())
}

@Serializable
data class HealthCheck(val name: String, val ok: Boolean, val detail: String)

@Serializable
data class HealthReport(val checks: List<HealthCheck>)

@Serializable
data class QBittorrentTestResult(val ok: Boolean, val detail: String, val torrentCount: Int? = null)

@Serializable
data class ArrTestResult(
    val ok: Boolean,
    val detail: String,
    val version: String? = null,
    val rootFolders: List<String>? = null,
)
