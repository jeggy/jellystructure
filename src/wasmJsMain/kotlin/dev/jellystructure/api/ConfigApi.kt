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
    // Phase 178 §FR-178-3
    @SerialName("throttle_while_playing") val throttleWhilePlaying: Boolean = false,
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

// Phase 136 — Jellyseerr/Overseerr connection (replaces the retired DiscoverFeedConfig chart backend).
@Serializable
data class SeerrConfig(
    val enabled: Boolean = false,
    val url: String = "",
    @SerialName("api_key") val apiKey: String = "",
)

// Phase 157 — Bazarr subtitle connection.
// Phase 218 (FR-218-2) — mirrors the backend's ChromecastConfig.
@Serializable
data class ChromecastConfig(
    val enabled: Boolean = false,
    @SerialName("app_id") val appId: String = "",
    @SerialName("max_sessions") val maxSessions: Int = 2,
    /** Phase 227 — retired (moved to AppConfig.publicUrl); kept so an old response still decodes. Never sent. */
    @SerialName("public_url") val publicUrl: String = "",
)

/** Phase 218 (FR-218-5) — the backend's verdict after fetching its own /cast/ through the public URL. */
@Serializable
data class ReceiverCheck(val reachable: Boolean, val https: Boolean, val detail: String)

@Serializable
data class CastDeviceSummary(val name: String, @SerialName("last_seen") val lastSeen: Long)

/** Phase 218 (FR-218-7) — `verified` only once a real cast has enrolled a receiver. */
@Serializable
data class ChromecastStatus(
    val enabled: Boolean = false,
    @SerialName("app_id_set") val appIdSet: Boolean = false,
    @SerialName("receiver_url") val receiverUrl: String? = null,
    val verified: Boolean = false,
    val devices: List<CastDeviceSummary> = emptyList(),
    @SerialName("last_cast_at") val lastCastAt: Long? = null,
    @SerialName("max_sessions") val maxSessions: Int = 2,
    @SerialName("active_sessions") val activeSessions: Int = 0,
)

@Serializable
data class BazarrConfig(
    val enabled: Boolean = false,
    val url: String = "",
    @SerialName("api_key") val apiKey: String = "",
    @SerialName("auto_search_on_add") val autoSearchOnAdd: Boolean = false,
    @SerialName("show_history_on_title") val showHistoryOnTitle: Boolean = true,
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
    // Phase 150 — detect_segments options.
    @SerialName("detect_fingerprint") val detectFingerprint: Boolean = false,
    @SerialName("trust_stinger_tags") val trustStingerTags: Boolean = true,
    @SerialName("chapter_keywords") val chapterKeywords: List<String> = emptyList(),
)

@Serializable
data class ScanConfig(
    val pipeline: List<PipelineStep> = emptyList(),
    // Phase 178 §FR-178-2
    @SerialName("defer_while_playing") val deferWhilePlaying: Boolean = true,
)

// Phase 139 — request-language steering (Original vs Nordic/Danish etc.). Mirrors the backend
// dev.jellystructure.config.RequestLanguageIntent field-for-field, including the provisioned-state
// fields (*ProfileId/*FormatId) — those round-trip untouched since the Settings card only edits the
// admin-typed fields, never those.
@Serializable
data class RequestLanguageIntent(
    val id: String = "",
    val label: String = "",
    val flag: String = "",
    @SerialName("base_profile") val baseProfile: String = "",
    val match: String = "",
    val tags: List<String> = emptyList(),
    val strict: Boolean = false,
    val default: Boolean = false,
    @SerialName("radarr_profile_id") val radarrProfileId: Int? = null,
    @SerialName("sonarr_profile_id") val sonarrProfileId: Int? = null,
    @SerialName("radarr_format_id") val radarrFormatId: Int? = null,
    @SerialName("sonarr_format_id") val sonarrFormatId: Int? = null,
)

@Serializable
data class RequestLanguageConfig(
    val intents: List<RequestLanguageIntent> = emptyList(),
    @SerialName("kids_default") val kidsDefault: String? = null,
)

