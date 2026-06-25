package dev.jellystructure.shared.tv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Enums ────────────────────────────────────────────────────────────────────

enum class MediaKind { MOVIE, SERIES }
enum class RowKind { CONTINUE, NEWLY_ADDED, GENRE, CUSTOM }
enum class ChannelStyle { LOGO, TEXT }
enum class TileShape { POSTER, LANDSCAPE, SQUARE }
enum class Skin { AURORA, MIDNIGHT, NOIR }
/** Join mode for a condition stack (R32 workbench). */
enum class MatchMode { ALL, ANY }

/** Operator-set content size for the TV grids/rows. COMFORTABLE = current sizing. */
enum class UiDensity { COMPACT, COZY, COMFORTABLE }

/** Multiplier applied to tile dimensions on the TV for the chosen density. */
fun UiDensity.tileScale(): Float = when (this) {
    UiDensity.COMPACT -> 0.82f
    UiDensity.COZY -> 0.91f
    UiDensity.COMFORTABLE -> 1f
}

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
    @SerialName("avatar_url") val avatarUrl: String? = null,
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

/**
 * Per-audio-track metadata from Jellyfin's MediaStreams (R46), so the player picker can show the
 * human-readable title (e.g. "Synstolkning") instead of a bare language code. `label` is Jellyfin's
 * `DisplayTitle ?: Title`. Order matches the container's audio-stream order (used to map to the
 * player's enumerated audio tracks).
 */
@Serializable
data class AudioTrack(
    val index: Int,
    val language: String?,
    val label: String?,
    val codec: String? = null,
    val channels: Int? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
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
    val audio: List<AudioTrack> = emptyList(),
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
    val watched: Boolean = false,
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
    val synopsis: String? = null,
)

@Serializable
data class Channel(
    val id: String,
    val name: String,
    @SerialName("logo_url") val logoUrl: String?,
    val style: ChannelStyle,
    @SerialName("brand_color") val brandColor: String?,
    @SerialName("padding_logo") val paddingLogo: ChannelButtonPadding? = null,
    @SerialName("padding_text") val paddingText: ChannelButtonPadding? = null,
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
    @SerialName("auto_advance_seconds") val autoAdvanceSeconds: Int = 7,
    @SerialName("tile_shape") val tileShape: TileShape = TileShape.POSTER,
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

// ─── Live events (R33) ──────────────────────────────────────────────────────────

/**
 * Pushed over the `/api/tv/events` WebSocket to a user's connected devices. `type` is currently
 * always `config_changed`; `rev` is a monotonic counter the client uses to dedupe/skip redundant
 * refreshes. The event is a signal only — the client re-pulls the authoritative feed/config.
 */
@Serializable
data class TvEvent(val type: String, val rev: Long = 0)

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
    // Admin display hints — stored so the editor renders without extra lookups.
    // The TV backend resolves the item from itemId alone and ignores these.
    @SerialName("display_title") val displayTitle: String? = null,
    @SerialName("display_meta") val displayMeta: String? = null,
    @SerialName("display_backdrop") val displayBackdrop: String? = null,
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

/** R53 — per-display-mode edge padding for the channel button (0..40 px each side). */
@Serializable
data class ChannelButtonPadding(
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
)

/**
 * R52 — optional hero carousel for a channel/collection page (absent = no hero on that page).
 * R58 — heroHeightPct + autoAdvanceSeconds removed; both now live only at the layout level
 *        (RaviloConfig) and apply to every hero uniformly (Home and all channel pages).
 */
@Serializable
data class PageHeroConfig(
    val enabled: Boolean = false,
    val items: List<HeroConfig> = emptyList(),
)

/**
 * R59 — per-channel content rows. `inherit` (default) = the feed serves the global Home rows
 * scoped to this channel; `custom` = the feed serves [items] scoped to this channel.
 * Reuses [RowConfig] verbatim — no parallel row model.
 */
@Serializable
data class ChannelRowsConfig(
    val mode: String = "inherit",  // "inherit" | "custom"
    val items: List<RowConfig> = emptyList(),
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
    // R52 — optional per-page hero carousel (null = page has no hero).
    @SerialName("page_hero") val pageHero: PageHeroConfig? = null,
    // R53 — per-display-mode channel-button padding (null = no padding for that mode).
    @SerialName("padding_logo") val paddingLogo: ChannelButtonPadding? = null,
    @SerialName("padding_text") val paddingText: ChannelButtonPadding? = null,
    // R59 — per-channel content rows (null / inherit = use global Home rows).
    val rows: ChannelRowsConfig? = null,
)

/** A server-owned channel-button logo asset (R36 §F), served at `/api/tv/channel-logos/<file>`. */
@Serializable
data class ChannelLogo(val url: String, val label: String)

/** Upload payload for a channel logo: original filename + base64 file bytes (data-URL prefix tolerated). */
@Serializable
data class ChannelLogoUpload(
    val filename: String,
    @SerialName("data_base64") val dataBase64: String,
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
    @SerialName("autoplay_next") val autoplayNext: Boolean = true,
    @SerialName("tile_shape") val tileShape: TileShape = TileShape.POSTER,
    @SerialName("ui_density") val uiDensity: UiDensity = UiDensity.COMFORTABLE,
    @SerialName("ui_language") val uiLanguage: String = "en",
    @SerialName("hero_height_pct") val heroHeightPct: Int = 56,       // % of screen the hero fills (40..100)
    @SerialName("auto_advance_seconds") val autoAdvanceSeconds: Int = 7, // hero carousel interval seconds; 0 = off (0..120)
    val discover: DiscoverConfig = DiscoverConfig(),                   // R48 — Top 10 / Discover tab
) {
    /** The skin actually rendered: the viewer's override when allowed, else the operator default. */
    fun effectiveSkin(): Skin = if (allowSkinOverride) (viewerSkinOverride ?: defaultSkin) else defaultSkin
}

/**
 * R48 — per-user Discover/Top-10 selection (server-owned, synced). `canRequest` gates whether a
 * non-admin may spend disk/bandwidth (admins always may). `lists` are ordered ChartListSpec ids.
 */
@Serializable
data class DiscoverConfig(
    val enabled: Boolean = false,
    @SerialName("can_request") val canRequest: Boolean = false,
    val source: String = "netflix",
    val region: String = "DK",
    val lists: List<String> = emptyList(),
)

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
    @SerialName("autoplay_next") val autoplayNext: Boolean? = null,
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
