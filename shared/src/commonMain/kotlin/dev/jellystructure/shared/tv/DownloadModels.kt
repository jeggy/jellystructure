package dev.jellystructure.shared.tv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * DEB-4061 (DEB-3995 FR-4) — `GET /api/tv/download/options?item_id=&media_source_id=`: what a phone may download of one
 * title. Additive; an old app never calls it. A viewer the operator has switched off gets a `403` instead (FR-2).
 */
@Serializable
data class DownloadOptions(val options: List<DownloadOption> = emptyList())

/**
 * One choice. [quality] is `original` | `high` | `medium` | `small`; [height] is the picture's height in pixels when
 * known. [bytes] is exact only when [exact] is true (the original file); a copy's is an estimate the phone marks "about".
 */
@Serializable
data class DownloadOption(
    val quality: String,
    val height: Int? = null,
    val bytes: Long,
    val exact: Boolean = false,
)

/**
 * `POST /api/tv/download/start`. [capabilities] is optional: the phone's declared decoders, so a file it cannot decode is
 * refused here as the options route would not list it. Absent, the server uses what this device declared at its last play.
 */
@Serializable
data class DownloadStartRequest(
    @SerialName("item_id") val itemId: String,
    @SerialName("media_source_id") val mediaSourceId: String? = null,
    val quality: String = "original",
    val capabilities: ClientCapabilities? = null,
    /** DEB-4062 (FR-6) — a smaller copy only: the 2 s segment a resumed download reached, so the stream's first job starts there. Absent = 0. */
    @SerialName("from_segment") val fromSegment: Int? = null,
    /** DEB-4062 — a smaller copy carries one audio: the viewer's remembered choice (see `resolveAudioChoice`), else the file's default. */
    @SerialName("audio_language") val audioLanguage: String? = null,
    @SerialName("audio_variant") val audioVariant: String? = null,
)

/**
 * A download ticket. A `start` is never a play: no session, no *Playing now*, no QoE row. [url] may expire; a paused
 * download asks `start` again for a fresh one.
 *
 * [item] is the exact payload the title's page route sends, kept verbatim and never merged: [itemKind] says which — `movie`
 * is `GET /api/tv/movie/{id}`'s body, `episode` is `GET /api/tv/series/{[seriesId]}`'s, the page that lists the episode.
 */
@Serializable
data class DownloadTicket(
    /** `file` for the original; `hls` for a smaller copy: [url] is a master playlist of one rung and one audio. */
    val kind: String,
    val quality: String,
    val url: String,
    val bytes: Long,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("media_source_id") val mediaSourceId: String? = null,
    val subtitles: List<DownloadSubtitle> = emptyList(),
    val artwork: DownloadArtwork = DownloadArtwork(),
    @SerialName("item_kind") val itemKind: String,
    @SerialName("series_id") val seriesId: String? = null,
    val item: JsonElement,
)

/** One text subtitle track, as WebVTT. */
@Serializable
data class DownloadSubtitle(
    val index: Int,
    val language: String?,
    val label: String?,
    val forced: Boolean = false,
    @SerialName("is_default") val isDefault: Boolean = false,
    val url: String,
)

/** The pictures the phone shows offline; any may be null when the title has none. [still] is an episode's own. */
@Serializable
data class DownloadArtwork(
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("still_url") val stillUrl: String? = null,
)

/**
 * DEB-4258 (DEB-4256 FR-6, FR-7) — the JSON body of `POST /api/tv/download/start`'s waiting `503`. [reason] is `encoder_full` (no card can take
 * one more rung; [streams] is how many streams the encoder is making, plays and other phones' downloads together) or `unavailable` (this copy
 * cannot be made right now). Both fields are additive: an older server sends neither, a newer one may send a reason this phone does not know,
 * and both read as the generic wait. Nothing in it names a title, a viewer, a device or a file.
 */
@Serializable
data class DownloadWaitBody(
    val error: String? = null,
    val reason: String? = null,
    val streams: Int? = null,
)

/** The reason a waiting `start` answered, as the phone reads it. [Unknown] is an absent, unreadable or newer reason. */
sealed interface DownloadWaitReason {
    /** [streams] is null when the answer carried no count. */
    data class EncoderFull(val streams: Int?) : DownloadWaitReason
    data object Unavailable : DownloadWaitReason
    data object Unknown : DownloadWaitReason

    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

        /** Reads the `503`'s body ([TvApiError.Http.message]); anything that is not the JSON above is [Unknown]. */
        fun parse(body: String?): DownloadWaitReason {
            val b = body?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(DownloadWaitBody.serializer(), it) }.getOrNull() }
                ?: return Unknown
            return when (b.reason) {
                "encoder_full" -> EncoderFull(b.streams?.takeIf { it >= 1 })
                "unavailable" -> Unavailable
                else -> Unknown
            }
        }
    }
}
