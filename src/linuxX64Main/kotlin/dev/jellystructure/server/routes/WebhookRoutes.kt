package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.log.Logger
import dev.jellystructure.media.RealtimeIngestService
import dev.jellystructure.tv.JellyfinLibraryListener
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class IngestStatus(
    @SerialName("webhook_secret") val webhookSecret: String,
    val realtime: Boolean,
    @SerialName("listener_connected") val listenerConnected: Boolean,
    @SerialName("last_event_at") val lastEventAt: Long?,
)

/**
 * Phase 114 (FR A) — inbound *arr webhooks: the earliest signal that a new/upgraded file has landed,
 * well before Jellyfin's own monitor would notice it (especially on network mounts, where Jellyfin's
 * realtime monitor is unreliable — that's the whole reason this phase exists). Open routes,
 * authenticated by a per-install secret query param rather than AuthPlugin's cookie/token/API-key
 * model, since *arr's webhook sender can't do any of those.
 */
fun Route.webhookRoutes(
    configStore: ConfigStore,
    jellyfinClient: JellyfinClient,
    realtimeIngest: RealtimeIngestService,
    appScope: CoroutineScope,
    libraryListener: JellyfinLibraryListener? = null,
) {
    post("/webhooks/sonarr") { handleArrWebhook(call, configStore, jellyfinClient, realtimeIngest, appScope, isSonarr = true) }
    post("/webhooks/radarr") { handleArrWebhook(call, configStore, jellyfinClient, realtimeIngest, appScope, isSonarr = false) }

    // Cookie-gated (falls through AuthPlugin's default /api/** branch — not under /api/webhooks/**,
    // which is open) — Settings ▸ Download tools reads this to show the webhook URLs + listener status.
    get("/settings/ingest-status") {
        val cfg = configStore.current
        call.respond(IngestStatus(cfg.ingest.webhookSecret, cfg.ingest.realtime, libraryListener?.connected ?: false, libraryListener?.lastEventAt))
    }
}

private suspend fun handleArrWebhook(
    call: io.ktor.server.application.ApplicationCall,
    configStore: ConfigStore,
    jellyfinClient: JellyfinClient,
    realtimeIngest: RealtimeIngestService,
    appScope: CoroutineScope,
    isSonarr: Boolean,
) {
    val secret = configStore.current.ingest.webhookSecret
    val provided = call.request.queryParameters["secret"]
    if (secret.isBlank() || provided != secret) {
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
    if (eventType != "Download" || json == null) {
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

    call.respond(HttpStatusCode.OK, mapOf("ok" to true)) // ack immediately; ingest happens in the background
    appScope.launch { nudgeAndIngest(filePath, configStore, jellyfinClient, realtimeIngest) }
}

/** FR A.2/A.3 — map the *arr-reported host path to the Jellyfin-visible path via the same
 *  jellyfin_path/local_path mapping used everywhere else, nudge Jellyfin so it notices promptly even
 *  on a network mount, then poll briefly for the item to actually exist before ingesting it. */
private suspend fun nudgeAndIngest(
    arrPath: String,
    configStore: ConfigStore,
    jellyfinClient: JellyfinClient,
    realtimeIngest: RealtimeIngestService,
) {
    val cfg = configStore.current
    val baseUrl = cfg.apiKeys.jellyfinUrl
    val token = cfg.apiKeys.jellyfinToken
    if (baseUrl.isBlank() || token.isBlank()) return

    val lib = cfg.libraries.firstOrNull { lib -> !lib.skip && lib.localPath.isNotBlank() && arrPath.startsWith(lib.localPath) }
    val jellyfinPath = mapToJellyfinPath(arrPath, lib)
    jellyfinClient.notifyLibraryMediaUpdated(baseUrl, token, jellyfinPath)

    // Bounded fallback poll (~5 min per spec FR A.3) — Jellyfin's own monitor settle delay is ~60s even
    // after the nudge above; poll for the item to actually exist rather than guessing a fixed wait.
    val deadline = dev.jellystructure.nowEpochSec() + 5 * 60
    var found: dev.jellystructure.auth.JellyfinItem? = null
    while (dev.jellystructure.nowEpochSec() < deadline) {
        kotlinx.coroutines.delay(10_000L)
        found = jellyfinClient.getItemByPath(baseUrl, token, jellyfinPath)
        if (found != null) break
    }
    if (found == null) {
        Logger.warn("Webhook: Jellyfin never picked up '$jellyfinPath' within 5 min — leaving for the next scheduled scan", "ingest")
        return
    }
    realtimeIngest.enqueue(found.id)
}

private fun mapToJellyfinPath(arrPath: String, lib: LibraryMapping?): String {
    if (lib == null || lib.jellyfinPath.isBlank()) return arrPath
    return arrPath.replaceFirst(lib.localPath, lib.jellyfinPath)
}
