package dev.jellystructure.shared.tv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Enums ────────────────────────────────────────────────────────────────────

enum class MediaKind { MOVIE, SERIES }
enum class RowKind { CONTINUE, NEWLY_ADDED, GENRE, CUSTOM }
enum class ChannelStyle { LOGO, TEXT }
enum class TileShape { POSTER, LANDSCAPE }
enum class Skin { AURORA, MIDNIGHT, NOIR }
/** Join mode for a condition stack (R32 workbench). */
enum class MatchMode { ALL, ANY }

// ─── Pairing ──────────────────────────────────────────────────────────────────

@Serializable
data class PairingChallenge(
    val code: String,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("poll_token") val pollToken: String,
)

@Serializable
data class TvSession(
    @SerialName("device_id") val deviceId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("is_admin") val isAdmin: Boolean,
)

/** Returned by `POST /api/tv/pair/poll` when the challenge has been approved. */
@Serializable
data class PairResult(
    val session: TvSession,
    @SerialName("device_token") val deviceToken: String,
)

// ─── Playback ─────────────────────────────────────────────────────────────────

@Serializable
data class ClientCapabilities(
    val containers: List<String> = emptyList(),
    @SerialName("video_codecs") val videoCodecs: List<String> = emptyList(),
    @SerialName("audio_codecs") val audioCodecs: List<String> = emptyList(),
    @SerialName("max_audio_channels") val maxAudioChannels: Int = 8,
    @SerialName("hls_only") val hlsOnly: Boolean = false,
)

@Serializable
data class SubTrack(
    val index: Int,
    val language: String?,
    val label: String?,
    val forced: Boolean = false,
    @SerialName("is_default") val isDefault: Boolean = false,
    val url: String?,
)

@Serializable
data class StreamTicket(
    @SerialName("jellyfin_base_url") val jellyfinBaseUrl: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("item_id") val itemId: String,
    val container: String,
    @SerialName("direct_play") val directPlay: Boolean,
    @SerialName("hls_url") val hlsUrl: String?,
    @SerialName("start_position_ms") val startPositionMs: Long = 0,
    val subtitles: List<SubTrack> = emptyList(),
    @SerialName("trickplay_url") val trickplayUrl: String? = null,
    @SerialName("expires_at") val expiresAt: Long,
)

@Serializable
data class PlaybackState(
    val watched: Boolean,
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
    val pct: Float,
)

// ─── Catalog ──────────────────────────────────────────────────────────────────

@Serializable
data class MediaCard(
    val id: String,
    val kind: MediaKind,
    val title: String,
    val year: Int?,
    val genre: String?,
    val rating: String?,
    @SerialName("poster_url") val posterUrl: String?,
    @SerialName("backdrop_url") val backdropUrl: String?,
    @SerialName("progress_pct") val progressPct: Float? = null,
    @SerialName("next_up_label") val nextUpLabel: String? = null,
    val badge: String? = null,
)

@Serializable
data class Person(
    val id: String,
    val name: String,
    val role: String?,
    @SerialName("image_url") val imageUrl: String?,
)

// ─── Home feed ────────────────────────────────────────────────────────────────

@Serializable
data class Hero(
    val item: MediaCard,
    @SerialName("tagline_kicker") val taglineKicker: String?,
    @SerialName("backdrop_url") val backdropUrl: String,
    @SerialName("logo_url") val logoUrl: String?,
    val badge: String?,
)

@Serializable
data class Channel(
    val id: String,
    val name: String,
    @SerialName("logo_url") val logoUrl: String?,
    val style: ChannelStyle,
    @SerialName("brand_color") val brandColor: String?,
)

@Serializable
data class Row(
    val id: String,
    val title: String,
    val kind: RowKind,
    val items: List<MediaCard>,
)

@Serializable
data class HomeFeed(
    val heroes: List<Hero>,
    val channels: List<Channel>,
    val rows: List<Row>,
    @SerialName("hero_height_pct") val heroHeightPct: Int = 56,
    @SerialName("auto_advance_seconds") val autoAdvanceSeconds: Int = 6,
)

// ─── Detail ───────────────────────────────────────────────────────────────────

@Serializable
data class Episode(
    val id: String,
    @SerialName("episode_number") val episodeNumber: Int,
    val title: String,
    val runtime: Int,
    val overview: String?,
    @SerialName("still_url") val stillUrl: String?,
    val playback: PlaybackState,
)

@Serializable
data class Season(
    val index: Int,
    val name: String,
    val episodes: List<Episode>,
)

@Serializable
data class SeriesProgress(
    @SerialName("watched_count") val watchedCount: Int,
    @SerialName("total_count") val totalCount: Int,
    @SerialName("resume_episode_id") val resumeEpisodeId: String?,
    @SerialName("resume_label") val resumeLabel: String?,
)

@Serializable
data class MovieDetail(
    val card: MediaCard,
    val synopsis: String?,
    val runtime: Int,
    val cast: List<Person>,
    val related: List<MediaCard>,
    val playback: PlaybackState,
)

@Serializable
data class SeriesDetail(
    val card: MediaCard,
    val synopsis: String?,
    val seasons: List<Season>,
    val cast: List<Person>,
    val related: List<MediaCard>,
    val progress: SeriesProgress,
)

