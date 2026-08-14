package dev.jellystructure.server.routes

import dev.jellystructure.arr.ArrClient
import dev.jellystructure.bazarr.BazarrClient
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.QBittorrentConfig
import dev.jellystructure.seerr.SeerrClient
import dev.jellystructure.torrent.QBittorrentClient
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
) {
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

        configStore.update(config)
        call.respond(HttpStatusCode.NoContent)
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
