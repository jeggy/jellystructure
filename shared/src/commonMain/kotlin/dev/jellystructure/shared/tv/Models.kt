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

// ─── Login ────────────────────────────────────────────────────────────────────

/** Phase 141/R175 — posted to `POST /api/tv/login`. [deviceId] is the device's own stable id
 *  (client-generated, persisted); the Jellyfin credentials are proxied server-side and never stored. */
@Serializable
data class TvLoginRequest(
    val username: String,
    val password: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String? = null,
)

@Serializable
data class TvSession(
    @SerialName("device_id") val deviceId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("is_kids") val isKids: Boolean = false,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/** Returned by `POST /api/tv/login` on a successful sign-in. */
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
    // Bug fix: an HDR10/HDR10+ (PQ) or HLG source used to always direct-play regardless of whether
    // the device could actually display it correctly — Jellyfin's DeviceProfile declared no VideoRange
    // constraint at all, so it never had a reason to tone-map-transcode to SDR. These default to
    // `false` (conservative: assume SDR-only, which forces a correctly tone-mapped transcode) unless
    // the client has verified real display/decoder support (see ravilo-ui's `detectHdrSupport()`).
    @SerialName("supports_hdr10") val supportsHdr10: Boolean = false,
    @SerialName("supports_hlg") val supportsHlg: Boolean = false,
)

@Serializable
data class SubTrack(
    val index: Int,
    val language: String?,
    val label: String?,
    val forced: Boolean = false,
    @SerialName("is_default") val isDefault: Boolean = false,
    val url: String?,
    /** R56 — "external" (VTT sideload), "embed" (native in-container, VobSub/DVDSub), "encode" (burn-in transcode, PGS). */
    @SerialName("delivery_method") val deliveryMethod: String = "external",
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
    // R113: season/episode of the specific episode this card resumes/queues (Continue Watching),
    // surfaced as a small on-image badge for TV shows. Null for movies / when unknown.
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val badge: String? = null,
    val watched: Boolean = false,
    /** R149: true when Sonarr is enabled, the series is continuing, and a next-airing date exists. */
    @SerialName("upcoming_episode") val upcomingEpisode: String? = null,
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
    // R159 — portrait-only hero height override (20-100); null = no override, portrait uses heroHeightPct.
    @SerialName("portrait_hero_height_pct") val portraitHeroHeightPct: Int? = null,
)

// ─── Detail ───────────────────────────────────────────────────────────────────

/** Phase 106: server-resolved age-rating certification badge (region-cascade winner). Null = the item
 *  has no certification data at all. Ravilo never re-runs the cascade — it only renders this (R153). */
@Serializable
data class RatingBadge(
    val region: String,
    val code: String,
    val tier: Int,
    val fallback: Boolean = false,
)

/** R149: Next scheduled unaired episode (Sonarr-sourced, server-pushed). Null = not applicable. */
@Serializable
data class NextAiring(
    val season: Int,
    val episode: Int,
    val title: String? = null,
    /** UTC-pinned ISO date: yyyy-MM-dd. */
    @SerialName("air_date") val airDate: String,
)

/**
 * R83: per-id play-state snapshot, returned by `GET /api/tv/playstate?ids=…`.
 * Null-absent ids are not in the response (item has no user data or was not found).
 */
@Serializable
data class CardPlayState(
    @SerialName("resume_ms") val resumeMs: Long = 0,
    val played: Boolean = false,
    @SerialName("played_pct") val playedPct: Float = 0f,
)

