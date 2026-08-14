package dev.jellystructure.server.routes

import dev.jellystructure.OutboundHttp
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.constantTimeEquals
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.log.Logger
import dev.jellystructure.media.RealtimeIngestService
import dev.jellystructure.tv.JellyfinLibraryListener
import io.ktor.client.request.header
import io.ktor.client.request.post as httpPost
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Phase 165 — the Jellyfin Webhook plugin's catalog identity (verified live against this deployment's
// Jellyfin 10.11.11: GET /Packages lists it under the default repository, GUID below, category
// "Administration", latest version 21.0.0.0 targeting ABI 10.11.8.0 — compatible).
private const val WEBHOOK_PLUGIN_GUID = "71552a5a5c5c4350a2aeebe451a30173"
private const val WEBHOOK_PLUGIN_NAME = "Webhook"
private const val OUR_DESTINATION_NAME = "jellystructure"

@Serializable
data class JellyfinWebhookStatus(
    val reachable: Boolean,
    @SerialName("plugin_installed") val pluginInstalled: Boolean = false,
    @SerialName("plugin_version") val pluginVersion: String? = null,
    @SerialName("plugin_available_version") val pluginAvailableVersion: String? = null,
    @SerialName("destination_configured") val destinationConfigured: Boolean = false,
    @SerialName("destination_url") val destinationUrl: String? = null,
    @SerialName("restart_pending") val restartPending: Boolean = false,
)

// Bug fix (live report, 2026-08-14) — /settings/ingest/setup-jellyfin used to call.respond(mapOf(...))
// with mixed Boolean+String values in one map on both its success paths. A bare Map<String, Any> has no
// single, uniform value type kotlinx.serialization can infer a serializer for, so BOTH calls crashed
// with "Serializing collections of different element types is not yet supported" — a 500 that fired
// AFTER the Jellyfin-side write had already succeeded (confirmed live: the destination was correctly
// configured in Jellyfin's own plugin config despite the browser seeing "internal server error"). A
// real typed DTO, matching every other response in this codebase, can't have this bug class at all.
@Serializable
data class JellyfinSetupResult(
    val installed: Boolean = false,
    val restartNeeded: Boolean = false,
    val configured: Boolean = false,
    val destinationUrl: String? = null,
)

@Serializable
private data class ReachUrlRequest(val url: String)

@Serializable
data class IngestStatus(
    @SerialName("webhook_secret") val webhookSecret: String,
    val realtime: Boolean,
    @SerialName("listener_connected") val listenerConnected: Boolean,
    @SerialName("last_event_at") val lastEventAt: Long?,
    // Phase 165
    @SerialName("jellyfin_reach_url") val jellyfinReachUrl: String = "",
    val jellyfin: JellyfinWebhookStatus? = null,
)

/**
 * Phase 114 (FR A) → Phase 165 — inbound webhooks that trigger realtime ingest. `/webhooks/jellyfin`
 * (Phase 165, FR-165-1) is now the PRIMARY path: the Jellyfin Webhook plugin's `ItemAdded` fires only
 * once Jellyfin has actually identified the item (its own `ItemAddedManager` holds the item until
 * `ProviderIds` is populated), so unlike the two paths below it needs neither a path→id mapping nor a
 * settle-time poll — the id it hands over is already resolvable. `/webhooks/{sonarr,radarr}` (Phase 114
 * FR A) are DEPRECATED as of Phase 165 (FR-165-6) but stay routed — existing installs already have
 * these URLs pasted into their *arr instances, and 404ing them would turn a working-ish path into a
 * broken one at upgrade time; they now only nudge Jellyfin's own monitor and let the Jellyfin webhook
 * (or, failing that, [JellyfinLibraryListener]'s change-feed) deliver the actual ingest, rather than
 * running their own 5-minute path-polling loop. Open routes, authenticated by a per-install secret
 * query param rather than AuthPlugin's cookie/token/API-key model, since none of these senders can
 * present any of those.
 */
