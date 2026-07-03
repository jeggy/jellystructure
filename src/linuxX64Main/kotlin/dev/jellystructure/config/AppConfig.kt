package dev.jellystructure.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val acquisition: AcquisitionConfig? = null,
    // Phase 136 — Jellyseerr/Overseerr connection (replaces the retired chart/Discover-charts subsystem)
    val seerr: SeerrConfig? = null,
    // Phase 91 — scan pipeline
    @SerialName("scan_schedule") val scanSchedule: String = "",
    val scan: ScanConfig = ScanConfig(),
    // Phase 98 — tracker registry (announce-host → name mapping)
    val trackers: List<TrackerEntry> = emptyList(),
    // Phase 114 — realtime ingest (arr webhooks + Jellyfin LibraryChanged)
    val ingest: IngestConfig = IngestConfig(),
)

// Phase 114 — realtime ingest. `realtime` defaults on when a Jellyfin token is configured (checked at
// call sites, not here — this class has no access to the rest of AppConfig). `webhookSecret` gates
// POST /api/webhooks/{sonarr,radarr}; generated once and shown in Settings ▸ Download tools.
@Serializable
data class IngestConfig(
    val realtime: Boolean = true,
    @SerialName("webhook_secret") val webhookSecret: String = "",
)

// Phase 91 — scan pipeline config: [[scan.pipeline]] array of steps
@Serializable
data class ScanConfig(
    val pipeline: List<PipelineStep> = emptyList(),
)

@Serializable
data class PipelineStep(
    val step: String = "",
    val enabled: Boolean = true,
    // scan_files options
    @SerialName("recheck_unchanged") val recheckUnchanged: Boolean = false,
    @SerialName("refresh_this_year") val refreshThisYear: String = "weekly",
    @SerialName("refresh_1_5y") val refresh1To5y: String = "monthly",
    @SerialName("refresh_older") val refreshOlder: String = "6months",
    // pull_tmdb / download_artwork options
    val scope: String = "missing",  // missing|all
    // write_nfo options
    val overwrite: Boolean = false,
    // wait options
    val minutes: Int = 5,
    // notify options
    val on: String = "summary",  // summary|changes|errors
    // Phase 115 — detect_drift option: silently fix "Jellyfin behind" items (write NFO if needed + full
    // refresh) and only report real external drift, instead of just reporting every non-converged item.
    @SerialName("auto_reassert") val autoReassert: Boolean = false,
)

// Phase 136 — Jellyseerr/Overseerr connection. Mirrors ArrConfig's shape (Phase 54) so the Settings UI,
// ##KEEP##-masked-key save path, and test-connection route all follow the same established pattern.
// Absent or enabled=false ⇒ integration completely inert; per-user Request rows (Phase 137) and the TV
// Request tab (R171) both require this to be connected.
@Serializable
data class SeerrConfig(
    val enabled: Boolean = false,
    val url: String = "",
    @SerialName("api_key") val apiKey: String = "",
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

// Phase 106 — age-rating region cascade: an ordered list of ISO-3166-1 country codes. Empty = feature
// off (each title's own primary certification is used, unresolved). Global only (no per-library override).
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
    @SerialName("scan_episode_cap") val scanEpisodeCap: Int = 0, // 0 = unlimited (probe every episode); Phase 49
    @SerialName("tv_image_cache_mb") val tvImageCacheMb: Int = 2048, // R129: TV image-proxy disk-cache cap (0 = unlimited)
    @SerialName("notifications_webhook") val notificationsWebhook: String = "",
    @SerialName("notify_on_scan_done") val notifyOnScanDone: Boolean = true,
    @SerialName("notify_on_no_match") val notifyOnNoMatch: Boolean = false,
    @SerialName("notify_on_write_failed") val notifyOnWriteFailed: Boolean = true,
    @SerialName("notify_on_drift") val notifyOnDrift: Boolean = false,
    // Phase 118 — fires server_recovered_from_crash on the next boot after an unhandled fatal
    // exception (the crash webhook itself is always attempted, best-effort, synchronously, from the
    // crash hook — this toggle only gates the *recovery* notification).
    @SerialName("notify_on_crash") val notifyOnCrash: Boolean = true,
)

@Serializable
data class QBittorrentConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val enabled: Boolean = false,
    @SerialName("no_auth") val noAuth: Boolean = false,
    @SerialName("path_mappings") val pathMappings: List<QBittorrentPathMapping> = emptyList(),
    // Phase 99 — snapshot TTL in seconds (default 10 min)
    @SerialName("seeding_cache_ttl") val seedingCacheTtl: Long = 600L,
)

// Phase 98 — tracker registry entry
@Serializable
data class TrackerEntry(
    val name: String,
    @SerialName("private") val isPrivate: Boolean = false,
    val hosts: List<String> = emptyList(),
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
