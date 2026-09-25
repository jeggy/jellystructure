package dev.jellystructure.server.routes

import dev.jellystructure.arr.ArrClient
import dev.jellystructure.bazarr.BazarrClient
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.QBittorrentConfig
import dev.jellystructure.seerr.SeerrClient
import dev.jellystructure.torrent.QBittorrentClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable

@Serializable
data class ConfigResponse(
    val config: AppConfig,
    val effectiveScanThreads: Int,
)

// Phase 166 (FR-166-5) — request/response shape for the Settings Custom cron field's live validation.
@Serializable
data class ScheduleCheckRequest(val cron: String)

@Serializable
data class ScheduleCheckResponse(
    val valid: Boolean,
    val description: String? = null,
    val error: String? = null,
    val nextRuns: List<Long> = emptyList(),
)

@Serializable
data class LibraryPathDiag(
    val name: String,
    val jellyfinPath: String,
    val localPath: String,
    val matchPrefix: String,
    val localExists: Boolean,
)

@Serializable
private data class TestQBittorrentRequest(
    val url: String,
    val username: String,
    val password: String,
)

@Serializable
data class TestQBittorrentResult(
    val ok: Boolean,
    val detail: String,
    val torrentCount: Int? = null,
)

@Serializable
private data class TestArrRequest(
    val url: String,
    val apiKey: String,
)

@Serializable
data class ArrTestResult(
    val ok: Boolean,
    val detail: String,
    val version: String? = null,
    val rootFolders: List<String>? = null,
)

// Security fix (2026-08-02 review, finding H3) — GET /config used to return AppConfig verbatim,
// including every stored secret in plaintext (Jellyfin admin token, TMDB key, qBittorrent password,
// Radarr/Sonarr/Seerr API keys, the *arr webhook secret). One XSS, one leaked browser profile, or one
// stolen js_session cookie was enough to walk off with the entire credential set. The write path
// already had a "##KEEP##" sentinel so a Settings save can preserve an unedited secret without the
// browser ever needing to see it (used by qbittorrent/radarr/sonarr/seerr below) — this masks every
// non-blank secret with that same sentinel on read, and PUT restores jellyfinToken/tmdbV3Key from it
// too now (the other four already did). A blank secret stays blank so the UI can still tell
// "not configured" apart from "configured, value withheld".
private fun maskSecrets(config: AppConfig): AppConfig {
    fun mask(s: String) = if (s.isBlank()) s else "##KEEP##"
    return config.copy(
        apiKeys = config.apiKeys.copy(
            jellyfinToken = mask(config.apiKeys.jellyfinToken),
            tmdbV3Key = mask(config.apiKeys.tmdbV3Key),
        ),
        qbittorrent = config.qbittorrent?.copy(password = mask(config.qbittorrent.password)),
        radarr = config.radarr?.copy(apiKey = mask(config.radarr.apiKey)),
        sonarr = config.sonarr?.copy(apiKey = mask(config.sonarr.apiKey)),
        seerr = config.seerr?.copy(apiKey = mask(config.seerr.apiKey)),
        bazarr = config.bazarr?.copy(apiKey = mask(config.bazarr.apiKey)),
        ingest = config.ingest.copy(webhookSecret = mask(config.ingest.webhookSecret)),
    )
}

