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
/**
 * 289 — the receiver's own notes on what its player did (a load, an error's code, why an item ended), one short line
 * of text each. A speaker has no screen and no DevTools; this is the only place its errors can be read. A channel of
 * its own, so that no sender's state is built from it: a sender that does not listen never sees it.
 */
const val CAST_LOG_NAMESPACE = "urn:x-cast:dev.jellystructure.ravilo.log"

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
 * 286 (FR-286-4) / R324 — one song in the queue the receiver owns. Ids and titles only, ~150–250 B a song. R359: 286
 * assumed ~30 songs (≈ 4 KB) and sent the whole queue in one LOAD; a long queue is sent as a window plus parts
 * ([castLoadPlan]). [coverUrl] is server-relative, as the app receives it; the receiver makes it absolute against its
 * own server URL.
 */
@Serializable
data class CastTrackItem(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    @SerialName("album_artist") val albumArtist: String? = null,
    val year: Int? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("has_lyrics") val hasLyrics: Boolean = false,
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
    // 286 (FR-286-4) — a music LOAD: the queue, in play order; [currentIndex] is the song to start. Empty = a film
    // or an episode as before. A receiver older than this field ignores all three.
    val tracks: List<CastTrackItem> = emptyList(),
    /** `off` · `all` · `one` */
    val repeat: String = "off",
    val shuffle: Boolean = false,
    /** R343 (FR-R343-8) — [episodes] is a series shuffle in play order (not a season in order): every start
     *  goes up as `shuffle`, and the next-up card says *UP NEXT · SHUFFLED*. Not [shuffle], which is music's
     *  and reorders [tracks]. A receiver older than this field plays the list as it comes, which is the same
     *  order. */
    @SerialName("episodes_shuffled") val episodesShuffled: Boolean = false,
    /** R343 (FR-R343-8) — the first start of this load is a *Start over* (FR-R343-4); the receiver's own
     *  next loads drop it. */
    @SerialName("start_over") val startOver: Boolean = false,
    // R359 (FR-R359-3) — a music queue too long for one message: [tracks] is a window of it, the run that starts at
    // [queueStart] of a queue of [queueTotal] songs ([currentIndex] counts within the window). The rest follows as
    // `queue_part` commands carrying the same [queueId]. [queueTotal] null = [tracks] is the whole queue (as before).
    // [queueId] names this queue for its parts; the receiver's own next loads keep it. A receiver older than R359
    // ignores all three and plays the window.
    @SerialName("queue_id") val queueId: String? = null,
    @SerialName("queue_total") val queueTotal: Int? = null,
    @SerialName("queue_start") val queueStart: Int = 0,
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
    val type: String,                                  // status | busy | noserver | nextup | ended | tracks | failed (R299) | queue_part (R359)
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
    // 286 (dev review 10) — the music snapshot R324 mirrors: the receiver's queue and where it is in it. Null from a
    // receiver that predates music, and on a film.
    val queue: List<CastTrackItem>? = null,
    @SerialName("queue_index") val queueIndex: Int? = null,
    val repeat: String? = null,
    val shuffle: Boolean? = null,
    /** FR-286-6 — lyrics on this display; null on a speaker (nothing to show them on). */
    @SerialName("lyrics_on") val lyricsOn: Boolean? = null,
    /** FR-286-3 — the receiver runs on an audio-only device. */
    val headless: Boolean? = null,
    /**
     * R356 (FR-R356-8) — the queue's revision: the receiver raises it whenever the queue's songs or their order change.
     * A `status` carries [queue] only when this changed since the receiver last sent it in full, after a sender
     * connects, and in answer to `status` / `get_queue`; otherwise [queue] is null and the sender keeps its copy.
     * Null from a receiver older than R356 (which sends the queue every time).
     */
    @SerialName("queue_rev") val queueRev: Int? = null,
    /** R356 — how many songs the queue holds, said also when [queue] is left out. */
    @SerialName("queue_size") val queueSize: Int? = null,
    /**
     * R359 (FR-R359-5) — on a `status`: the queue did not fit in it and follows in this many `queue_part` messages (a
     * sender that knows them does not ask `get_queue` for the revision). On a `queue_part`: unset.
     */
    @SerialName("queue_parts") val queueParts: Int? = null,
    /** R359 — on a `queue_part`: where [queue] (a run of the queue of [queueSize] songs, revision [queueRev]) starts. */
    @SerialName("queue_offset") val queueOffset: Int? = null,
)

/** Phone → receiver. 286/R324 add `prev` · `play_at` · `queue_move` · `queue_remove` · `queue_add` · `queue_play_next`
 *  · `repeat` · `shuffle` · `lyrics`, R359 `queue_part` — the same shape, extended (additive). */
@Serializable
data class CastCommand(
    val type: String,                                  // subtitle | audio | subsize | next | nextup_cancel | nextup_play | status | get_queue (R356)
    val index: Int? = null,
    val size: String? = null,
    /** `queue_move`'s destination. */
    val to: Int? = null,
    /** `queue_add` / `queue_play_next`. */
    val track: CastTrackItem? = null,
    /** `lyrics` (on/off) and `shuffle`. */
    val on: Boolean? = null,
    /** `repeat`: `off` · `all` · `one`. */
    val mode: String? = null,
    /** R359 (FR-R359-3) — `queue_part`: the LOAD's [CastLoadData.queueId] this part belongs to. */
    @SerialName("queue_id") val queueId: String? = null,
    /** R359 — `queue_part`: where [tracks] starts in the whole queue. */
    val offset: Int? = null,
    /** R359 — `queue_part`: a run of the queue, in the sender's order. */
    val tracks: List<CastTrackItem>? = null,
)
