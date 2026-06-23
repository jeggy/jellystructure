package dev.jellystructure.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    @SerialName("api_keys") val apiKeys: ApiKeys = ApiKeys(),
    @SerialName("language_rules") val languageRules: LanguageRules = LanguageRules(),
    val behavior: Behavior = Behavior(),
    val libraries: List<LibraryMapping> = emptyList(),
    val qbittorrent: QBittorrentConfig? = null,
    val radarr: ArrConfig? = null,
    val sonarr: ArrConfig? = null,
    val acquisition: AcquisitionConfig? = null,
)

// Phase 56 — acquisition engine settings. Absent or enabled=false ⇒ no requests/polling.
// Flat keys (not [acquisition.radarr] sub-tables) to keep ktoml serialization trivial.
@Serializable
data class AcquisitionConfig(
    val enabled: Boolean = false,
    @SerialName("poll_seconds") val pollSeconds: Int = 10,
    @SerialName("radarr_root_folder") val radarrRootFolder: String = "",
    @SerialName("radarr_quality_profile") val radarrQualityProfile: String = "",
    @SerialName("sonarr_root_folder") val sonarrRootFolder: String = "",
    @SerialName("sonarr_quality_profile") val sonarrQualityProfile: String = "",
    @SerialName("sonarr_monitor") val sonarrMonitor: String = "all", // all|future|firstSeason|latestSeason|pilot
    @SerialName("sonarr_season_folder") val sonarrSeasonFolder: Boolean = true,
)

// Phase 54 — Radarr/Sonarr connection (movies / series). Opt-in, read + rescan only.
// Absent or enabled=false ⇒ that integration is completely inert.
@Serializable
data class ArrConfig(
    val enabled: Boolean = false,
    val url: String = "",
    @SerialName("api_key") val apiKey: String = "",
    @SerialName("rescan_after_write") val rescanAfterWrite: Boolean = true,
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
    @SerialName("scan_episode_cap") val scanEpisodeCap: Int = 0, // 0 = unlimited (probe every episode); Phase 49
    @SerialName("notifications_webhook") val notificationsWebhook: String = "",
    @SerialName("notify_on_scan_done") val notifyOnScanDone: Boolean = true,
    @SerialName("notify_on_no_match") val notifyOnNoMatch: Boolean = false,
    @SerialName("notify_on_write_failed") val notifyOnWriteFailed: Boolean = true,
    @SerialName("notify_on_drift") val notifyOnDrift: Boolean = false,
)

@Serializable
data class QBittorrentConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val enabled: Boolean = false,
    @SerialName("no_auth") val noAuth: Boolean = false,
    @SerialName("path_mappings") val pathMappings: List<QBittorrentPathMapping> = emptyList(),
)

@Serializable
data class QBittorrentPathMapping(
    val local: String,
    val remote: String,
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
