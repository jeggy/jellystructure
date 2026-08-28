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
    // Phase 157 — Bazarr subtitle integration
    val bazarr: BazarrConfig? = null,
    // Phase 91 — scan pipeline
    @SerialName("scan_schedule") val scanSchedule: String = "",
    val scan: ScanConfig = ScanConfig(),
    // Phase 98 — tracker registry (announce-host → name mapping)
    val trackers: List<TrackerEntry> = emptyList(),
    // Phase 114 — realtime ingest (arr webhooks + Jellyfin LibraryChanged)
    val ingest: IngestConfig = IngestConfig(),
    // Phase 139 — request-language steering (Original vs Nordic/Danish etc.)
    @SerialName("request_language") val requestLanguage: RequestLanguageConfig = RequestLanguageConfig(),
)

// Phase 114 — realtime ingest. `realtime` defaults on when a Jellyfin token is configured (checked at
// call sites, not here — this class has no access to the rest of AppConfig). `webhookSecret` gates
// POST /api/webhooks/{sonarr,radarr,jellyfin} (Phase 165 adds the third); generated once and shown in
// Settings ▸ Download tools.
@Serializable
data class IngestConfig(
    val realtime: Boolean = true,
    @SerialName("webhook_secret") val webhookSecret: String = "",
    // Phase 165 (FR-165-3, open question 2) — where JELLYFIN should reach THIS jellystructure install
    // to deliver its webhook (e.g. "http://192.0.2.10:9505") — cannot be inferred (the value a browser
    // sees as this page's own address is not necessarily reachable from Jellyfin's own network
    // position). Blank until the admin sets it; the one-click setup refuses to proceed without it
    // rather than guessing wrong and silently never delivering. Same "explicit override, not derived"
    // shape as Towo's own "where runners connect" URL (Phase 162).
    @SerialName("jellyfin_reach_url") val jellyfinReachUrl: String = "",
)