fun Route.webhookRoutes(
    configStore: ConfigStore,
    jellyfinClient: JellyfinClient,
    realtimeIngest: RealtimeIngestService,
    appScope: CoroutineScope,
    libraryListener: JellyfinLibraryListener? = null,
) {
    post("/webhooks/jellyfin") { handleJellyfinWebhook(call, configStore, realtimeIngest) }
    post("/webhooks/sonarr") { handleArrWebhook(call, configStore, jellyfinClient, appScope, isSonarr = true) }
    post("/webhooks/radarr") { handleArrWebhook(call, configStore, jellyfinClient, appScope, isSonarr = false) }

    // Cookie-gated (falls through AuthPlugin's default /api/** branch — not under /api/webhooks/**,
    // which is open) — Settings ▸ Download tools reads this to show webhook status.
    get("/settings/ingest-status") {
        val cfg = configStore.current
        val jellyfinStatus = computeJellyfinWebhookStatus(cfg, jellyfinClient)
        call.respond(IngestStatus(
            cfg.ingest.webhookSecret, cfg.ingest.realtime, libraryListener?.connected ?: false, libraryListener?.lastEventAt,
            cfg.ingest.jellyfinReachUrl, jellyfinStatus,
        ))
    }

    post("/settings/ingest/reach-url") {
        val body = runCatching { call.receive<ReachUrlRequest>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
        val ok = configStore.update(configStore.current.copy(ingest = configStore.current.ingest.copy(jellyfinReachUrl = body.url.trim())))
        if (ok) call.respond(HttpStatusCode.NoContent)
        else call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Couldn't save — check the server log"))
    }

    // Phase 165 (FR-165-3) — the one-click "Set up Jellyfin webhook" action: installs the plugin if
    // missing, or (once installed) read-modify-writes our Generic destination into its configuration.
    // Never restarts Jellyfin itself (see /settings/ingest/restart-jellyfin below) — that's its own,
    // separately-confirmed, explicitly-labelled action per the spec (a restart drops every household
    // stream). On ANY failure the caller falls back to the manual-instructions card; nothing here is
    // trusted blindly, since the plugin's exact configuration JSON shape was not verified live against
    // an actual install while this was written (see JellyfinClient.getPluginConfiguration's own doc).
    post("/settings/ingest/setup-jellyfin") {
        val cfg = configStore.current
        val baseUrl = cfg.apiKeys.jellyfinUrl
        val token = cfg.apiKeys.jellyfinToken
        if (baseUrl.isBlank() || token.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Connect Jellyfin first (Settings ▸ Connections)"))
        }
        if (cfg.ingest.webhookSecret.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No webhook secret configured — restart jellystructure once to generate one"))
        }
        val reachUrl = cfg.ingest.jellyfinReachUrl
        if (reachUrl.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Set \"where Jellyfin should reach this install\" first"))
        }

        val plugins = jellyfinClient.getPlugins(baseUrl, token)
            ?: return@post call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Couldn't reach Jellyfin"))
        val installed = plugins.firstOrNull { it.name.equals(WEBHOOK_PLUGIN_NAME, ignoreCase = true) }

        if (installed == null) {
            val available = jellyfinClient.getAvailablePackages(baseUrl, token)
                ?.firstOrNull { it.guid?.equals(WEBHOOK_PLUGIN_GUID, ignoreCase = true) == true }
                ?: return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "The Webhook plugin isn't offered by this server's repositories"))
            val installOk = jellyfinClient.installPlugin(baseUrl, token, WEBHOOK_PLUGIN_NAME, WEBHOOK_PLUGIN_GUID, available.versions.firstOrNull()?.version)
            if (!installOk) return@post call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Plugin install failed"))
            Logger.info("Jellyfin Webhook plugin installed — Jellyfin must restart before it loads", "ingest")
            return@post call.respond(JellyfinSetupResult(installed = true, restartNeeded = true))
        }

        val pluginId = installed.id
            ?: return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "Plugin has no id yet — try again after Jellyfin restarts"))
        val config = jellyfinClient.getPluginConfiguration(baseUrl, token, pluginId)
            ?: return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "Couldn't read the plugin's configuration — set it up manually"))

        val destinationUrl = "${reachUrl.trimEnd('/')}/api/webhooks/jellyfin?secret=${cfg.ingest.webhookSecret}"
        val ourEntry = buildJsonObject {
            put("WebhookName", OUR_DESTINATION_NAME)
            put("WebhookUri", destinationUrl)
            put("NotificationTypes", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("ItemAdded")) })
            put("EnableMovies", true); put("EnableEpisodes", true); put("EnableSeries", true)
            put("EnableSeasons", false); put("EnableAlbums", false); put("EnableSongs", false); put("EnableVideos", false)
            put("Template", """{"ItemId":"{{ItemId}}","ItemType":"{{ItemType}}"}""")
            put("SendAllProperties", false)
            put("EnableWebhook", true)
            put("UserFilter", buildJsonArray { })
            put("Headers", buildJsonArray { })
            put("Fields", buildJsonArray { })
            put("TrimWhitespace", true)
            put("SkipEmptyMessageBody", false)
        }
        // Read-modify-write: keep every OTHER destination (Discord, Slack, an admin's own Generic
        // entries) untouched; only add-or-replace the ONE entry named "jellystructure" (matched by
        // WebhookName, so re-running this action is idempotent and never duplicates).
        val existingGeneric = (config["GenericOptions"] as? JsonArray) ?: JsonArray(emptyList())
        val keptOthers = existingGeneric.filterNot {
            (it as? JsonObject)?.get("WebhookName")?.jsonPrimitive?.contentOrNull == OUR_DESTINATION_NAME
        }
        val updatedConfig = JsonObject(config.toMutableMap().apply {
            put("GenericOptions", JsonArray(keptOthers + ourEntry))
        })

        val writeOk = jellyfinClient.updatePluginConfiguration(baseUrl, token, pluginId, updatedConfig)
        if (writeOk) call.respond(JellyfinSetupResult(configured = true, destinationUrl = destinationUrl))
        else call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Couldn't write the plugin configuration — set it up manually"))
    }

    // Bug fix (live report, 2026-08-14) — this used to run client-side (the browser POSTing straight to
    // the absolute destinationUrl), which is only ever same-origin by coincidence and was blocked by
    // this server's own CORS policy the moment it wasn't (Settings served via a separately-hosted dev
    // proxy port, or a reach URL that differs from whatever origin the admin actually browsed from) —
    // "Couldn't reach that URL" every time, regardless of whether the URL was actually fine. CORS here
    // is deliberately locked to same-origin (2026-08-02 security review, finding M1 — credential
    // exposure via a permissive cross-origin policy); loosening it just to make this button work would
    // reopen exactly that hole. Running the test server-side sidesteps CORS entirely (it's a browser-
    // only concept) and the frontend now calls this same-origin route instead of the destination URL
    // directly.
    post("/settings/ingest/test-destination") {
        val cfg = configStore.current
        val url = "${cfg.ingest.jellyfinReachUrl.trimEnd('/')}/api/webhooks/jellyfin?secret=${cfg.ingest.webhookSecret}"
        if (cfg.ingest.jellyfinReachUrl.isBlank() || cfg.ingest.webhookSecret.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Set the reach URL first"))
        }
        val ok = runCatching {
            OutboundHttp.withPermit {
                OutboundHttp.client.httpPost(url) {
                    header("Content-Type", "application/json")
                    setBody("""{"ItemId":"test","ItemType":"Movie"}""")
                }.status.isSuccess()
            }
        }.getOrElse { Logger.warn("Ingest test-destination failed: ${it.message}", "ingest"); false }
        if (ok) call.respond(mapOf("ok" to true)) else call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Couldn't reach that URL from this server"))
    }

    // Explicit, separately-confirmed, destructive-labelled — restarting Jellyfin drops every household
    // stream. Never triggered automatically by the setup action above.
    post("/settings/ingest/restart-jellyfin") {
        val cfg = configStore.current
        val ok = jellyfinClient.restartServer(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken)
        if (ok) call.respond(mapOf("ok" to true)) else call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Restart request failed"))
    }
}

