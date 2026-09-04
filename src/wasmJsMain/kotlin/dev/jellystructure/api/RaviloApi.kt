package dev.jellystructure.api

import dev.jellystructure.shared.tv.ChannelLogo
import dev.jellystructure.shared.tv.ChannelLogoUpload
import dev.jellystructure.shared.tv.PickerOption
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.ResolvedBehaviour
import dev.jellystructure.shared.tv.ViewerSettingsRequest
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The Ravilo layout DTOs are defined once in `:shared` (`dev.jellystructure.shared.tv`) and reused by
// the backend, the TV client, and this admin frontend (Constitution Invariant 2). This file only
// adds the admin-only transport types that are not part of the shared layout model.

@Serializable
data class JellyfinUser(
    val id: String,
    @SerialName("display_name") val displayName: String,
)

/** R51 — GET /tv/admin/config response envelope. */
@Serializable
data class AdminConfigResponse(
    val config: RaviloConfig,
    @SerialName("hasOverride") val hasOverride: Boolean = false,
    @SerialName("isGlobal") val isGlobal: Boolean = false,
)

// Phase 143 — GET /tv/admin/overview response shapes (Users & devices Settings tab).
@Serializable
data class OverviewDevice(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val connected: Boolean,
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("is_kids") val isKids: Boolean,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_seen") val lastSeen: Long,
    @SerialName("now_playing") val nowPlaying: String? = null,
    // Phase 177 §FR-177-5 — this device's most recent playback-quality report, if any.
    @SerialName("recent_quality") val recentQuality: QoeSummary? = null,
    // Phase 185 (FR-185-8) — this device's persisted decode ceiling (bps). Both null ⇒ "not measured
    // yet" (no Ravilo session since the R216 build).
    @SerialName("decode_max_bitrate_hevc") val decodeMaxBitrateHevc: Long? = null,
    @SerialName("decode_max_bitrate_h264") val decodeMaxBitrateH264: Long? = null,
    @SerialName("decode_measured_at") val decodeMeasuredAt: Long? = null,
)

/** Phase 177 §FR-177-5 — mirrors the backend's `dev.jellystructure.tv.QoeSummary`. [hasIssue] matches
 *  the backend's own field-for-field so a clean session is never badged, same rule both places. */
@Serializable
data class QoeSummary(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("jellyfin_id") val jellyfinId: String,
    @SerialName("play_session_id") val playSessionId: String,
    @SerialName("dropped_frames") val droppedFrames: Int,
    @SerialName("rebuffer_count") val rebufferCount: Int,
    @SerialName("rebuffer_ms") val rebufferMs: Long,
    @SerialName("bandwidth_estimate_bps") val bandwidthEstimateBps: Long? = null,
    @SerialName("video_decoder") val videoDecoder: String? = null,
    @SerialName("direct_play") val directPlay: Boolean = false,
    @SerialName("link_kind") val linkKind: String = "unknown",
    @SerialName("link_mbps") val linkMbps: Int = 0,
    // Phase 179 (FR-179-3).
    @SerialName("subtitle_load_errors") val subtitleLoadErrors: Int = 0,
    @SerialName("updated_at") val updatedAt: Long,
) {
    val hasIssue: Boolean get() = rebufferCount > 0 || droppedFrames > 0 || subtitleLoadErrors > 0
}