// Phase 91 — scan pipeline config: [[scan.pipeline]] array of steps
@Serializable
data class ScanConfig(
    val pipeline: List<PipelineStep> = emptyList(),
    // Phase 178 §FR-178-2 — defer scan_files' probe-heavy work and fetch_artwork (plus detect_segments'
    // own queue, gated the same way) while a TV is playing, for SCHEDULED and event-driven (realtime
    // ingest) runs only — an operator's own manual "Scan library"/"Run pipeline now" click always
    // proceeds (FR-178-4). Default true: a single-user household and a many-viewer one want opposite
    // answers, but "don't compete with the TV for disk" is the safer default either way.
    @SerialName("defer_while_playing") val deferWhilePlaying: Boolean = true,
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
    // Phase 150 — detect_segments options. detectFingerprint (FR-SEG1-4) gates the heavier
    // cross-episode Chromaprint intro-fingerprinting tier, on top of detect_segments itself.
    @SerialName("detect_fingerprint") val detectFingerprint: Boolean = false,
    @SerialName("trust_stinger_tags") val trustStingerTags: Boolean = true,
    @SerialName("chapter_keywords") val chapterKeywords: List<String> = emptyList(),
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

// Phase 157 — Bazarr subtitle connection. Mirrors SeerrConfig's shape (no root-folder/rescan concept,
// same as Seerr — Bazarr isn't a library-mapping source either): enabled/url/api_key, plus
// auto_search_on_add. Absent or enabled=false ⇒ every subtitle surface in the admin UI disappears.
@Serializable
data class BazarrConfig(
    val enabled: Boolean = false,
    val url: String = "",
    @SerialName("api_key") val apiKey: String = "",
    @SerialName("auto_search_on_add") val autoSearchOnAdd: Boolean = false,
    @SerialName("show_history_on_title") val showHistoryOnTitle: Boolean = true,
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

/**
 * Phase 139 — the request-language intent catalog. [intents] is what Settings ▸ Download tools ▸
 * Request languages edits; [kidsDefault], if set, is an intent [RequestLanguageIntent.id] that wins
 * over the catalog's own `default`-flagged intent whenever the requesting device is a Kids profile
 * (`device.isKids`). Empty/absent `intents` ⇒ the feature is fully inert — requests behave exactly as
 * before (no `profileId`/`tags` added to the Seerr call).
 */
@Serializable
data class RequestLanguageConfig(
    val intents: List<RequestLanguageIntent> = emptyList(),
    @SerialName("kids_default") val kidsDefault: String? = null,
)

/**
 * Phase 139 — one request-language intent (e.g. "Original" or "Dansk / Nordic"). [match] blank ⇒ no
 * custom format is provisioned; the request just uses [baseProfile] as-is (this is `original`'s shape —
 * nothing to clone, nothing to score). [match] non-blank ⇒ Settings' "Set up profiles" action
 * provisions a release-title custom format + a clone of [baseProfile] scoring it, and fills the
 * `*ProfileId`/`*FormatId` fields below — those are **derived state**, not admin-typed, refreshed on
 * every provisioning run (never hand-edit them in config.toml).
 */
@Serializable
data class RequestLanguageIntent(
    val id: String,
    val label: String,
    val flag: String = "",                                     // ISO-639-1 language code (e.g. "da") — matches AudioFlagStrip's flag lookup; blank = original-language flag / globe
    @SerialName("base_profile") val baseProfile: String = "",   // an existing Radarr/Sonarr quality-profile name
    val match: String = "",                                     // release-title regex; blank = use baseProfile as-is
    val tags: List<String> = emptyList(),                       // optional *arr indexer tag names (traffic hygiene, not required for correctness)
    val strict: Boolean = false,                                // true = only a matching release ever qualifies (minFormatScore gate)
    val default: Boolean = false,                               // the non-kids fallback when no per-viewer override exists
    @SerialName("radarr_profile_id") val radarrProfileId: Int? = null,
    @SerialName("sonarr_profile_id") val sonarrProfileId: Int? = null,
    @SerialName("radarr_format_id") val radarrFormatId: Int? = null,
    @SerialName("sonarr_format_id") val sonarrFormatId: Int? = null,
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
    // Phase 155 — cascade-resolved certification code (CertificationResolver.resolve()'s .code, e.g.
    // "PG-13") -> normalized age 0-18. Keyed on the RESOLVED code, not the raw per-country map — see
    // the spec's Backend review addendum for why (~22 rows for a real library vs. 200+ raw, ambiguous
    // per-country strings). Empty until first seeded (CertificationResolver.AGE_SEED) or until an
    // operator maps something; CertificationResolver.normalizedAge() treats any code missing from this
    // map (including "no cascade match at all") as 18.
    @SerialName("age_rating_map") val ageRatingMap: Map<String, Int> = emptyMap(),
)

@Serializable
data class Behavior(
    @SerialName("overwrite_nfo") val overwriteNfo: Boolean = false,
    @SerialName("fetch_images") val fetchImages: Boolean = true,
    @SerialName("tell_jellyfin") val tellJellyfin: Boolean = true,
    @SerialName("scan_workers") val scanWorkers: Int = 1,
    // Phase 164 — the detect_segments job queue's own worker lane, deliberately a separate (smaller)
    // knob from scan_workers: this lane runs CONCURRENTLY with normal request serving and every other
    // pipeline step now, not sequentially inside a scan/pipeline run — see MediaJobQueue.kt.
    @SerialName("segment_workers") val segmentWorkers: Int = 2,
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
    // Phase 178 §FR-178-3 — apply qBittorrent's own alternative speed limits while a TV is playing.
    // Default false: unlike deferring our OWN pipeline work, this reaches into a service the operator
    // owns and mutates its live state, so it must be opted into, not assumed.
    @SerialName("throttle_while_playing") val throttleWhilePlaying: Boolean = false,
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
