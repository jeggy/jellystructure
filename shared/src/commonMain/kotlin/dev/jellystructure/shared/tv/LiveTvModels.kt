package dev.jellystructure.shared.tv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Phase 147 — Live TV surfaced from Jellyfin. jellystructure organizes presentation (lineup
// show/number/order/logo/category overrides + guide refresh cadence); Jellyfin stays the source of
// truth for tuners/provider and the program guide itself. Live TV is never a Ravilo top-nav tab
// (Phase 148/R177) — it surfaces via the Home "On now" row and/or a Live TV collection.

@Serializable
data class LiveTvProgramInfo(
    val name: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    @SerialName("is_series") val isSeries: Boolean = false,
)

/** One row of the admin lineup table / the TV-facing channel list — Jellyfin facts + jellystructure's
 *  override, already merged server-side. */
@Serializable
data class LiveTvChannel(
    @SerialName("channel_id") val channelId: String,
    val name: String,
    val number: Int,
    val order: Int,
    val shown: Boolean,
    @SerialName("logo_url") val logoUrl: String?,
    val category: String,
    @SerialName("has_guide") val hasGuide: Boolean,
    @SerialName("is_new") val isNew: Boolean = false,
    val unavailable: Boolean = false,
    @SerialName("current_program") val currentProgram: LiveTvProgramInfo? = null,
    @SerialName("next_program") val nextProgram: LiveTvProgramInfo? = null,
)

@Serializable
data class LiveTvOverview(
    val enabled: Boolean,
    val reachable: Boolean,
    @SerialName("channels_discovered") val channelsDiscovered: Int,
    @SerialName("channels_shown") val channelsShown: Int,
    @SerialName("new_count") val newCount: Int,
    @SerialName("unavailable_count") val unavailableCount: Int,
    @SerialName("epg_cadence_minutes") val epgCadenceMinutes: Int,
    @SerialName("last_synced_at") val lastSyncedAt: Long,
)

@Serializable
data class LiveTvChannelUpdate(
    val shown: Boolean? = null,
    val number: Int? = null,
    val category: String? = null,
    @SerialName("logo_override_url") val logoOverrideUrl: String? = null,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class LiveTvSettingsUpdate(
    val enabled: Boolean? = null,
    @SerialName("epg_cadence_minutes") val epgCadenceMinutes: Int? = null,
)

@Serializable
data class LiveTvTuneRequest(
    @SerialName("channel_id") val channelId: String,
    val capabilities: ClientCapabilities,
)

@Serializable
data class LiveTvStopRequest(
    @SerialName("live_stream_id") val liveStreamId: String,
)

@Serializable
data class LiveTvReorderRequest(
    @SerialName("channel_ids") val channelIds: List<String>,
)

/** One guide slot for a channel — the full-schedule grid (R177 §C); "On now"/next comes from
 *  [LiveTvChannel.currentProgram] instead (embedded on the channel, no separate fetch — addendum C). */
@Serializable
data class LiveTvGuideProgram(
    @SerialName("channel_id") val channelId: String,
    val name: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    @SerialName("is_series") val isSeries: Boolean = false,
)

/** Returned by a channel tune — a sibling of [StreamTicket] for the infinite/open-close Live TV
 *  lifecycle (dev-review addendum D): no resume position, no fixed duration, and a [liveStreamId]
 *  that must be passed back on stop so the tuner/provider stream is explicitly closed. */
@Serializable
data class LiveTvStreamTicket(
    @SerialName("jellyfin_base_url") val jellyfinBaseUrl: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("channel_id") val channelId: String,
    @SerialName("live_stream_id") val liveStreamId: String,
    @SerialName("hls_url") val hlsUrl: String,
    @SerialName("expires_at") val expiresAt: Long,
)

/** Phase 148 §B1 "Live TV on Home" — placement only, shown in the Layout tab only when Live TV is
 *  enabled on the Live TV page; that page owns connection + lineup, this owns where it sits on Home. */
@Serializable
data class LiveTvHomePlacement(
    @SerialName("show_on_now_row") val showOnNowRow: Boolean = true,
    @SerialName("on_now_row_position") val onNowRowPosition: Int = 0,
    @SerialName("show_collection") val showCollection: Boolean = false,
)