/** Phase 177 §FR-177-5 — mirrors the backend's `QoeActivityRow` (device/title already resolved). */
@Serializable
data class QoeActivityRow(
    @SerialName("device_name") val deviceName: String,
    val title: String,
    @SerialName("dropped_frames") val droppedFrames: Int,
    @SerialName("rebuffer_count") val rebufferCount: Int,
    @SerialName("rebuffer_ms") val rebufferMs: Long,
    @SerialName("direct_play") val directPlay: Boolean,
    @SerialName("link_kind") val linkKind: String,
    @SerialName("link_mbps") val linkMbps: Int,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class OverviewSession(
    val id: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_used_at") val lastUsedAt: Long,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("is_current") val isCurrent: Boolean,
)

@Serializable
data class OverviewPolicy(
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("all_folders") val allFolders: Boolean,
    @SerialName("library_count") val libraryCount: Int? = null,
    @SerialName("total_libraries") val totalLibraries: Int = 0,
    @SerialName("allowed_tags") val allowedTags: List<String> = emptyList(),
    @SerialName("blocked_tags") val blockedTags: List<String> = emptyList(),
    @SerialName("max_rating") val maxRating: Int? = null,
)

@Serializable
data class OverviewUser(
    @SerialName("user_id") val userId: String,
    val username: String,
    val policy: OverviewPolicy,
    val devices: List<OverviewDevice>,
    val sessions: List<OverviewSession>,
    // Phase 187 (FR-187-9) — read-only; the admin gets no ability to set/clear another user's photo.
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

// Phase 143 (design addendum) — "Recently watched" lazy history. Timestamps are epoch **seconds**
// (Jellyfin ISO DatePlayed), unlike the millis-based timestamps above — see formatHistoryTs in Settings.kt.
@Serializable
data class WatchHistoryEntry(
    val title: String,
    @SerialName("episode_label") val episodeLabel: String? = null,
    @SerialName("episode_count") val episodeCount: Int = 1,
    @SerialName("first_played_at") val firstPlayedAt: Long,
    @SerialName("last_played_at") val lastPlayedAt: Long,
    val finished: Boolean = true,
    @SerialName("progress_pct") val progressPct: Int? = null,
)

@Serializable
data class WatchHistoryPage(
    val entries: List<WatchHistoryEntry>,
    @SerialName("has_more") val hasMore: Boolean,
)

object RaviloApi {
    suspend fun getUsers(): List<JellyfinUser> =
        httpClient.get("/api/jellyfin/users").body()

    suspend fun getConfig(userId: String): RaviloConfig =
        httpClient.get("/api/tv/admin/config?userId=$userId").body<AdminConfigResponse>().config

    suspend fun getConfigWithMeta(scope: String? = null, userId: String? = null): AdminConfigResponse {
        val q = when {
            scope == "global" -> "?scope=global"
            userId != null -> "?userId=$userId"
            else -> ""
        }
        return httpClient.get("/api/tv/admin/config$q").body()
    }

    suspend fun removeUserConfig(userId: String) {
        val r = httpClient.delete("/api/tv/admin/config?userId=$userId")
        if (!r.status.isSuccess()) throw Exception(r.body<String>())
    }

    // R162 — the field-level behaviour & preferences overlay, independent of the layout config above.
    suspend fun getBehaviour(userId: String): ResolvedBehaviour =
        httpClient.get("/api/tv/admin/behaviour?userId=$userId").body()

    suspend fun setBehaviour(userId: String, req: ViewerSettingsRequest): ResolvedBehaviour {
        val r = httpClient.put("/api/tv/admin/behaviour?userId=$userId") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        if (!r.status.isSuccess()) throw Exception(r.body<String>())
        return r.body()
    }

    suspend fun resetBehaviourField(userId: String, field: String): ResolvedBehaviour {
        val r = httpClient.delete("/api/tv/admin/behaviour?userId=$userId&field=$field")
        if (!r.status.isSuccess()) throw Exception(r.body<String>())
        return r.body()
    }

    suspend fun putConfig(userId: String, config: RaviloConfig) {
        val r = httpClient.put("/api/tv/admin/config?userId=$userId") {
            contentType(ContentType.Application.Json)
            setBody(config)
        }
        if (!r.status.isSuccess()) throw Exception(r.body<String>())
    }

    suspend fun putGlobalConfig(config: RaviloConfig) {
        val r = httpClient.put("/api/tv/admin/config?scope=global") {
            contentType(ContentType.Application.Json)
            setBody(config)
        }
        if (!r.status.isSuccess()) throw Exception(r.body<String>())
    }

    // Phase 138 — Request tab add-row pickers (genre/studio/network dropdowns instead of raw TMDB ids).
    suspend fun getSeerrGenres(kind: String): List<PickerOption> =
        httpClient.get("/api/config/seerr/genres") { parameter("kind", kind) }.body()

    suspend fun getSeerrStudios(q: String = ""): List<PickerOption> =
        httpClient.get("/api/config/seerr/studios") { if (q.isNotBlank()) parameter("q", q) }.body()

    suspend fun getSeerrNetworks(): List<PickerOption> =
        httpClient.get("/api/config/seerr/networks").body()

    suspend fun listChannelLogos(): List<ChannelLogo> =
        httpClient.get("/api/tv/admin/channel-logos").body()

    suspend fun uploadChannelLogo(filename: String, dataBase64: String): ChannelLogo {
        val r = httpClient.post("/api/tv/admin/channel-logos") {
            contentType(ContentType.Application.Json)
            setBody(ChannelLogoUpload(filename, dataBase64))
        }
        if (!r.status.isSuccess()) throw Exception(r.body<String>())
        return r.body()
    }

    // Phase 143 — Users & Devices Settings tab.
    suspend fun getOverview(): List<OverviewUser> =
        httpClient.get("/api/tv/admin/overview").body()

    /** Phase 177 §FR-177-5 — the Activity page's "Playback quality" card. */
    suspend fun getRecentPlaybackQuality(): List<QoeActivityRow>? = runCatching {
        httpClient.get("/api/tv/admin/playback-qoe/recent").body<List<QoeActivityRow>>()
    }.getOrNull()

    suspend fun revokeDevice(deviceId: String, userId: String) {
        httpClient.delete("/api/tv/admin/devices/$deviceId") { parameter("userId", userId) }
    }

    suspend fun revokeSession(id: String) {
        httpClient.delete("/api/tv/admin/sessions/$id")
    }

    suspend fun signOutAll(userId: String) {
        httpClient.post("/api/tv/admin/users/$userId/signout-all")
    }

    suspend fun getHistory(userId: String, offset: Int = 0): WatchHistoryPage =
        httpClient.get("/api/tv/admin/users/$userId/history") { parameter("offset", offset) }.body()

}