/** Phase 165 (FR-165-2) — the Settings card's live plugin-status computation. Every field degrades to a
 *  safe "unknown"/false rather than throwing — a Jellyfin hiccup on this read-only status check must
 *  never break the rest of the Settings page. */
private suspend fun computeJellyfinWebhookStatus(cfg: AppConfig, jellyfinClient: JellyfinClient): JellyfinWebhookStatus {
    val baseUrl = cfg.apiKeys.jellyfinUrl
    val token = cfg.apiKeys.jellyfinToken
    if (baseUrl.isBlank() || token.isBlank()) return JellyfinWebhookStatus(reachable = false)

    val plugins = jellyfinClient.getPlugins(baseUrl, token) ?: return JellyfinWebhookStatus(reachable = false)
    val installed = plugins.firstOrNull { it.name.equals(WEBHOOK_PLUGIN_NAME, ignoreCase = true) }
    val availableVersion = jellyfinClient.getAvailablePackages(baseUrl, token)
        ?.firstOrNull { it.guid?.equals(WEBHOOK_PLUGIN_GUID, ignoreCase = true) == true }
        ?.versions?.firstOrNull()?.version

    if (installed == null) {
        return JellyfinWebhookStatus(reachable = true, pluginInstalled = false, pluginAvailableVersion = availableVersion)
    }

    val pluginId = installed.id
    val destinationUrl = pluginId?.let { id ->
        val config = jellyfinClient.getPluginConfiguration(baseUrl, token, id)
        val generic = config?.get("GenericOptions") as? JsonArray
        generic?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it["WebhookName"]?.jsonPrimitive?.contentOrNull == OUR_DESTINATION_NAME }
            ?.get("WebhookUri")?.jsonPrimitive?.contentOrNull
    }

    return JellyfinWebhookStatus(
        reachable = true, pluginInstalled = true, pluginVersion = installed.version,
        pluginAvailableVersion = availableVersion, destinationConfigured = destinationUrl != null,
        destinationUrl = destinationUrl, restartPending = installed.status == "Restart",
    )
}