// ─── Search ───────────────────────────────────────────────────────────────────

@Serializable
data class SearchResults(
    val query: String,
    val items: List<MediaCard>,
)

// ─── Config ───────────────────────────────────────────────────────────────────

@Serializable
data class HeroConfig(
    @SerialName("item_id") val itemId: String,
    val enabled: Boolean = true,
    val order: Int = 0,
    val override: Boolean = false,
    // R32 hero-item dressing (optional; null/absent ⇒ defaults).
    val badge: String? = null,
    val tagline: String? = null,
    @SerialName("clearlogo_overlay") val clearlogoOverlay: Boolean = true,
)

/**
 * One filter condition (R32 workbench). `facet` is one of: studio, network, genre, tag,
 * audio_language, audio_codec, track_title, hero_item. `op` is is_any_of | is_none_of for list
 * facets, or contains | not_contains for track_title.
 */
@Serializable
data class Condition(
    val facet: String,
    val op: String = "is_any_of",
    val values: List<String> = emptyList(),
)

@Serializable
data class ChannelConfig(
    val id: String,
    val name: String = "",
    val style: ChannelStyle = ChannelStyle.TEXT,
    @SerialName("brand_color") val brandColor: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    // Legacy single typed filters — superseded by `conditions` when that is non-empty.
    @SerialName("filter_network") val filterNetwork: String? = null,
    @SerialName("filter_studio") val filterStudio: String? = null,
    @SerialName("filter_genre") val filterGenre: String? = null,
    @SerialName("filter_tag") val filterTag: String? = null,
    val match: MatchMode = MatchMode.ALL,
    val conditions: List<Condition> = emptyList(),
    val enabled: Boolean = true,
    val order: Int = 0,
)

@Serializable
data class RowConfig(
    val id: String,
    val kind: RowKind,
    val title: String? = null,
    val enabled: Boolean = true,
    val order: Int = 0,
    @SerialName("media_kind") val mediaKind: String? = null, // "MOVIE", "SERIES", or null = all
    val match: MatchMode = MatchMode.ALL,
    val conditions: List<Condition> = emptyList(),
)

@Serializable
data class RaviloConfig(
    val heroes: List<HeroConfig> = emptyList(),
    val channels: List<ChannelConfig> = emptyList(),
    val rows: List<RowConfig> = emptyList(),
    @SerialName("merge_newly_added") val mergeNewlyAdded: Boolean = false,
    @SerialName("default_skin") val defaultSkin: Skin = Skin.AURORA,
    @SerialName("allow_skin_override") val allowSkinOverride: Boolean = true,
    // Per-viewer skin choice, kept separate from the operator's defaultSkin so an operator
    // default change still reaches viewers who never picked a skin. Null = no override.
    @SerialName("viewer_skin_override") val viewerSkinOverride: Skin? = null,
    @SerialName("show_continue_progress") val showContinueProgress: Boolean = true,
    @SerialName("tile_shape") val tileShape: TileShape = TileShape.POSTER,
    @SerialName("ui_language") val uiLanguage: String = "en",
    @SerialName("hero_height_pct") val heroHeightPct: Int = 56,       // % of screen the hero fills (30..100)
    @SerialName("auto_advance_seconds") val autoAdvanceSeconds: Int = 7, // hero carousel interval seconds; 0 = off (0..120)
) {
    /** The skin actually rendered: the viewer's override when allowed, else the operator default. */
    fun effectiveSkin(): Skin = if (allowSkinOverride) (viewerSkinOverride ?: defaultSkin) else defaultSkin
}

// ─── Request bodies ───────────────────────────────────────────────────────────

@Serializable
data class PlaybackStartRequest(
    @SerialName("item_id") val itemId: String,
    val capabilities: ClientCapabilities,
)

@Serializable
data class PlaybackProgressRequest(
    @SerialName("item_id") val itemId: String,
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("is_paused") val isPaused: Boolean = false,
)

@Serializable
data class PlaybackStopRequest(
    @SerialName("item_id") val itemId: String,
    @SerialName("position_ms") val positionMs: Long,
)

/** On-device viewer-tweakable settings (PUT /api/tv/settings). All fields optional = unchanged. */
@Serializable
data class ViewerSettingsRequest(
    val skin: Skin? = null,
    @SerialName("show_continue_progress") val showContinueProgress: Boolean? = null,
    @SerialName("tile_shape") val tileShape: TileShape? = null,
)

@Serializable
data class MarkRequest(
    @SerialName("item_id") val itemId: String,
    val watched: Boolean,
)

// ─── Browse facets ────────────────────────────────────────────────────────────

@Serializable
data class FacetItem(val name: String, val count: Int)

@Serializable
data class BrowseFacets(
    val genres: List<FacetItem> = emptyList(),
    val studios: List<FacetItem> = emptyList(),
    val networks: List<FacetItem> = emptyList(),
    val tags: List<FacetItem> = emptyList(),
)

// ─── Errors ───────────────────────────────────────────────────────────────────

sealed class TvApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Http(val status: Int, override val message: String) : TvApiError("HTTP $status: $message")
    class Network(override val cause: Throwable) : TvApiError("Network error: ${cause.message}", cause)
    class Parse(override val message: String, override val cause: Throwable) : TvApiError(message, cause)
}