@Serializable
data class Episode(
    val id: String,
    @SerialName("episode_number") val episodeNumber: Int,
    val title: String,
    val runtime: Int,
    val overview: String?,
    @SerialName("still_url") val stillUrl: String?,
    /** R83: null until hydrated from `/api/tv/playstate`; R84 overlays it. */
    val playback: PlaybackState? = null,
    /** R148: episode first-air date (ISO yyyy-MM-dd) from the scanned MediaItem. Null = no date line. */
    @SerialName("air_date") val airDate: String? = null,
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

/** Phase 130: one official trailer reference (YouTube/Vimeo) — catalog-only, populated straight from
 *  the stored MediaItem.trailer. Null when the title has none; every consumer (R163) is conditional. */
@Serializable
data class TvTrailer(
    val site: String,   // "youtube" | "vimeo"
    val key: String,
    val name: String? = null,
)

/** Phase 131: IMDb rating — catalog-only, populated straight from the stored MediaItem.imdbRating
 *  (never fetched at detail-read time). Null when the title has no rating yet. R164 renders it. */
@Serializable
data class TvImdbRating(
    @SerialName("aggregate_rating") val aggregateRating: Double,
    @SerialName("vote_count") val voteCount: Long,
)

@Serializable
data class MovieDetail(
    val card: MediaCard,
    val synopsis: String?,
    val runtime: Int,
    val cast: List<Person>,
    val related: List<MediaCard>,
    /** R83: null until hydrated from `/api/tv/playstate`; R84 overlays it. */
    val playback: PlaybackState? = null,
    @SerialName("audio_languages") val audioLanguages: List<String> = emptyList(),
    @SerialName("subtitle_languages") val subtitleLanguages: List<String> = emptyList(),
    /** R130: clearlogo proxy URL (always set when the item has a Jellyfin id); the app falls back to
     *  the title as text if it's null or the image 404s. */
    @SerialName("logo_url") val logoUrl: String? = null,
    /** Phase 106: server-resolved age-rating badge (R153 renders it). Null = no certification data. */
    @SerialName("rating_badge") val ratingBadge: RatingBadge? = null,
    /** Phase 130: null when TMDB has no usable trailer. R163 renders the Play-Trailer button. */
    val trailer: TvTrailer? = null,
    /** Phase 131: null when the title has no IMDb rating yet. R164 renders it. */
    @SerialName("imdb_rating") val imdbRating: TvImdbRating? = null,
)

@Serializable
data class SeriesDetail(
    val card: MediaCard,
    val synopsis: String?,
    val seasons: List<Season>,
    val cast: List<Person>,
    val related: List<MediaCard>,
    /** R83: null until hydrated from `/api/tv/playstate`; R84 overlays it. */
    val progress: SeriesProgress? = null,
    @SerialName("audio_languages") val audioLanguages: List<String> = emptyList(),
    @SerialName("subtitle_languages") val subtitleLanguages: List<String> = emptyList(),
    /** R130: clearlogo proxy URL; the app falls back to the title as text if null or the image 404s. */
    @SerialName("logo_url") val logoUrl: String? = null,
    /** R149: next scheduled unaired episode from Sonarr. Null = Sonarr off / ended / no date. */
    @SerialName("next_airing") val nextAiring: NextAiring? = null,
    /** Phase 106: server-resolved age-rating badge (R153 renders it). Null = no certification data. */
    @SerialName("rating_badge") val ratingBadge: RatingBadge? = null,
    /** Phase 130: null when TMDB has no usable trailer (series-level only). R163 renders the button. */
    val trailer: TvTrailer? = null,
    /** Phase 131: null when the title has no IMDb rating yet (the show-level rating). R164 renders it. */
    @SerialName("imdb_rating") val imdbRating: TvImdbRating? = null,
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

/**
 * Pushed once a live Jellyfin playstate fetch completes for a user — every one of that user's connected
 * devices patches its already-rendered tiles in place (the same `WatchedBus` path R147 uses for a
 * locally-triggered "mark as watched"), so a fetch made to satisfy one device's `/api/tv/home` request
 * keeps every other open screen for that user in sync too, instead of each one needing its own
 * independent live round trip.
 */
@Serializable
data class PlaystateChangedEnvelope(
    val type: String = "",
    val patch: Map<String, CardPlayState> = emptyMap(),
)

/**
 * R152 — a Jellyfin dashboard "send message" relayed device-addressed over `/api/tv/events` (via the
 * Phase 110 session bridge). Payload-bearing, same envelope shape as [dev.jellystructure.shared.tv.AcquisitionChangedEnvelope].
 */
@Serializable
data class ServerMessageEnvelope(
    val type: String = "",
    val text: String = "",
    val header: String? = null,
    @SerialName("timeout_ms") val timeoutMs: Long? = null,
)

/**
 * R155 — a remote "play this" command (Home Assistant via Phase 111, or the Jellyfin dashboard cast
 * menu via the Phase 110 bridge), device-addressed over `/api/tv/events`. `kind` is resolved
 * server-side ("movie" | "series" | "episode") so the app never has to look it up.
 */
@Serializable
data class PlayItemEnvelope(
    val type: String = "",
    @SerialName("jellyfin_id") val jellyfinId: String = "",
    val kind: String = "movie",
    val title: String? = null,
    @SerialName("start_position_ms") val startPositionMs: Long = 0,
)

/** R155 — a remote playstate command (stop/pause/unpause/seek) for whichever item is currently
 *  playing on this device. Ignored if no player is open. */
@Serializable
data class PlaystateCommandEnvelope(
    val type: String = "",
    val command: String = "",
    @SerialName("seek_position_ms") val seekPositionMs: Long? = null,
)

/** R155 — a remote navigation command ("home" only, this phase). */
@Serializable
data class NavigateEnvelope(
    val type: String = "",
    val destination: String = "",
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
    // Admin display hints — stored so the editor renders without extra lookups.
    // The TV backend resolves the item from itemId alone and ignores these.
    @SerialName("display_title") val displayTitle: String? = null,
    @SerialName("display_meta") val displayMeta: String? = null,
    @SerialName("display_backdrop") val displayBackdrop: String? = null,
)

/**
 * One filter condition (R32 workbench). `facet` is one of: studio, network, genre, tag,
 * audio_language, audio_codec, track_title, hero_item, content_row. `op` is is_any_of | is_none_of
 * for list facets, or contains | not_contains for track_title.
 *
 * R87 — `content_row` facet: membership in another saved filter (a content row). Its referenced rows
 * are carried verbatim in [rows] (RowConfig specs, reused — no parallel taxonomy, no ids to resolve);
 * `values` is unused for this facet. A title matches "is_any_of" iff it matches any listed row's
 * filter; "is_none_of" iff none. Evaluated by the same per-title matcher as every other condition.
 *
 * Phase 140 — also a leaf [QueryNode] (`: QueryNode`, `@SerialName("cond")`) so it can sit inside a
 * [ConditionGroup] tree. This is additive: kotlinx-serialization only injects the `"kind"` class
 * discriminator when serializing *through* the sealed `QueryNode` type (e.g. a `List<QueryNode>`
 * field) — every existing call site that serializes `Condition`/`List<Condition>` directly (batch-
 * count, `/media/facets`, the legacy `conditions=` URL param) keeps producing/expecting the exact
 * same shape as before, unchanged. See `QueryTree.kt` for the tree model + migration/prune helpers.
 */
@Serializable
@SerialName("cond")
data class Condition(
    val facet: String,
    val op: String = "is_any_of",
    val values: List<String> = emptyList(),
    // R87: referenced content rows for the `content_row` facet (empty for all other facets).
    val rows: List<RowConfig> = emptyList(),
) : QueryNode

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
    // R143 — per-channel system rows (Continue Watching, Newly Added). Only consulted in custom mode;
    // in "inherit" mode the channel keeps inheriting Home's system rows (scoped to the channel per R59).
    val system: ChannelSystemRows = ChannelSystemRows(),
)

/**
 * R143 — per-channel system-row settings. Both rows shown by default, **scoped to the channel** (R145):
 * a channel page is a curated subset, so its Continue Watching / Newly Added show only titles belonging to
 * the channel (`scope = "channel"` ANDs the channel's own conditions, R32). `scope = "all"` is the opt-out
 * that shows the library-wide row instead.
 */
@Serializable
data class ChannelSystemRows(
    // `continue` is a Kotlin keyword → property is `cont`, serialized as "continue" to match the mockup.
    @SerialName("continue") val cont: SystemContinue = SystemContinue(),
    val newly: SystemNewly = SystemNewly(),
)

@Serializable
data class SystemContinue(
    val show: Boolean = true,
    val scope: String = "channel",  // "channel" (this channel's in-progress titles) | "all" (library-wide)
)

@Serializable
data class SystemNewly(
    val show: Boolean = true,
    val scope: String = "channel",  // "channel" (newest in this channel) | "all" (library-wide)
    val merge: Boolean = false,
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
    // Phase 140 — the recursive blocks tree; null = not yet migrated from match/conditions (see
    // ChannelConfig.effectiveQuery() in QueryTree.kt). Additive field — an installed Ravilo TV APK
    // that predates this phase just skips it (ignoreUnknownKeys) since the TV never evaluates
    // filters itself (server-pushed rows only).
    val query: ConditionGroup? = null,
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
    // Phase 140 — see ChannelConfig.query above; same additive/migration story here.
    val query: ConditionGroup? = null,
)

@Serializable
data class RaviloConfig(
    val heroes: List<HeroConfig> = emptyList(),
    val channels: List<ChannelConfig> = emptyList(),
    val rows: List<RowConfig> = emptyList(),
    // Newly Added on Home: true = one combined row (all media); false = split Movies + Series.
    // Channel pages follow this too (R143): inherit-mode channels and custom channels set to
    // "inherit" use it; custom channels can override per-channel (merged/split/none).
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
    // R174 — items per row in the poster grids (all-movies/series browse + search + request search),
    // landscape/TV. Portrait overrides this via PortraitConfig.gridColumns. Clamped 2..10 server-side.
    @SerialName("grid_columns") val gridColumns: Int = 6,
    val discover: DiscoverConfig = DiscoverConfig(),                   // R48 — Top 10 / Discover tab
    // R159 — optional overrides applied only when the app's viewport is portrait. Null = no overrides
    // (portrait behaves exactly like landscape); the home for future portrait-only settings.
    val portrait: PortraitConfig? = null,
) {
    /** The skin actually rendered: the viewer's override when allowed, else the operator default. */
    fun effectiveSkin(): Skin = if (allowSkinOverride) (viewerSkinOverride ?: defaultSkin) else defaultSkin
}

/**
 * R162 — field-level per-user behaviour & preferences overlay, independent of [RaviloConfig]'s
 * layout override (R51, full-replace). Each field is sparse: absent = "follow global default".
 * `*Writer` tags who set a present value — `"viewer"` (via `PUT /api/tv/settings`, R161) or
 * `"admin"` (via the config editor). Resolution is always **viewer entry → admin entry → global
 * default**; the editor may only Reset a viewer-tagged entry, never overwrite it (R161's guardrail
 * against silently clobbering a viewer's explicit on-TV choice).
 */
@Serializable
data class BehaviourOverlay(
    @SerialName("ui_language") val uiLanguage: String? = null,
    @SerialName("ui_language_writer") val uiLanguageWriter: String? = null,
    val skin: Skin? = null,
    @SerialName("skin_writer") val skinWriter: String? = null,
    @SerialName("tile_shape") val tileShape: TileShape? = null,
    @SerialName("tile_shape_writer") val tileShapeWriter: String? = null,
    @SerialName("show_continue_progress") val showContinueProgress: Boolean? = null,
    @SerialName("show_continue_progress_writer") val showContinueProgressWriter: String? = null,
    @SerialName("autoplay_next") val autoplayNext: Boolean? = null,
    @SerialName("autoplay_next_writer") val autoplayNextWriter: String? = null,
    // Phase 139 — per-viewer default request-language intent id (e.g. "nordic"). Unlike the other
    // fields above, its "global default" fallback is NOT a RaviloConfig field — it's whichever
    // request-language intent the admin flagged `default = true` in AppConfig (resolved in
    // SeerrDiscoverService, which already has ConfigStore access), so there is no matching field on
    // [RaviloConfig] the way `uiLanguage`/`skin`/etc. have one.
    @SerialName("request_language") val requestLanguage: String? = null,
    @SerialName("request_language_writer") val requestLanguageWriter: String? = null,
)

/** One resolved behaviour field for the config-editor UI: the effective [value] plus where it came
 *  from — `"global"` (no override), `"admin"` (admin-set per-user override), or `"viewer"` (set by
 *  the viewer on their own TV — reset-only in the editor). */
@Serializable
data class ResolvedBehaviourField<T>(val value: T, val source: String)

@Serializable
data class ResolvedBehaviour(
    @SerialName("ui_language") val uiLanguage: ResolvedBehaviourField<String>,
    val skin: ResolvedBehaviourField<Skin>,
    @SerialName("tile_shape") val tileShape: ResolvedBehaviourField<TileShape>,
    @SerialName("show_continue_progress") val showContinueProgress: ResolvedBehaviourField<Boolean>,
    @SerialName("autoplay_next") val autoplayNext: ResolvedBehaviourField<Boolean>,
    // Phase 139 — "global" here means "the catalog's default-flagged intent" (or the kids-default
    // intent for a kids device), resolved server-side since only the backend has the AppConfig catalog.
    @SerialName("request_language") val requestLanguage: ResolvedBehaviourField<String>,
)

/** R159 — portrait-only display overrides. Each field null = that override is off; the block itself
 *  being null means no portrait overrides at all (both are equivalent, but `heroHeightPct == null`
 *  lets the section keep the toggle's on/off state independent of future sibling fields). */
@Serializable
data class PortraitConfig(
    @SerialName("hero_height_pct") val heroHeightPct: Int? = null, // % of screen the hero fills in portrait (20..100)
    // R174 — items per row in the poster grids when the viewport is portrait. null = the built-in
    // portrait default (2, far fewer than the landscape count). Clamped 1..4 server-side.
    @SerialName("grid_columns") val gridColumns: Int? = null,
)

/**
 * R48/Phase 137 — per-user Request (formerly Discover/Top-10) selection (server-owned, synced).
 * `canRequest` gates whether a non-admin may place a request (admins always may). `feeds` are the
 * ordered Seerr discover feeds shown on this user's Request tab (Phase 136 retired the chart backend
 * this used to select from — `lists`/`source`/`sources`/`region` are gone, replaced by `feeds`).
 */
@Serializable
data class DiscoverConfig(
    val enabled: Boolean = false,
    @SerialName("can_request") val canRequest: Boolean = false,
    val feeds: List<SeerrFeed> = emptyList(),
)

/** Phase 137 — one configured Seerr discover feed row (order + visibility live in [DiscoverConfig.feeds]). */
@Serializable
data class SeerrFeed(
    val id: String,
    val kind: SeerrFeedKind,
    val endpoint: SeerrDiscoverEndpoint,
    val param: String? = null,   // genre id / studio id / network id / ISO-639-1 language code
    val name: String,
    val visible: Boolean = true,
)

enum class SeerrFeedKind { MOVIE, TV, MIXED }

/** Phase 137 — the Seerr discover-endpoint catalogue the "+ Add row" popover offers. [needsParam] is
 *  the single source of truth for which endpoints require a value (genre/studio/network id, or an
 *  ISO-639-1 language code) — both the admin editor's add-row UI and RaviloConfigService.validate()
 *  read it, so they can never drift apart on which endpoints are parameterised. */
enum class SeerrDiscoverEndpoint(val needsParam: Boolean) {
    MOVIES_POPULAR(false), MOVIES_GENRE(true), MOVIES_LANGUAGE(true), MOVIES_STUDIO(true), MOVIES_UPCOMING(false),
    TV_POPULAR(false), TV_GENRE(true), TV_LANGUAGE(true), TV_NETWORK(true), TV_UPCOMING(false),
    TRENDING(false),
}

/** One choice in the add-row popover's genre/studio/network picker — `logoPath` is a TMDB `/t/p/...`
 *  path (null for genres, which have no logo) so the admin editor can show a recognizable brand mark
 *  instead of a bare id. */
@Serializable
data class PickerOption(
    val id: Int,
    val name: String,
    val logoPath: String? = null,
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

/** R56 — Re-request a stream ticket with a subtitle burned in (encode / PGS path). */
@Serializable
data class PlaybackRestreamRequest(
    @SerialName("item_id") val itemId: String,
    @SerialName("subtitle_stream_index") val subtitleStreamIndex: Int,
    @SerialName("position_ms") val positionMs: Long = 0,
)

/** On-device viewer-tweakable settings (PUT /api/tv/settings). All fields optional = unchanged. */
@Serializable
data class ViewerSettingsRequest(
    val skin: Skin? = null,
    @SerialName("show_continue_progress") val showContinueProgress: Boolean? = null,
    @SerialName("autoplay_next") val autoplayNext: Boolean? = null,
    @SerialName("tile_shape") val tileShape: TileShape? = null,
    // R162: joins the other four in the field-level behaviour overlay (R161 will be the first UI to
    // actually send it — the field/plumbing lands now so that phase is a pure UI change).
    @SerialName("ui_language") val uiLanguage: String? = null,
    // Phase 139 — admin-editor-only for now (no on-TV Settings control yet); same overlay mechanism.
    @SerialName("request_language") val requestLanguage: String? = null,
)

@Serializable
data class MarkRequest(
    @SerialName("item_id") val itemId: String,
    val watched: Boolean,
)

/**
 * R142 — played/unplayed write-through. [itemId] is a movie / episode / series jellyfin id; for a series
 * the server fans the flag out to every child episode. [episodeIds] (optional) targets a specific set —
 * a season's episodes (Mark all played) — instead of letting the server derive them.
 */
@Serializable
data class PlayedRequest(
    @SerialName("item_id") val itemId: String,
    val played: Boolean,
    @SerialName("episode_ids") val episodeIds: List<String> = emptyList(),
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
}