/** Phase 165 (FR-165-1) — id-based, no path mapping, no polling: the Jellyfin Webhook plugin's
 *  `ItemAdded` only fires once Jellyfin has finished identifying the item, so the id it hands over is
 *  already resolvable the instant this arrives. Reads defensively (never binds a strict DTO to a
 *  third-party sender) and accepts both this phase's own minimal Template body and the plugin's
 *  SendAllProperties shape — both carry `ItemId`/`ItemType` alongside whatever else is in the body. */
private suspend fun handleJellyfinWebhook(
    call: io.ktor.server.application.ApplicationCall,
    configStore: ConfigStore,
    realtimeIngest: RealtimeIngestService,
) {
    val secret = configStore.current.ingest.webhookSecret
    val provided = call.request.queryParameters["secret"]
    if (secret.isBlank() || provided == null || !constantTimeEquals(provided, secret)) {
        Logger.warn("Webhook rejected: bad secret (jellyfin)", "ingest")
        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "invalid secret"))
        return
    }

    val raw = runCatching { call.receiveText() }.getOrNull()
    val json = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
    val itemId = json?.get("ItemId")?.jsonPrimitive?.contentOrNull
    val itemType = json?.get("ItemType")?.jsonPrimitive?.contentOrNull

    // Ack immediately regardless — an unacknowledged webhook makes the plugin retry, and an unsupported
    // item type (Season/Album/Song/Video — never enabled on our destination anyway) is not an error.
    call.respond(HttpStatusCode.OK, mapOf("ok" to true))

    if (itemId.isNullOrBlank()) {
        Logger.warn("Webhook: jellyfin ItemAdded with no ItemId", "ingest")
        return
    }
    if (itemType != null && itemType !in setOf("Movie", "Series", "Episode")) return
    realtimeIngest.enqueue(itemId)
}

