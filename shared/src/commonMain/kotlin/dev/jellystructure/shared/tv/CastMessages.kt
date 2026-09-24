package dev.jellystructure.shared.tv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── R245 — what the phone and the Chromecast receiver say to each other ─────────────────────────
//
// Shared on purpose: the receiver is a Kotlin/JS client of this same module, so the two sides cannot
// drift. Everything Jellyfin-shaped stays server-side — the receiver enrols with a hand-off code
// (Phase 218 FR-218-9), holds a Ravilo device token like a TV, and every Jellyfin call is made by
// jellystructure on its behalf. Nothing here names a product, a codec or a status code (FR-R245-16).

/** The custom namespace both sides register on the Cast session. */
const val CAST_NAMESPACE = "urn:x-cast:dev.jellystructure.ravilo"

/** One episode the receiver may advance to by itself (FR-R245-14) — ids and the markers it needs. */
@Serializable
data class CastEpisode(
    val id: String,
    val title: String,
    val kicker: String? = null,
    @SerialName("still_url") val stillUrl: String? = null,
    @SerialName("intro_start_ms") val introStartMs: Long? = null,
    @SerialName("intro_end_ms") val introEndMs: Long? = null,
    @SerialName("credits_start_ms") val creditsStartMs: Long? = null,
)

/**
 * The LOAD request's `customData`. [code] is the single-use hand-off code; [receiverId] is the
 * receiver's own persisted device id when its storage survived since the last cast (so the same
 * `ravilo_device` row is reused), else null. [positionMs] is set only when the phone hands a live
 * position over (casting from inside the local player); null lets the server resolve the resume
 * position exactly as it does for a TV.
 */
@Serializable
data class CastLoadData(
    @SerialName("server_url") val serverUrl: String,
    val code: String,
    @SerialName("item_id") val itemId: String,
    val title: String,
    val kicker: String? = null,
    @SerialName("art_url") val artUrl: String? = null,
    @SerialName("position_ms") val positionMs: Long? = null,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("receiver_id") val receiverId: String? = null,
    val episodes: List<CastEpisode> = emptyList(),
    @SerialName("current_index") val currentIndex: Int = -1,
    val lang: String = "en",
    @SerialName("sub_size") val subSize: String = "M",
)

/** A track the receiver reports back so the phone's picker can render it (R180/R195 shape). */
@Serializable
data class CastTrack(
    val index: Int,
    val label: String? = null,
    val language: String? = null,
    val forced: Boolean = false,
    @SerialName("is_default") val isDefault: Boolean = false,
    /** The Cast media track id the phone selects it by (null for "Off"). */
    @SerialName("track_id") val trackId: Long? = null,
)

/** Receiver → phone. One message type, optional fields; the receiver sends it on every state change. */
@Serializable
data class CastReceiverMessage(
    val type: String,                                  // status | busy | noserver | nextup | ended | tracks | failed (R299)
    @SerialName("item_id") val itemId: String? = null,
    val title: String? = null,
    val kicker: String? = null,
    @SerialName("art_url") val artUrl: String? = null,
    @SerialName("has_next") val hasNext: Boolean = false,
    @SerialName("retry_after") val retryAfter: Int? = null,
    @SerialName("since_ms") val sinceMs: Long? = null,
    @SerialName("nextup_secs") val nextupSecs: Int? = null,
    @SerialName("next_title") val nextTitle: String? = null,
    @SerialName("audio_tracks") val audioTracks: List<CastTrack> = emptyList(),
    @SerialName("subtitle_tracks") val subtitleTracks: List<CastTrack> = emptyList(),
    @SerialName("selected_audio") val selectedAudio: Int = 0,
    @SerialName("selected_sub") val selectedSub: Int = -1,
    @SerialName("sub_size") val subSize: String = "M",
    @SerialName("receiver_id") val receiverId: String? = null,
    /** FR-R245-19 — the stream the receiver is playing is a server-side conversion of the file, not the
     *  file itself (`StreamTicket.directPlay == false`). Null from a receiver older than this field. */
    @SerialName("transcoding") val transcoding: Boolean? = null,
)

/** Phone → receiver. */
@Serializable
data class CastCommand(
    val type: String,                                  // subtitle | audio | subsize | next | nextup_cancel | nextup_play | status
    val index: Int? = null,
    val size: String? = null,
)