@Serializable
data class ProvisionPlanLine(val arrKind: String = "", val kind: String = "", val name: String = "", val action: String = "")

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
    val seerr: SeerrConfig? = null,
    val bazarr: BazarrConfig? = null,
    // Phase 218 (FR-218-2) — Chromecast; null = off, and off means the phone shows no cast button at all.
    val chromecast: ChromecastConfig? = null,
    /** Phase 227 — the installation's one public address (an https origin), at the root. */
    @SerialName("public_url") val publicUrl: String = "",
    @SerialName("scan_schedule") val scanSchedule: String = "",
    val scan: ScanConfig = ScanConfig(),
    val trackers: List<TrackerConfig> = emptyList(),
    @SerialName("request_language") val requestLanguage: RequestLanguageConfig = RequestLanguageConfig(),
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
    @SerialName("job_workers") val jobWorkers: Int = 2,
    @SerialName("scan_threads") val scanThreads: Int = 4,
    @SerialName("scan_interval_hours") val scanIntervalHours: Int = 0,
    @SerialName("scan_episode_cap") val scanEpisodeCap: Int = 0,
    @SerialName("tv_image_cache_mb") val tvImageCacheMb: Int = 2048,
    @SerialName("notifications_webhook") val notificationsWebhook: String = "",
    @SerialName("notify_on_scan_done") val notifyOnScanDone: Boolean = true,
    @SerialName("notify_on_no_match") val notifyOnNoMatch: Boolean = false,
    @SerialName("notify_on_write_failed") val notifyOnWriteFailed: Boolean = true,
    @SerialName("notify_on_drift") val notifyOnDrift: Boolean = false,
    @SerialName("notify_on_crash") val notifyOnCrash: Boolean = true,
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

// Phase 212 — mirrors dev.jellystructure.advisor.{AdvisorFinding,LibraryAdvisorSection,AdvisorResponse}.
@Serializable
data class AdvisorFinding(
    val id: String,
    val summary: String,
    @SerialName("current_value") val currentValue: String,
    @SerialName("cost_here") val costHere: String,
    @SerialName("navigation_path") val navigationPath: String,
    @SerialName("field_label") val fieldLabel: String,
    val recommendation: String,
    val tradeoff: String,
    // Phase 246 FR-246-9/10 — "critical" | "warning" | "info". Defaulted so an older backend (or the
    // memory-budget calculator, which produces findings of its own) still deserialises unchanged.
    val severity: String = "warning",
    val action: String? = null,
)

@Serializable
data class LibraryAdvisorSection(
    @SerialName("library_name") val libraryName: String,
    val findings: List<AdvisorFinding> = emptyList(),
    // Phase 242 FR-242-7 — "Jellyfin returned no options for this library, so nothing was checked",
    // which an empty findings list would otherwise render as "this library is fine".
    @SerialName("options_unavailable") val optionsUnavailable: Boolean = false,
)

@Serializable
data class AdvisorResponse(
    val reachable: Boolean,
    @SerialName("computed_at") val computedAt: Long = 0,
    @SerialName("server_wide") val serverWide: List<AdvisorFinding> = emptyList(),
    @SerialName("per_library") val perLibrary: List<LibraryAdvisorSection> = emptyList(),
)

// Phase 215 — mirrors dev.jellystructure.advisor.{MemoryBudgetLine,MemoryBudgetResult}.
@Serializable
data class MemoryBudgetLine(val label: String, val value: String)