private suspend fun handleArrWebhook(
    call: io.ktor.server.application.ApplicationCall,
    configStore: ConfigStore,
    jellyfinClient: JellyfinClient,
    appScope: CoroutineScope,
    isSonarr: Boolean,
) {
    val secret = configStore.current.ingest.webhookSecret
    val provided = call.request.queryParameters["secret"]
    // Security fix (L5) — constant-time compare (see constantTimeEquals's doc comment).
    if (secret.isBlank() || provided == null || !constantTimeEquals(provided, secret)) {
        Logger.warn("Webhook rejected: bad secret (${if (isSonarr) "sonarr" else "radarr"})", "ingest")
        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "invalid secret"))
        return
    }

    val raw = runCatching { call.receiveText() }.getOrNull()
    val json = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
    val eventType = json?.get("eventType")?.jsonPrimitive?.contentOrNull

    if (eventType == "Test") {
        call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        return
    }
    // `json == null` first: it's a live check here (receiveText/parse can fail). If it were second,
    // reaching it would require eventType == "Download", which — being derived from json?.get(...) —
    // already implies json != null, making the null check dead (the "always false" warning).
    if (json == null || eventType != "Download") {
        call.respond(HttpStatusCode.OK, mapOf("ignored" to true)) // acknowledge, don't 4xx unknown events
        return
    }

    // Sonarr: Data.episodeFile.path (or .relativePath under series.path). Radarr: Data.movieFile.path.
    // *arr's own docs are inconsistent about which fields are always present across versions, so this
    // reads defensively rather than binding to a strict DTO.
    val filePath = (json["episodeFile"] as? JsonObject)?.get("path")?.jsonPrimitive?.contentOrNull
        ?: (json["movieFile"] as? JsonObject)?.get("path")?.jsonPrimitive?.contentOrNull
        ?: (json["episodeFiles"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
            ?.let { (it as? JsonObject)?.get("path")?.jsonPrimitive?.contentOrNull }

    if (filePath.isNullOrBlank()) {
        Logger.warn("Webhook: Download event with no file path (${if (isSonarr) "sonarr" else "radarr"})", "ingest")
        call.respond(HttpStatusCode.OK, mapOf("ignored" to true))
        return
    }

    call.respond(HttpStatusCode.OK, mapOf("ok" to true)) // ack immediately; the nudge happens in the background
    // Phase 165 (FR-165-6) — DEPRECATED: this used to poll Jellyfin for up to 5 minutes waiting for the
    // item to appear, then ingest it directly. That poll loop is gone — this now only nudges Jellyfin's
    // own monitor to notice the file sooner; the actual ingest is delivered by the Jellyfin webhook
    // (once configured, Phase 165) or, failing that, JellyfinLibraryListener's change-feed. Both already
    // exist independently of this nudge, so removing the poll loses nothing but the redundant 5-minute
    // busy-wait this route used to do on every single import.
    Logger.warn("Webhook: ${if (isSonarr) "sonarr" else "radarr"} webhook is deprecated (Phase 165) — set up the Jellyfin webhook in Settings instead", "ingest")
    appScope.launch { nudgeJellyfin(filePath, configStore, jellyfinClient) }
}

/** FR A.2 — map the *arr-reported host path to the Jellyfin-visible path via the same jellyfin_path/
 *  local_path mapping used everywhere else, and nudge Jellyfin so it notices sooner even on a network
 *  mount. No longer polls/ingests directly — see the deprecation note at the call site above. */
private suspend fun nudgeJellyfin(arrPath: String, configStore: ConfigStore, jellyfinClient: JellyfinClient) {
    val cfg = configStore.current
    val baseUrl = cfg.apiKeys.jellyfinUrl
    val token = cfg.apiKeys.jellyfinToken
    if (baseUrl.isBlank() || token.isBlank()) return
    val lib = cfg.libraries.firstOrNull { lib -> !lib.skip && lib.localPath.isNotBlank() && arrPath.startsWith(lib.localPath) }
    jellyfinClient.notifyLibraryMediaUpdated(baseUrl, token, mapToJellyfinPath(arrPath, lib))
}

private fun mapToJellyfinPath(arrPath: String, lib: LibraryMapping?): String {
    if (lib == null || lib.jellyfinPath.isBlank()) return arrPath
    return arrPath.replaceFirst(lib.localPath, lib.jellyfinPath)
}