fun Route.configureConfigRoutes(
    configStore: ConfigStore,
    effectiveScanThreads: Int,
    qbClient: QBittorrentClient? = null,
    arrClient: ArrClient? = null,
    seerrClient: SeerrClient? = null,
    bazarrClient: BazarrClient? = null,
    tmdbClient: dev.jellystructure.tmdb.TmdbClient? = null,
    requestLanguageService: dev.jellystructure.arr.RequestLanguageService? = null,
    // Phase 218 — the Chromecast card's reachability check (FR-218-5) and honest status (FR-218-7);
    // tvEventBus so a change to the chromecast block reaches every client's config snapshot (FR-218-3).
    castService: dev.jellystructure.tv.CastService? = null,
    tvEventBus: dev.jellystructure.tv.TvEventBus? = null,
    // Phase 221 — the Jellyfin webhook's own last delivery, for the deprecated-*arr finding's wording.
    realtimeIngest: dev.jellystructure.media.RealtimeIngestService? = null,
) {
    // Phase 221 (FR-221-2) — a server-side test delivery: status code or connect error, elapsed ms.
    // Recorded like any delivery, so the status line under the field updates at once.
    post("/config/test-webhook") {
        val req = runCatching { call.receive<WebhookTestRequest>() }.getOrElse { WebhookTestRequest() }
        val url = req.url.trim()
        if (url.isBlank()) { call.respond(WebhookTestResult(false, null, 0, "No URL set")); return@post }
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        val result = runCatching {
            dev.jellystructure.OutboundHttp.withPermit {
                dev.jellystructure.OutboundHttp.client.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"event":"test","source":"jellystructure","note":"A test notification sent from Settings → Notifications"}""")
                }
            }
        }
        val elapsed = started.elapsedNow().inWholeMilliseconds
        val response = result.getOrNull()
        val ok = response != null && response.status.value in 200..299
        val detail = when {
            response == null -> result.exceptionOrNull()?.message?.substringBefore("CurlRequestData")?.trim()?.trimEnd(':', ' ') ?: "connection failed"
            ok -> "HTTP ${response.status.value}"
            else -> "HTTP ${response.status.value}"
        }
        dev.jellystructure.ops.WebhookStatus.recordDelivery(url, ok, if (ok) null else detail, elapsed)
        call.respond(WebhookTestResult(ok, response?.status?.value, elapsed, detail))
    }
    // Phase 221 (FR-221-2/3/4) — the standing status line and the findings (empty = silent).
    get("/config/webhook-status") {
        val url = configStore.current.behavior.notificationsWebhook.trim()
        // lastWebhookReceivedAt is epoch SECONDS; findings() takes ms (it was passed raw until 2026-09-25,
        // which dated a working Jellyfin webhook to January 1970).
        val since = realtimeIngest?.lastWebhookReceivedAt?.let { it * 1000 }
        call.respond(WebhookStatusResponse(
            configured = url.isNotBlank(),
            target = if (url.isNotBlank()) dev.jellystructure.ops.WebhookStatus.target(url) else null,
            findings = dev.jellystructure.ops.WebhookStatus.findings(url, since),
        ))
    }
    // Phase 218 (FR-218-5) — the BACKEND fetches its own /cast/ through the public address the admin
    // typed; never a verdict off local config or the browser's address bar. Two outcomes only.
    post("/config/chromecast/check") {
        val svc = castService ?: return@post call.respond(HttpStatusCode.NotFound)
        val req = runCatching { call.receive<ChromecastCheckRequest>() }.getOrElse { ChromecastCheckRequest() }
        call.respond(svc.checkReceiver(req.url))
    }
    // Phase 218 (FR-218-7) — `verified` is true only once a real cast has enrolled a receiver.
    get("/config/chromecast/status") {
        val svc = castService ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(svc.status(dev.jellystructure.tv.activePlaybackDevices()))
    }
    get("/config") {
        call.respond(ConfigResponse(maskSecrets(configStore.current), effectiveScanThreads))
    }

    // Phase 139 — Settings ▸ Download tools ▸ Request languages ▸ "Set up profiles". Preview shows what
    // would be created/updated (read-only *arr GETs); apply actually provisions, idempotently, and
    // persists the resulting profile/format ids back into config.requestLanguage.intents.
    get("/config/request-language/preview") {
        val svc = requestLanguageService
            ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "request-language service not available"))
        call.respond(svc.preview())
    }
    post("/config/request-language/provision") {
        val svc = requestLanguageService
            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "request-language service not available"))
        call.respond(svc.provision())
    }
    put("/config") {
        val received = call.receive<AppConfig>()
        val stored = configStore.current
        // Blank secrets arrive as the "##KEEP##" sentinel — preserve the stored value rather than wipe it.
        var config = received
        if (received.apiKeys.jellyfinToken == "##KEEP##") {
            config = config.copy(apiKeys = config.apiKeys.copy(jellyfinToken = stored.apiKeys.jellyfinToken))
        }
        if (received.apiKeys.tmdbV3Key == "##KEEP##") {
            config = config.copy(apiKeys = config.apiKeys.copy(tmdbV3Key = stored.apiKeys.tmdbV3Key))
        }
        if (received.qbittorrent?.password == "##KEEP##") {
            config = config.copy(qbittorrent = received.qbittorrent.copy(password = stored.qbittorrent?.password ?: ""))
        }
        if (received.radarr?.apiKey == "##KEEP##") {
            config = config.copy(radarr = received.radarr.copy(apiKey = stored.radarr?.apiKey ?: ""))
        }
        if (received.sonarr?.apiKey == "##KEEP##") {
            config = config.copy(sonarr = received.sonarr.copy(apiKey = stored.sonarr?.apiKey ?: ""))
        }
        if (received.seerr?.apiKey == "##KEEP##") {
            config = config.copy(seerr = received.seerr.copy(apiKey = stored.seerr?.apiKey ?: ""))
        }
        if (received.bazarr?.apiKey == "##KEEP##") {
            config = config.copy(bazarr = received.bazarr.copy(apiKey = stored.bazarr?.apiKey ?: ""))
        }
        // The frontend AppConfig model omits acquisition (config-file-only, not exposed in the Settings
        // UI). Preserve the stored value so a Settings save never wipes it.
        if (config.acquisition == null) config = config.copy(acquisition = stored.acquisition)
        // Trackers are managed on Metadata ▸ Trackers via their own CRUD endpoints, and ingest is
        // config-file-only — the Settings form (readForm) sends neither, so without this a Settings save
        // would wipe the operator's tracker registry and reset the ingest webhook secret/realtime flag.
        config = config.copy(trackers = stored.trackers, ingest = stored.ingest)
        // Bug fix (live report, 2026-09-04) — same class of bug: age_rating_map is managed on Metadata ▸
        // Age ratings via its own write-through POST /api/metadata/age-ratings, not on this form.
        // readForm() sends MetadataConfig(ageRatingCascade = ...) with ageRatingMap defaulting to empty,
        // so every plain Settings save (e.g. just reordering the cascade on this same page) was silently
        // wiping every mapped certification back to Unmapped — the map's contents never actually appeared
        // deleted from the library (item counts stayed put), just reset, which read as "the page doesn't
        // work" rather than an obvious data loss.
        config = config.copy(metadata = config.metadata.copy(ageRatingMap = stored.metadata.ageRatingMap))

        // Phase 227 (FR-227-1) — validated on WRITE only (never on load): an origin, https, no path. Stored
        // normalised; the retired nested key is never written back (FR-227-2).
        dev.jellystructure.model.PublicUrl.problem(config.publicUrl)?.let { reason ->
            call.respond(HttpStatusCode.BadRequest, mapOf("field" to "public_url", "error" to reason))
            return@put
        }
        @Suppress("DEPRECATION")
        config = config.copy(
            publicUrl = dev.jellystructure.model.PublicUrl.normalize(config.publicUrl),
            chromecast = config.chromecast?.copy(publicUrl = ""),
        )

        // Phase 166 (FR-166-4) — the save-path backstop: a blank schedule means "scheduling off" and is
        // always valid; anything else must parse, actually fire within the search horizon, and not fire
        // more often than every 15 minutes. Rejects with 422 rather than silently persisting a schedule
        // that leaves the scheduler idle with nothing but a repeating log warning (the bug this closes).
        if (config.scanSchedule.isNotBlank()) {
            val check = dev.jellystructure.cron.validateSchedule(config.scanSchedule, dev.jellystructure.nowEpochSec())
            if (check is dev.jellystructure.cron.ScheduleCheck.Invalid) {
                call.respond(HttpStatusCode.UnprocessableEntity, mapOf("field" to "scan_schedule", "error" to check.message))
                return@put
            }
        }

        // Bug fix (live report, 2026-08-14) — configStore.update()'s write-to-disk result is now
        // checked; a failed persist used to still respond 204, so a Settings "Saved ✓" could be a lie
        // (the ktoml age_rating_map decode bug produced exactly this — see ConfigStore.fixAgeRatingMapKeys).
        val ok = configStore.update(config)
        // Phase 218 (FR-218-3) — a changed chromecast block changes every client's resolved `cast`
        // capability, so push the same global-config event a layout save does; clients re-fetch /tv/config.
        if (ok && (config.chromecast != stored.chromecast || config.publicUrl != stored.publicUrl)) tvEventBus?.notifyGlobalConfigChanged()
        if (ok) call.respond(HttpStatusCode.NoContent)
        else call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Couldn't save — check the server log"))
    }

    // Phase 166 (FR-166-5) — live validation + next-5-runs preview for the Settings Custom cron field,
    // computed by the exact same dev.jellystructure.cron code the save path (above) and the scheduler
    // (Main.kt) use, so the frontend can never disagree with the server about what a schedule means or
    // whether it's acceptable. Cookie-gated like every other /api/config route (not open).
    post("/config/validate-schedule") {
        val body = runCatching { call.receive<ScheduleCheckRequest>() }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest)
        if (body.cron.isBlank()) {
            call.respond(ScheduleCheckResponse(valid = true, description = "Scheduling is off."))
            return@post
        }
        when (val check = dev.jellystructure.cron.validateSchedule(body.cron, dev.jellystructure.nowEpochSec())) {
            is dev.jellystructure.cron.ScheduleCheck.Ok ->
                call.respond(ScheduleCheckResponse(valid = true, description = check.description, nextRuns = check.nextRuns))
            is dev.jellystructure.cron.ScheduleCheck.Invalid ->
                call.respond(ScheduleCheckResponse(valid = false, error = check.message))
        }
    }
    post("/config/test-qbittorrent") {
        if (qbClient == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, TestQBittorrentResult(false, "qBittorrent client not available"))
            return@post
        }
        val req = call.receive<TestQBittorrentRequest>()
        val tmpConfig = QBittorrentConfig(url = req.url, username = req.username, password = req.password, enabled = true)
        val result = runCatching {
            val sid = qbClient.login(tmpConfig)
            val torrents = qbClient.getTorrents(tmpConfig, sid)
            TestQBittorrentResult(true, "Connected successfully", torrents.size)
        }.getOrElse { e ->
            TestQBittorrentResult(false, e.message ?: "Unknown error")
        }
        call.respond(result)
    }
    // Phase 54 — Radarr/Sonarr connection test (temporary creds, never persisted). The v3 API is
    // identical, so both endpoints share one handler.
    suspend fun testArr(req: TestArrRequest): ArrTestResult {
        if (arrClient == null) return ArrTestResult(false, "*arr client not available")
        if (req.url.isBlank()) return ArrTestResult(false, "URL not configured")
        val ping = arrClient.ping(req.url, req.apiKey)
        if (!ping.ok) return ArrTestResult(false, ping.detail)
        val roots = runCatching { arrClient.rootFolders(req.url, req.apiKey) }.getOrDefault(emptyList())
        return ArrTestResult(true, "Connected" + (ping.version?.let { " · v$it" } ?: ""), ping.version, roots)
    }
    post("/config/test-radarr") { call.respond(testArr(call.receive())) }
    post("/config/test-sonarr") { call.respond(testArr(call.receive())) }
    // Phase 136 — Jellyseerr/Overseerr connection test (temporary creds, never persisted). Reuses
    // TestArrRequest/ArrTestResult (identical url+apiKey shape) rather than a redundant pair of DTOs.
    post("/config/test-seerr") {
        val req = call.receive<TestArrRequest>()
        if (seerrClient == null) { call.respond(ArrTestResult(false, "Seerr client not available")); return@post }
        if (req.url.isBlank()) { call.respond(ArrTestResult(false, "URL not configured")); return@post }
        val ping = seerrClient.ping(req.url, req.apiKey)
        call.respond(ArrTestResult(ping.ok, if (ping.ok) "Connected" + (ping.version?.let { " · v$it" } ?: "") else ping.detail, ping.version))
    }
    // Phase 157 — Bazarr connection test (temporary creds, never persisted). Reuses TestArrRequest/
    // ArrTestResult, same as Seerr's test route.
    post("/config/test-bazarr") {
        val req = call.receive<TestArrRequest>()
        if (bazarrClient == null) { call.respond(ArrTestResult(false, "Bazarr client not available")); return@post }
        if (req.url.isBlank()) { call.respond(ArrTestResult(false, "URL not configured")); return@post }
        val ping = bazarrClient.ping(req.url, req.apiKey)
        call.respond(ArrTestResult(ping.ok, if (ping.ok) "Connected" + (ping.version?.let { " · v$it" } ?: "") else ping.detail, ping.version))
    }
    // Import root folders → pre-fill [[libraries]] local paths (uses the stored, saved creds).
    get("/config/radarr/root-folders") {
        val r = configStore.current.radarr
        if (arrClient == null || r == null || r.url.isBlank()) { call.respond(emptyList<String>()); return@get }
        call.respond(runCatching { arrClient.rootFolders(r.url, r.apiKey) }.getOrDefault(emptyList()))
    }
    get("/config/sonarr/root-folders") {
        val s = configStore.current.sonarr
        if (arrClient == null || s == null || s.url.isBlank()) { call.respond(emptyList<String>()); return@get }
        call.respond(runCatching { arrClient.rootFolders(s.url, s.apiKey) }.getOrDefault(emptyList()))
    }
    get("/config/path-check") {
        val diags = configStore.current.libraries.filter { !it.skip }.map { lib ->
            val prefix = lib.jellyfinPath.ifBlank { lib.localPath }
            LibraryPathDiag(
                name = lib.name,
                jellyfinPath = lib.jellyfinPath,
                localPath = lib.localPath,
                matchPrefix = prefix,
                localExists = lib.localPath.isNotBlank() && SystemFileSystem.exists(Path(lib.localPath)),
            )
        }
        call.respond(diags)
    }

    // Phase 138 — Request tab add-row pickers (genre/studio/network dropdowns instead of raw TMDB ids).
    get("/config/seerr/genres") {
        call.respond(dev.jellystructure.seerr.seerrGenreOptions(call.request.queryParameters["kind"] ?: "movie"))
    }
    get("/config/seerr/studios") {
        val q = call.request.queryParameters["q"]?.trim().orEmpty()
        if (q.isBlank()) { call.respond(dev.jellystructure.seerr.CURATED_STUDIOS); return@get }
        val results = tmdbClient?.let { runCatching { it.searchCompanies(q) }.getOrDefault(emptyList()) }.orEmpty()
        call.respond(results.map { dev.jellystructure.shared.tv.PickerOption(it.id, it.name, it.logoPath) })
    }
    get("/config/seerr/networks") {
        // No live catalogue search for networks — TMDB has no /search/network endpoint; the operator
        // types into a fixed curated list, filtered client-side.
        call.respond(dev.jellystructure.seerr.CURATED_NETWORKS)
    }
}

/** Phase 218 (FR-218-5) — the public address the admin typed; the backend fetches `<url>/cast/` itself. */
@Serializable
data class ChromecastCheckRequest(val url: String = "")

// Phase 221 — DTOs for the webhook test + status routes.
@Serializable
data class WebhookTestRequest(val url: String = "")

@Serializable
data class WebhookTestResult(val ok: Boolean, val status: Int?, @kotlinx.serialization.SerialName("elapsed_ms") val elapsedMs: Long, val detail: String)

@Serializable
data class WebhookStatusResponse(
    val configured: Boolean,
    val target: dev.jellystructure.ops.WebhookTargetStatus?,
    val findings: List<dev.jellystructure.ops.OperatorFinding>,
)