@Serializable
data class MemoryBudgetResult(
    val ok: Boolean,
    @SerialName("refusal_reason") val refusalReason: String? = null,
    val arithmetic: List<MemoryBudgetLine> = emptyList(),
    val findings: List<AdvisorFinding> = emptyList(),
    @SerialName("survival_note") val survivalNote: String = "",
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

// Phase 166 (FR-166-4/5) — save-path validation error + the Custom cron field's live-preview shape.
@Serializable
data class SaveConfigResult(val ok: Boolean, val field: String? = null, val error: String? = null)

@Serializable
private data class ScheduleCheckRequest(val cron: String)

@Serializable
private data class ConfigErrorBody(val field: String? = null, val error: String? = null)

@Serializable
data class ScheduleCheckResponse(
    val valid: Boolean,
    val description: String? = null,
    val error: String? = null,
    val nextRuns: List<Long> = emptyList(),
)

object ConfigApi {
    suspend fun get(): ConfigResponse? = runCatching {
        httpClient.get("/api/config").body<ConfigResponse>()
    }.getOrNull()

    suspend fun save(config: AppConfig): SaveConfigResult = runCatching {
        val response = httpClient.put("/api/config") {
            contentType(ContentType.Application.Json)
            setBody(config)
        }
        when (response.status) {
            HttpStatusCode.NoContent -> SaveConfigResult(ok = true)
            // 422 = Phase 166's schedule backstop; 400 = Phase 227's public-address rule. Same body shape.
            HttpStatusCode.UnprocessableEntity, HttpStatusCode.BadRequest -> {
                val body = runCatching { response.body<ConfigErrorBody>() }.getOrNull()
                SaveConfigResult(ok = false, field = body?.field, error = body?.error ?: "Invalid configuration.")
            }
            else -> SaveConfigResult(ok = false, error = "Save failed (${response.status.value}).")
        }
    }.getOrDefault(SaveConfigResult(ok = false, error = "Save failed — couldn't reach the server."))

    /** Live validation + next-5-runs preview for the Settings Custom cron field, computed by the exact
     *  backend code [save] itself will validate against — see ScheduleCheckResponse's doc. */
    suspend fun validateSchedule(cron: String): ScheduleCheckResponse? = runCatching {
        httpClient.post("/api/config/validate-schedule") {
            contentType(ContentType.Application.Json)
            setBody(ScheduleCheckRequest(cron))
        }.body<ScheduleCheckResponse>()
    }.getOrNull()

    suspend fun testConnections(): ConnectionTestResult? = runCatching {
        httpClient.post("/api/connections/test").body<ConnectionTestResult>()
    }.getOrNull()

    suspend fun getJellyfinLibraries(): List<JellyfinLibrary>? = runCatching {
        httpClient.get("/api/jellyfin/libraries").body<List<JellyfinLibrary>>()
    }.getOrNull()

    // Phase 212 — Settings → Libraries' Jellyfin settings advisor.
    suspend fun getJellyfinAdvisor(): AdvisorResponse? = runCatching {
        httpClient.get("/api/jellyfin/advisor").body<AdvisorResponse>()
    }.getOrNull()

    // Phase 215 — the memory budget calculator.
    suspend fun calculateMemoryBudget(budgetGb: Double): MemoryBudgetResult? = runCatching {
        httpClient.post("/api/jellyfin/memory-budget") {
            contentType(ContentType.Application.Json)
            setBody("""{"budget_gb":$budgetGb}""")
        }.body<MemoryBudgetResult>()
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
    suspend fun testSeerr(url: String, apiKey: String): ArrTestResult? = testArr("seerr", url, apiKey)
    suspend fun testBazarr(url: String, apiKey: String): ArrTestResult? = testArr("bazarr", url, apiKey)

    // Phase 218 (FR-218-5/7) — the Chromecast card's reachability check and honest status list.
    suspend fun checkChromecast(url: String): ReceiverCheck? = runCatching {
        httpClient.post("/api/config/chromecast/check") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"${url.replace("\\", "\\\\").replace("\"", "\\\"")}"}""")
        }.body<ReceiverCheck>()
    }.getOrNull()

    suspend fun chromecastStatus(): ChromecastStatus? = runCatching {
        httpClient.get("/api/config/chromecast/status").body<ChromecastStatus>()
    }.getOrNull()

    // Phase 221 (FR-221-2/3/4) — a server-side test delivery, and the standing status + findings.
    suspend fun testWebhook(url: String): WebhookTestResult? = runCatching {
        httpClient.post("/api/config/test-webhook") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"${url.replace("\\", "\\\\").replace("\"", "\\\"")}"}""")
        }.body<WebhookTestResult>()
    }.getOrNull()

    suspend fun webhookStatus(): WebhookStatusResponse? = runCatching {
        httpClient.get("/api/config/webhook-status").body<WebhookStatusResponse>()
    }.getOrNull()

    // Phase 139 — "Set up profiles in Radarr/Sonarr": preview shows what would be created/updated
    // (read-only), provision actually does it (idempotent — safe to re-run).
    suspend fun previewRequestLanguage(): List<ProvisionPlanLine>? = runCatching {
        httpClient.get("/api/config/request-language/preview").body<List<ProvisionPlanLine>>()
    }.getOrNull()

    suspend fun provisionRequestLanguage(): List<ProvisionPlanLine>? = runCatching {
        httpClient.post("/api/config/request-language/provision").body<List<ProvisionPlanLine>>()
    }.getOrNull()
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

// Phase 221 — mirrors the backend's WebhookTestResult / WebhookStatusResponse. `findings` reuse the
// phase-212 AdvisorFinding shape so advisorFindingHtml renders them unchanged.
@Serializable
data class WebhookTestResult(val ok: Boolean, val status: Int? = null, @SerialName("elapsed_ms") val elapsedMs: Long = 0, val detail: String = "")

@Serializable
data class WebhookTargetStatus(
    val url: String,
    @SerialName("consecutive_failures") val consecutiveFailures: Int = 0,
    @SerialName("last_attempt_at") val lastAttemptAt: Long? = null,
    @SerialName("last_success_at") val lastSuccessAt: Long? = null,
    @SerialName("last_failure_at") val lastFailureAt: Long? = null,
    @SerialName("last_failure_reason") val lastFailureReason: String? = null,
    @SerialName("last_elapsed_ms") val lastElapsedMs: Long? = null,
)

@Serializable
data class WebhookStatusResponse(
    val configured: Boolean = false,
    val target: WebhookTargetStatus? = null,
    val findings: List<AdvisorFinding> = emptyList(),
)
