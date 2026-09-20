package dev.jellystructure.shared.tv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ─── Enums ────────────────────────────────────────────────────────────────────

enum class MediaKind { MOVIE, SERIES, MUSIC_VIDEO }
enum class RowKind { CONTINUE, NEWLY_ADDED, GENRE, CUSTOM }
enum class ChannelStyle { LOGO, TEXT }
enum class TileShape { POSTER, LANDSCAPE, SQUARE }
enum class Skin { AURORA, MIDNIGHT, NOIR }
/** Join mode for a condition stack (R32 workbench). */
enum class MatchMode { ALL, ANY }

/** Operator-set content size for the TV grids/rows. COMFORTABLE = current sizing. */
enum class UiDensity { COMPACT, COZY, COMFORTABLE }

/** R182 — Skip Intro / Skip Credits behaviour. PROMPT = pill/card shown, viewer presses OK to skip;
 *  AUTO = auto-skips when its countdown elapses (still cancellable); OFF = never shown. */
enum class SkipMode { OFF, PROMPT, AUTO }

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
    /**
     * Phase 253 (FR-253-3) — this client plays **HEVC in fMP4 HLS**. The transcode profile is
     * h264/ts unless a client says this, so an `hls_only` client that lists `hevc` still had every
     * HEVC title re-encoded to h264. Opt-in, default false: a wrong yes is a black screen on a title
     * that plays today, so only a client that has verified it (or probed it) sets this.
     */
    @SerialName("hls_hevc") val hlsHevc: Boolean = false,
    // Bug fix: an HDR10/HDR10+ (PQ) or HLG source used to always direct-play regardless of whether
    // the device could actually display it correctly — Jellyfin's DeviceProfile declared no VideoRange
    // constraint at all, so it never had a reason to tone-map-transcode to SDR. These default to
    // `false` (conservative: assume SDR-only, which forces a correctly tone-mapped transcode) unless
    // the client has verified real display/decoder support (see ravilo-ui's `detectHdrSupport()`).
    @SerialName("supports_hdr10") val supportsHdr10: Boolean = false,
    @SerialName("supports_hlg") val supportsHlg: Boolean = false,
    // R183: Dolby Vision. `supportsDolbyVision` = a real DV decoder (needed for profile 5, which has no
    // HDR10 base layer); `supportsDolbyVisionEl` = dual-layer DV (profile 7's enhancement layer, which
    // additionally needs multi-instance HEVC decode). A DV **profile 8** file carries an HDR10/HDR10+/HLG/
    // SDR base layer, so it plays correctly on any decoder that handles that base range even without DV —
    // which is why `supportsHdr10` alone unlocks it (see `deviceProfile()`), exactly as Jellyfin's own
    // Android TV client decides it.
    @SerialName("supports_dolby_vision") val supportsDolbyVision: Boolean = false,
    @SerialName("supports_dolby_vision_el") val supportsDolbyVisionEl: Boolean = false,
    // R183: the client's real H.264 decode ceiling, used to declare an honest transcode target. Jellyfin
    // advertises the HLS variant's `CODECS`/`RESOLUTION` from what the profile claims — with nothing
    // declared it defaults to **Baseline level 4.1** while still targeting the source's full 4K, and
    // ExoPlayer rejects that variant outright (no decoder accepts 3840x1606 Baseline-L4.1), so playback
    // failed before the first frame. 0 = unknown → the server falls back to a universally-decodable
    // 1080p High/L5.1 declaration.
    @SerialName("max_h264_width") val maxH264Width: Int = 0,
    @SerialName("max_h264_height") val maxH264Height: Int = 0,
    /** H.264 level ×10, Jellyfin's own encoding (e.g. `51` = level 5.1). */
    @SerialName("max_h264_level") val maxH264Level: Int = 0,
    /** Phase 161 / R209 — can this client render an embedded text-or-PGS subtitle natively from the
     *  container on a direct-played file, without the server sideloading it as an extracted VTT / burning
     *  it in via transcode? False (the pre-existing behavior) unless a client actively confirms it — see
     *  `PlaybackService.buildSubtracks()`'s `embedContainerSubs` doc for why this must stay conservative. */
    @SerialName("supports_embedded_text_subs") val supportsEmbeddedTextSubs: Boolean = false,
    // R216/Phase 177 — the client's real video-decode bitrate ceilings (bits/s), from
    // ravilo-ui's detectDecoderLimits() (generalised from the H.264-only detectAvcDecoderLimits()).
    // 0 = unknown — Phase 177's per-codec `VideoBitrate` condition is then never emitted (never invent
    // a ceiling; see JellyfinClient.deviceProfile()'s FR-177-2 doc). Investigation:
    // stue-tv-4k-playback-stutter-2026-08-28.md found the TV's decoders both cap at 60 Mbps while the
    // server never asked, letting a 93 Mbps remux direct-play into them unchanged for two hours.
    @SerialName("max_video_bitrate") val maxVideoBitrate: Int = 0,
    @SerialName("max_hevc_bitrate") val maxHevcBitrate: Int = 0,
    @SerialName("max_h264_bitrate") val maxH264Bitrate: Int = 0,
    // R216/Phase 177 — this device's own network link, sampled once at startPlayback (detectLinkState()).
    // "unknown"/0 ⇒ Phase 177's link-derived MaxStreamingBitrate cap never applies (today's behaviour).
    @SerialName("link_kind") val linkKind: String = "unknown",
    @SerialName("link_mbps") val linkMbps: Int = 0,
)

/**
 * R216/Phase 177 (FR-R216-4/FR-177-5) — one playback-quality report, posted by the client at session end
 * (and on a long-session interval) to `POST /api/tv/playback/qoe`. `deviceId`/`playSessionId` are NOT
 * carried here — the server derives them from the authenticated device + [itemId] (the same
 * `playSessionIdFor()` every other playback call already uses), so a compromised/spoofed report can never
 * claim to be a different device. Fire-and-forget on the client: a failed POST must never affect
 * playback (see the phase's invariant) and is simply dropped, not retried.
 */
@Serializable
data class PlaybackQoeReport(
    @SerialName("item_id") val itemId: String,
    @SerialName("dropped_frames") val droppedFrames: Int = 0,
    @SerialName("rebuffer_count") val rebufferCount: Int = 0,
    @SerialName("rebuffer_ms") val rebufferMs: Long = 0,
    @SerialName("bandwidth_estimate_bps") val bandwidthEstimateBps: Long? = null,
    @SerialName("video_decoder") val videoDecoder: String? = null,
    @SerialName("direct_play") val directPlay: Boolean = false,
    @SerialName("link_kind") val linkKind: String = "unknown",
    @SerialName("link_mbps") val linkMbps: Int = 0,
    /** Phase 179 (FR-179-3) — count of sideloaded text-subtitle load errors this session (e.g. the
     *  `.../Subtitles/{index}/...` sideload racing Jellyfin's own concurrent extraction — see
     *  phase-179's Root cause). Diagnostic only, so a repeat doesn't need another live logcat pull. */
    @SerialName("subtitle_load_errors") val subtitleLoadErrors: Int = 0,
    /** R237 (FR-R237-6) — the HTTP status that ended a start attempt, set ONLY when the session never
     *  reached Ready. Null on every ordinary report. Makes a failed start readable from `playback_qoe`
     *  alone instead of by hand-correlating log lines against row timestamps. */
    @SerialName("start_failure_status") val startFailureStatus: Int? = null,
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
    /**
     * R271 (FR-R271-4) — **always the empty string.** The credential is gone; the field is not.
     *
     * It used to carry a raw Jellyfin access token to every client, and after FR-R271-2 it had
     * exactly zero consumers: its only reader in the whole product was `PlayerScreen`'s dead
     * URL-templating fallback. A credential that travels to a client for no reason is a credential
     * that can leak for no reason — so nothing populates it any more.
     *
     * ⚠ **But removing the field outright broke the household TV, and this is why it is still here.**
     * Deleting it from the DTO looked free — no reader left — and it is not: a client built before
     * the change declares `access_token` as **required**, so it cannot deserialize a ticket from a
     * server that stops sending one. Shipped in v1.31 and caught the same morning on the stue TV
     * (running v1.27): the backend negotiated `PlaybackInfo` fine, five times, and the TV showed
     * *"Couldn't reach the server"* — a client-side `MissingFieldException` wearing R237's
     * unreachable copy. Re-signing was not an escape either, since the installed build predates the
     * real upload keystore, so an update would have meant uninstall + re-pair.
     *
     * **No default, deliberately**: the server's `Json` has `encodeDefaults = false`, so a property
     * equal to its default is omitted from the wire — which is exactly the breakage again. It must be
     * present and empty, not absent. (Same trap as phase 238's `never_attempted`.)
     *
     * Removing it for real is a future phase, gated on every installed client being past v1.31 — not
     * on there being no reader, which was never the binding constraint.
     *
     * Not to be confused with Live TV's own `access_token` (`LiveTvModels.kt`), a different field on
     * a different model that this phase did not survey — it stays.
     */
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
    /**
     * Phase 252 (FR-252-1) — Jellyfin's stream index of the subtitle **encoded into this stream's
     * video**, null when none. Only a burn-in restream sets it. Without it a client cannot know a
     * subtitle is already in the picture, and leaves its own text track rendering on top — the
     * "two subtitles at once" report. Additive with a default on purpose: an older client ignores it,
     * and `encodeDefaults = false` keeps a null off the wire entirely (contrast [accessToken]).
     */
    @SerialName("burned_subtitle_index") val burnedSubtitleIndex: Int? = null,
    /**
     * Phase 253 (FR-253-2) — on a TRANSCODE, Jellyfin's index of the one audio track this stream
     * carries (matches an [AudioTrack.index] in [audio]); a fact read back from Jellyfin's own URL,
     * never an echo of the request. Null on direct play, where the container carries every track and
     * the player selects. It is how a picker shows — and changes — the audio of an HLS session.
     */
    @SerialName("audio_stream_index") val audioStreamIndex: Int? = null,
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
    /** Phase 155 — normalized age 0-18 (CertificationResolver.normalizedAge()); never null, defaults to
     *  18 for an unmapped or uncertified title. A gate value for filtering/kids-gating, not a label —
     *  never render this as "18+" on its own; [rating] is still what R153's regional chip displays. */
    @SerialName("age_rating") val ageRating: Int = 18,
    /** Phase 202 (FR-202-5) — R240's L/J facts, populated only on Home content-row items and only when
     *  the household's resolved [RaviloConfig.focusDetail] isn't "none". Null everywhere else (hero,
     *  channel rail, browse, search, related) — the same additive-field discipline [BrowseCard.genres]/
     *  R164's IMDb-rating precedent already set for this card. */
    @SerialName("focus_detail") val focusDetail: FocusDetailFacts? = null,
)

/**
 * Phase 202 (FR-202-5) — the per-title fact set R240's L (foot status line) and J (row-opens) directions
 * render, carried on a Home content-row [MediaCard]. Facts only, no presentation: the client formats
 * these against its own en/da/fo string table (R240 FR-R240-11) rather than being shipped prose.
 * "Where you left off" rides [MediaCard]'s own [MediaCard.progressPct]/[MediaCard.nextUpLabel] — not
 * duplicated here. Deliberately **no artwork of any kind** — dropping round 1's overlay directions took
 * the backdrop/logo fetch back out of this payload (see the phase 202 spec's Current-state note).
 */
@Serializable
data class FocusDetailFacts(
    val year: Int? = null,
    /** Format/quality label, e.g. "4K HDR" / "1080p" (same resolution as [BrowseCard.quality]). */
    val badge: String? = null,
    /** Series only — distinct season count. */
    val seasons: Int? = null,
    /** Series only — total episode count across all seasons. */
    val episodes: Int? = null,
    /** Movie only — null for a series (seasons/episodes stand in for it on the strip). */
    @SerialName("runtime_minutes") val runtimeMinutes: Int? = null,
    @SerialName("rating_badge") val ratingBadge: RatingBadge? = null,
    @SerialName("imdb_rating") val imdbRating: TvImdbRating? = null,
    /** R221 — every genre, in TMDB's own order (the first is primary). */
    val genres: List<String> = emptyList(),
    @SerialName("audio_languages") val audioLanguages: List<String> = emptyList(),
    @SerialName("subtitle_languages") val subtitleLanguages: List<String> = emptyList(),
    val overview: String? = null,
)

/**
 * R187 — one browse-page grid tile: the same [card] every other surface uses, plus the extra per-item
 * facets the browse page's facet bar needs (genre list, audio languages, quality, channel membership).
 * Deliberately NOT folded into [MediaCard] itself — every other card-consuming surface (Home rows,
 * search, Continue Watching) would pay for fields it never uses; R164's decision to keep IMDb rating off
 * MediaCard for the same reason is the precedent this follows. The browse page fetches the FULL
 * seed-matching set in one call ([SeededBrowseResponse] — this project's catalog is a few hundred items,
 * confirmed cheap at that scale) and does all facet counting/filtering/sorting client-side from it, so
 * no further round trip is needed as the viewer toggles facets.
 */
@Serializable
data class BrowseCard(
    val card: MediaCard,
    val genres: List<String> = emptyList(),
    @SerialName("audio_languages") val audioLanguages: List<String> = emptyList(),
    /** e.g. "4K HDR" / "1080p" / "720p" / "SD". Null when the item has no probed video track yet. */
    val quality: String? = null,
    /** Ravilo channel ids (`ChannelConfig.id`) this item currently belongs to. */
    val channels: List<String> = emptyList(),
    /** For client-side sort-by-IMDb only (FR-RV-BROWSE1-7) — deliberately NOT added to [MediaCard]
     *  itself, per R164's existing payload-bloat decision to keep it detail-DTO-only; [BrowseCard] is
     *  its own additive wrapper, so this doesn't reopen that decision for every other card surface. */
    @SerialName("imdb_rating") val imdbRating: TvImdbRating? = null,
    /** R253 (FR-R253-3) — Jellyfin's `SortName` (225 FR-225-3); null until the item's next scan. Browse-only, like [imdbRating]. */
    @SerialName("sort_name") val sortName: String? = null,
)

/** Request body for the seeded-browse endpoints — the row's (channel-ANDed) [Row.seedQuery] plus the
 *  page's own [Row.seedMediaKind], re-submitted verbatim by the client. Null query = no additional
 *  filter (the Movies/Series nav case — media kind only). */
@Serializable
data class SeededBrowseRequest(
    val query: ConditionGroup? = null,
    @SerialName("media_kind") val mediaKind: String? = null,
)

@Serializable
data class SeededBrowseResponse(
    val items: List<BrowseCard>,
    val total: Int,
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
    /** Phase 232 (FR-232-6) — `"light"` | `"dark"`: the ink the clearlogo is drawn in; absent = not judged yet. */
    @SerialName("logo_ink") val logoInk: String? = null,
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
    /** R187 — this row's resolvable seed for a "→ See all" browse page: the same condition tree
     *  (already ANDed with the channel's own query when this row is channel-scoped) the server used to
     *  build [items], re-submittable to the seeded-browse endpoint to get the FULL matching set with
     *  facets. Null for CONTINUE (not condition-tree-representable — needs its own dedicated resolution
     *  path, see the R187 spec's §G-4) and whenever a row was built by legacy code that hasn't been
     *  updated to populate it. [seedMediaKind] ("MOVIE"/"SERIES"/null) is a separate, non-tree filter —
     *  mirrors [RowConfig.mediaKind] / `BrowseService.browse`'s own `kind` param. Whether to actually
     *  show a "→ See all" tile is a client decision: [items] is already capped at 30 and never
     *  truncates below the row's true count when that count is <= 30, so `items.size > 8` is exactly
     *  equivalent to "the full seed has more than 8 items" — no separate total-count field is needed. */
    val seedQuery: ConditionGroup? = null,
    val seedMediaKind: String? = null,
    /** Phase 225 (FR-225-8) — the key [items] is ordered by, so R253's See-all page can open in the same
     *  order with no config round trip. Set on workbench rows with an explicit order; the PIN LIST IS
     *  NEVER SENT (the viewer sees an order, never a reason). */
    @SerialName("sort_by") val sortBy: String? = null,
    @SerialName("sort_descending") val sortDescending: Boolean? = null,
    /** R187 — the seed's TRUE match count, before the [items] cap. [items].size alone undercounts once
     *  a row is actually truncated (ROW_ITEM_LIMIT), which is exactly when a See-all count needs to be
     *  right. Null alongside a null [seedQuery]/[seedMediaKind] (nothing to count beyond [items]). */
    val seedTotalCount: Int? = null,
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
    // Phase 147/R177 — placement for the Home "On now" row / Live TV collection; null = not configured
    // (Live TV disabled or never placed) — the client renders no Live TV surface at all in that case.
    @SerialName("live_tv_home") val liveTvHome: LiveTvHomePlacement? = null,
    // Phase 202 (FR-202-1/2) — the household's resolved focus-detail mode + delay, mirrored from
    // [RaviloConfig] onto this feed so a client never needs a second round trip to `/api/tv/config`
    // before it can render the first focused tile (FR-202-7). "none"/170 default matches
    // [RaviloConfig]'s own defaults for every pre-202 test/mock HomeFeed construction.
    @SerialName("focus_detail") val focusDetail: String = "none",
    @SerialName("focus_detail_delay_ms") val focusDetailDelayMs: Int = 170,
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
    // Bug fix — "My List": carried on the same playstate overlay as `played` (same Jellyfin UserData
    // bulk fetch, no extra round trip) so the detail screens' "+ My List" button has real state.
    val favorite: Boolean = false,
)

/** Phase 150: the client-facing mirror of `dev.jellystructure.model.Stinger` — a mid/post-credits
 *  scene TMDB flags. Its mere presence tells the player to never auto-skip past it (R182). */
@Serializable
data class TvStinger(
    @SerialName("at_ms") val atMs: Long? = null,
    val kind: String,
)

/** Phase 150: the client-facing mirror of `dev.jellystructure.model.SegmentMarkers` — where this
 *  title's intro/credits segments start, so Ravilo (R182) can offer Skip Intro / Skip Credits instead
 *  of a fixed end-of-file guess. `source`/`confidence` are read-only display info for a future admin
 *  surface; the player only needs the timestamps + [stinger]. */
@Serializable
data class TvSegmentMarkers(
    @SerialName("intro_start_ms") val introStartMs: Long? = null,
    @SerialName("intro_end_ms") val introEndMs: Long? = null,
    @SerialName("credits_start_ms") val creditsStartMs: Long? = null,
    val stinger: TvStinger? = null,
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
    /**
     * Phase 149: the shared physical file this episode lives in — episodes sharing this value are a
     * "multi-episode file" (e.g. `S01E01E02E03.mkv`); the client groups by it to render one combined
     * card instead of N. Never parsed by the client (constitution: renders server-pushed state only).
     */
    val file: String = "",
    /** Phase 149: 0-based position within [file]'s group, ordered by episode number. */
    @SerialName("part_index") val partIndex: Int = 0,
    /** Phase 149: size of [file]'s group (1 for a normal single-episode file). */
    @SerialName("part_count") val partCount: Int = 1,
    /** Phase 149: this episode's start offset (ms) within [file], when the container's chapter count
     *  matched the contained-episode count. Null when the file has no usable chapters. */
    @SerialName("chapter_start_ms") val chapterStartMs: Long? = null,
    /** Phase 149: true only when every episode in [file]'s group got a chapter offset. */
    @SerialName("has_chapters") val hasChapters: Boolean = false,
    /** Phase 150: this episode's own intro/credits segments (R182 Skip Intro / Skip Credits). */
    val segments: TvSegmentMarkers = TvSegmentMarkers(),
    /** R222 (Phase 185 FR-185-9): resolved server-side, per file, for the requesting device. Null ⇒
     *  render nothing (FR-R222-1) — never derive, never re-check client-side. A Phase 149 combined file
     *  carries this on every episode in the group, but the value is identical across them (one file, one
     *  note) — R222's client groups them into one card and shows it once, not per episode. */
    @SerialName("playback_note") val playbackNote: PlaybackNote? = null,
)

/**
 * Phase 185 (FR-185-5) — the whole resolved verdict for one (device, file): whether tonight's playback
 * will be slow to start here, and how sure the server is. R222 renders the two sentences this maps to
 * and nothing else — no bitrate, ceiling, margin or delivery method ever crosses to the client (R180
 * FR-RV-ASP1-2, and the constitution's frontend-renders-server-pushed-state-only rule).
 */
@Serializable
data class PlaybackNote(
    /** The user-set device name this note is FOR (e.g. "Bedroom TV") — the server owns this string too,
     *  so a model name never has a path to a viewer (FR-R222-3). */
    val device: String,
    /** `"measured"` (≥3 start samples for this device+file — [seconds] is their median) or `"expected"`
     *  (the predicate fired but there's no history yet — no [seconds]). */
    val basis: String,
    val seconds: Int? = null,
)

@Serializable
data class Season(
    val index: Int,
    val name: String,
    val episodes: List<Episode>,
    /** R194 — this season's own poster URL. The client 404s through to the series' own [MediaCard.posterUrl]
     *  when this season has none on disk (never guaranteed non-null/non-404, unlike other image URLs here). */
    @SerialName("poster_url") val posterUrl: String? = null,
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
    /** Phase 232 (FR-232-6) — `"light"` | `"dark"`: the ink the clearlogo is drawn in; absent = not judged yet. */
    @SerialName("logo_ink") val logoInk: String? = null,
    /** Phase 106: server-resolved age-rating badge (R153 renders it). Null = no certification data. */
    @SerialName("rating_badge") val ratingBadge: RatingBadge? = null,
    /** Phase 130: null when TMDB has no usable trailer. R163 renders the Play-Trailer button. */
    val trailer: TvTrailer? = null,
    /** Phase 131: null when the title has no IMDb rating yet. R164 renders it. */
    @SerialName("imdb_rating") val imdbRating: TvImdbRating? = null,
    /** R181 — the title's own original-audio language (ISO code), for the player's "Dubbed" badge on
     *  audio tracks in a different language. Null when unknown. */
    @SerialName("original_language") val originalLanguage: String? = null,
    /** Phase 150: this movie's own intro/credits segments (R182 Skip Intro / Skip Credits). */
    val segments: TvSegmentMarkers = TvSegmentMarkers(),
    /** R221 — every genre, in TMDB's own order (the first is "primary" — [MediaCard.genre] is already
     *  `genres.firstOrNull()`, this is the rest). Deliberately on the detail DTO, not [MediaCard] itself —
     *  same reasoning as [BrowseCard.genres]/R164's IMDb-rating precedent: every other card-consuming
     *  surface (Home rows, search, Continue Watching) would pay for a field only the detail page renders. */
    val genres: List<String> = emptyList(),
    /** R222 (Phase 185 FR-185-5): resolved server-side, for the requesting device, from this movie's own
     *  file. Null ⇒ render nothing. See [Episode.playbackNote]'s doc — same field, same contract; a
     *  series carries it per-episode instead of here (FR-R222-5: a ceiling is per device, a bitrate is
     *  per file, and a series hero has no single file to speak for). */
    @SerialName("playback_note") val playbackNote: PlaybackNote? = null,
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
    /** Phase 232 (FR-232-6) — `"light"` | `"dark"`: the ink the clearlogo is drawn in; absent = not judged yet. */
    @SerialName("logo_ink") val logoInk: String? = null,
    /** R149: next scheduled unaired episode from Sonarr. Null = Sonarr off / ended / no date. */
    @SerialName("next_airing") val nextAiring: NextAiring? = null,
    /** Phase 106: server-resolved age-rating badge (R153 renders it). Null = no certification data. */
    @SerialName("rating_badge") val ratingBadge: RatingBadge? = null,
    /** Phase 130: null when TMDB has no usable trailer (series-level only). R163 renders the button. */
    val trailer: TvTrailer? = null,
    /** Phase 131: null when the title has no IMDb rating yet (the show-level rating). R164 renders it. */
    @SerialName("imdb_rating") val imdbRating: TvImdbRating? = null,
    /** R181 — the series' own original-audio language (ISO code), for the player's "Dubbed" badge on
     *  audio tracks in a different language. Null when unknown. */
    @SerialName("original_language") val originalLanguage: String? = null,
    /** R221 — every genre, in TMDB's own order. See [MovieDetail.genres]'s doc for why this lives here
     *  and not on [MediaCard]. */
    val genres: List<String> = emptyList(),
)

// ─── Search ───────────────────────────────────────────────────────────────────

@Serializable
data class SearchResults(
    val query: String,
    val items: List<MediaCard>,
    // Bug fix: total matching count, independent of how many `items` this particular page carries --
    // Browse's grid paginates now (R118 follow-up) and needs the true total for its "N titles" count,
    // not just the current page's size. Defaults to items.size for every existing non-paginated caller
    // (search()'s suggestions/results, which already return the full matching set in one response).
    val total: Int = items.size,
)

// ─── Live events (R33) ──────────────────────────────────────────────────────────

/**
 * Pushed over the `/api/tv/events` WebSocket to a user's connected devices. `type` is `config_changed`
 * (R33) or `home_changed` (R248 — a stop or played/mark write was folded into the Home feed); `rev` is
 * a per-type monotonic counter the client uses to dedupe/skip redundant refreshes. The event is a
 * signal only — the client re-pulls the authoritative feed/config.
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
    // Phase 236 (FR-236-4) — which of a shared screen's own tokens to play this under; absent on a
    // single-session device (today's TVs), which keeps its one and only behaviour unchanged.
    @SerialName("session_user_id") val sessionUserId: String? = null,
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

/** Phase 236 (FR-236-3) — everything past the original stop/pause/unpause/home quartet: seek, skip,
 *  next/previous, track selection, subtitle size, next-up, segment skip, volume. [args] is the
 *  command's own field object; a device honouring only some commands simply ignores the rest and
 *  answers via its own next status report (FR-236-11's rule), never silently. */
@Serializable
data class PlayerCommandEnvelope(
    val type: String = "",
    val command: String = "",
    val args: JsonObject? = null,
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
    // R143 — per-channel system rows (Continue Watching, Newly Added). `show`/`merge` only consulted in
    // custom mode; in "inherit" mode the channel keeps inheriting Home's system rows, scoped to the
    // channel per R233 (a system row always shows what is available on the page it's rendered on — not
    // configurable, in either row-list mode; R59's own inherit/custom meaning is otherwise untouched).
    val system: ChannelSystemRows = ChannelSystemRows(),
)

/**
 * R143 — per-channel system-row settings. Both rows shown by default. R233 retired `scope`: a system
 * row is **always** scoped to the channel it's rendered on — there is no supported way to put a
 * library-wide system row on a collection page (the field was a setting that should never have
 * existed, not a currently-misused one — no live config ever set it). `show`/`merge` are unaffected.
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
)

@Serializable
data class SystemNewly(
    val show: Boolean = true,
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

// ─── Phase 187 — a viewer's own photo + password ──────────────────────────────

/** Upload payload for a viewer's own account photo: base64 image bytes (data-URL prefix tolerated) +
 *  the browser/client-reported content type. The server validates/re-encodes before it ever reaches
 *  Jellyfin (FR-187-6) — this is what the client actually captured, not what gets forwarded. */
@Serializable
data class AccountPhotoUpload(
    @SerialName("data_base64") val dataBase64: String,
    @SerialName("content_type") val contentType: String,
)

/** Response to a photo set/remove: the new avatar URL to repaint everywhere immediately (FR-R234-8) —
 *  already carries Jellyfin's fresh PrimaryImageTag as its cache-busting `?v=`, or is null after a
 *  remove (client falls back to initials, same as any other viewer with no photo). */
@Serializable
data class AccountPhotoResult(
    @SerialName("avatar_url") val avatarUrl: String?,
)

@Serializable
data class AccountPasswordChangeRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

/** FR-187-8 — the route states what actually happened to the caller's own session rather than the
 *  client guessing; [tokenSurvived] is measured per-call (see [dev.jellystructure.auth.JellyfinClient]'s
 *  own doc), never hard-coded to a fixed answer. [wrongCurrentPassword] is the one error the client
 *  renders as its own sentence (FR-187-3/R234 FR-R234-5) rather than a generic failure. */
@Serializable
data class AccountPasswordResult(
    val ok: Boolean,
    @SerialName("wrong_current_password") val wrongCurrentPassword: Boolean = false,
    @SerialName("token_survived") val tokenSurvived: Boolean = true,
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
    // Phase 225 — how this row lines up. ALL THREE absent = today's row, tile for tile (newest first,
    // 30 titles): that is the migration. Ignored by every installed client (the TV never evaluates rows).
    /** null = `RowSort("added", descending = true)` — today's order. */
    val sort: RowSort? = null,
    /** Ordered Jellyfin item ids shown FIRST, in this order, when they match the row (FR-225-4). A stale
     *  id is kept here and skipped when serving (FR-225-5) — the server never edits this list. */
    val pinned: List<String> = emptyList(),
    /** Titles the row shows, 3..30; null = 30 (FR-225-1b). Also the ceiling on [pinned]. */
    val limit: Int? = null,
)

/** Phase 225 — [by] ∈ `added` · `title` · `year`. [descending] = newest first for added/year, Z → A for title. */
@Serializable
data class RowSort(val by: String = "added", val descending: Boolean = true)

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
    // R182 — Skip Intro / Skip Credits, global defaults (per-user overrides via BehaviourOverlay).
    @SerialName("skip_intro") val skipIntro: SkipMode = SkipMode.PROMPT,
    @SerialName("skip_credits") val skipCredits: SkipMode = SkipMode.PROMPT,
    @SerialName("skip_secs") val skipSecs: Int = 6, // 4/6/8
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
    // Phase 147/148 — where Live TV surfaces on Home (placement only; enabling Live TV itself is the
    // Live TV page's job). Null = not configured — the Layout tab's "Live TV on Home" section only
    // renders once Live TV is enabled there, and defaults apply from that point on.
    @SerialName("live_tv_home") val liveTvHome: LiveTvHomePlacement? = null,
    // Phase 202 — R240's L (foot line) / J (row opens in place). Both booleans stay admin-side state so
    // the config screen can say "On · superseded" (FR-202-6) rather than silently disagreeing with the
    // screen; a client never reads these two directly — see [focusDetail] below. J defaulted to off
    // pending the reflow-cost measurement invariant 11 asked for; that measurement closed 2026-09-13
    // (R240 spec, "the systematic sweep-and-trace invariant 11 asked for, run to closure" — 46 real
    // settled opens on the stue TV, 2.8-3.2% janky frames, 0 missed vsync), so J now defaults on too.
    @SerialName("focus_detail_line") val focusDetailLine: Boolean = true,
    @SerialName("focus_detail_row_open") val focusDetailRowOpen: Boolean = true,
    // FR-202-3 — any non-negative ms value is valid (0 = immediate, no upper bound); RaviloConfigService
    // .normalize() is what resolves a negative/hand-edited value back to the 170 default on save.
    @SerialName("focus_detail_delay_ms") val focusDetailDelayMs: Int = 170,
    // FR-202-2 — the server resolves the mode; no client anywhere re-implements this precedence rule.
    // Deliberately a real (settable) field rather than a getter-only computed property — kotlinx.
    // serialization only serializes properties with a backing field — but callers never set it
    // directly: [resolvedFocusDetail] is the single source of truth and every read path
    // (RaviloConfigService.getConfig/getGlobalConfig, HomeFeedService) overwrites this field with it
    // before the config leaves the server, the same way [viewerSkinOverride] gets overwritten with the
    // resolved behaviour overlay in RaviloConfigService.getConfig.
    @SerialName("focus_detail") val focusDetail: String = "line",
    // Phase 218 (FR-218-3/11) — server-resolved, never stored: present ONLY when the admin has enabled
    // Chromecast and set an application id. Absent means "there is nothing to cast to" and Ravilo draws
    // NO cast button — not a greyed one (R245 FR-R245-1). Same shape as [focusDetail]: one resolved
    // field, overwritten by RaviloConfigService on every read path before the config leaves the server.
    val cast: CastCapability? = null,
    // Phase 236 (dev review item 9) — server-pushed like [cast], so R265's Cast-glyph sheet can draw
    // itself without a request per Home. [ScreensCapability.paired] is this viewer's own device list,
    // never the whole household's — a device token is per (device, user) throughout this codebase.
    val screens: ScreensCapability? = null,
) {
    /** The skin actually rendered: the viewer's override when allowed, else the operator default. */
    fun effectiveSkin(): Skin = if (allowSkinOverride) (viewerSkinOverride ?: defaultSkin) else defaultSkin

    /** FR-202-2 — `rowOpen` wins whenever it's on, regardless of [focusDetailLine]. Recompute this
     *  (never trust a stored/round-tripped [focusDetail] value) before a config leaves the server. */
    fun resolvedFocusDetail(): String = when {
        focusDetailRowOpen -> "rowOpen"
        focusDetailLine -> "line"
        else -> "none"
    }
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
    // R182 — Skip Intro / Skip Credits + countdown length, admin-editor-only (no on-TV Settings control).
    @SerialName("skip_intro") val skipIntro: SkipMode? = null,
    @SerialName("skip_intro_writer") val skipIntroWriter: String? = null,
    @SerialName("skip_credits") val skipCredits: SkipMode? = null,
    @SerialName("skip_credits_writer") val skipCreditsWriter: String? = null,
    @SerialName("skip_secs") val skipSecs: Int? = null,
    @SerialName("skip_secs_writer") val skipSecsWriter: String? = null,
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
    @SerialName("skip_intro") val skipIntro: ResolvedBehaviourField<SkipMode>,
    @SerialName("skip_credits") val skipCredits: ResolvedBehaviourField<SkipMode>,
    @SerialName("skip_secs") val skipSecs: ResolvedBehaviourField<Int>,
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
    /** Phase 185 (FR-185-4) — negotiation-to-first-frame, as the client itself measured it for THIS
     *  session. Null whenever the client can't honestly report one: first frame never rendered (an
     *  abandoned/failed start), or the session ended some other way the client didn't instrument. Never
     *  guessed or backfilled server-side — an absent value here simply means no sample is recorded. */
    @SerialName("startup_ms") val startupMs: Long? = null,
)

/** R56 — Re-request a stream ticket with a subtitle burned in (encode / PGS path). */
@Serializable
data class PlaybackRestreamRequest(
    @SerialName("item_id") val itemId: String,
    /** Phase 252 (FR-252-2) — negative = "this stream at [positionMs], with NO burn-in" (how a
     *  burn-in is undone). `-1` rather than a nullable so the wire type never changed: a backend that
     *  predates 252 forwards it to Jellyfin, where -1 already means "no subtitle". */
    @SerialName("subtitle_stream_index") val subtitleStreamIndex: Int,
    @SerialName("position_ms") val positionMs: Long = 0,
    /** Phase 252 (FR-252-3) — the session's own capabilities, so an un-burn negotiates as the real
     *  device (it may well direct-play). Absent from a pre-252 client ⇒ conservative defaults. */
    val capabilities: ClientCapabilities? = null,
    /** Phase 253 (FR-253-1) — Jellyfin's index of the audio track the restreamed session must carry;
     *  null ⇒ Jellyfin's default. Composes with [subtitleStreamIndex]: a subtitle pick keeps the
     *  audio, an audio pick keeps the burn-in. */
    @SerialName("audio_stream_index") val audioStreamIndex: Int? = null,
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
    // R182 — admin-editor-only (no on-TV Settings control); same overlay mechanism.
    @SerialName("skip_intro") val skipIntro: SkipMode? = null,
    @SerialName("skip_credits") val skipCredits: SkipMode? = null,
    @SerialName("skip_secs") val skipSecs: Int? = null,
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

// Bug fix — Ravilo's "My List" write-through (the button existed on both detail screens with no
// backend call behind it at all).
@Serializable
data class FavoriteRequest(
    @SerialName("item_id") val itemId: String,
    val favorite: Boolean,
)

// ─── Chromecast (Phase 218 / R245) ────────────────────────────────────────────

/** Phase 218 (FR-218-11) — the per-installation Cast application id, delivered at runtime on the config
 *  snapshot rather than in any manifest. [receiverUrl] is informational (the receiver is resolved by
 *  Google from the app id); null when the admin has not set a public address. */
@Serializable
data class CastCapability(
    @SerialName("app_id") val appId: String,
    @SerialName("receiver_url") val receiverUrl: String? = null,
)

/** Phase 218 (FR-218-9) — what `POST /api/tv/cast/handoff` returns to the phone: a short-lived,
 *  single-use code the phone puts in its LOAD so the receiver can enrol as its own Ravilo device. */
@Serializable
data class CastHandoffResponse(
    val code: String,
    @SerialName("expires_at") val expiresAt: Long,
    /** Seconds the code stays valid, for a client that wants to show or time it. */
    @SerialName("ttl_seconds") val ttlSeconds: Int,
)

/** Phase 218 (FR-218-9) — the receiver redeems the code for its own device token (a [PairResult], exactly
 *  what `/tv/login` returns to a TV). [receiverId] is the receiver's persisted device id when its storage
 *  survived since the last cast, so the same `ravilo_device` row is reused; absent ⇒ a new row. */
@Serializable
data class CastRedeemRequest(
    val code: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("receiver_id") val receiverId: String? = null,
)

// ─── Screens (Phase 236) — the backend drives a TV for a phone ────────────────
//
// The Chromecast receiver model (218 / R245) with Google removed from the middle: a receiver-only TV
// app (R264) holds its own device identity and plays what the backend pushes; the phone (R265) lists its
// TVs, starts a title, and drives it, entirely through the server. Everything here is shared between the
// backend, the phone (Compose) and the receiver (Kotlin/JS) so the three cannot drift — the R245 lesson
// (dev notes) where a receiver declared fields the backend never read.

/** Phase 236 (dev review item 9) — server-pushed like [CastCapability], so the phone can draw the Cast
 *  glyph without a request per Home. [paired] is scoped to the viewer THIS config was resolved for, not
 *  the whole household — a device token is per (device, user) throughout this codebase. */
@Serializable
data class ScreensCapability(
    val enabled: Boolean,
    val paired: Boolean,
)

/** Phase 236 (FR-236-3) — one track the phone's picker (or an API caller) can select by index, same
 *  shape the local player already uses (R180/R195). */
@Serializable
data class ScreenTrack(
    val index: Int,
    val label: String? = null,
    val language: String? = null,
    val forced: Boolean = false,
    @SerialName("is_default") val isDefault: Boolean = false,
)

/**
 * Phase 236 (FR-236-5, dev review item 6) — what a receiver posts to `POST /api/tv/playback/status` and
 * what a subscriber (a phone's remote, an API caller) receives as `device_status`. Moved here from
 * `ravilo-ui`'s `CastRemoteStatus` (dev review item 6): [subSize] is a string (a Char doesn't serialise
 * cleanly), and `receiverId` is dropped — the route already carries the device id, so the field would
 * just be the same value restated.
 */
@Serializable
data class ScreenStatus(
    @SerialName("item_id") val itemId: String? = null,
    val title: String? = null,
    val kicker: String? = null,
    @SerialName("art_url") val artUrl: String? = null,
    @SerialName("position_ms") val positionMs: Long = 0L,
    @SerialName("duration_ms") val durationMs: Long = 0L,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    /** Media is loaded on the receiver (the mini bar and the remote have something to show). */
    val loaded: Boolean = false,
    /** The receiver reported the item finished (FR-R245-9 · Ended). */
    val ended: Boolean = false,
    @SerialName("has_next") val hasNext: Boolean = false,
    @SerialName("next_up_secs") val nextUpSecs: Int? = null,
    @SerialName("next_title") val nextTitle: String? = null,
    /** Phase 182's 503 as the receiver saw it, with when it started waiting. */
    @SerialName("busy_retry_after") val busyRetryAfter: Int? = null,
    @SerialName("busy_since_ms") val busySinceMs: Long? = null,
    @SerialName("no_server") val noServer: Boolean = false,
    @SerialName("audio_tracks") val audioTracks: List<ScreenTrack> = emptyList(),
    @SerialName("subtitle_tracks") val subtitleTracks: List<ScreenTrack> = emptyList(),
    @SerialName("selected_audio") val selectedAudio: Int = 0,
    @SerialName("selected_sub") val selectedSub: Int = -1,
    @SerialName("sub_size") val subSize: String = "M",
    /** FR-R245-19 — the receiver's stream is a server-side conversion, not the file itself. */
    val transcoding: Boolean = false,
    /** FR-236-4 — which of the receiver's own tokens this status describes; absent on a single-session
     *  device (today's TVs and receivers, unchanged). */
    @SerialName("session_user_id") val sessionUserId: String? = null,
)

/** Phase 236 (FR-236-1) — a device kind, not a name prefix; `cast`/`screen` behave alike except at the
 *  two sites that still care which is which (218's session ceiling, the Jellyfin dashboard identity). */
@Serializable
enum class DeviceKind {
    @SerialName("tv") TV,
    @SerialName("phone") PHONE,
    @SerialName("web") WEB,
    @SerialName("cast") CAST,
    @SerialName("screen") SCREEN,
}

/** The backend stores `kind` as the DB's raw TEXT column (`dev.jellystructure.auth.DeviceData.kind`);
 *  this is the one place that string becomes the wire enum. Unknown/legacy values read as TV — the
 *  historical default every row had before this phase. */
fun deviceKindOf(raw: String): DeviceKind = when (raw) {
    "phone" -> DeviceKind.PHONE
    "web" -> DeviceKind.WEB
    "cast" -> DeviceKind.CAST
    "screen" -> DeviceKind.SCREEN
    else -> DeviceKind.TV
}

/** Phase 236 (FR-236-3) — `GET /api/remote/devices`, extended in place (phase 111's original
 *  [nowPlayingTitle] stays for compatibility; a caller that only ever read that field keeps working). */
@Serializable
data class RemoteDevice(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val kind: DeviceKind,
    val platform: String? = null,
    /** Renamed from `connected` (FR-236-3) — kept as the same boolean a Home Assistant integration
     *  written against phase 111 already reads under the old name via [connected]. */
    val online: Boolean,
    @SerialName("last_seen") val lastSeen: Long,
    /** FR-236-6 — "on the same network as the caller"; the phone renders this as its first tier and
     *  never computes it itself. */
    val nearby: Boolean = false,
    /** Household display names of every user paired to this device (FR-236-10, dev review item 8d). */
    @SerialName("paired_users") val pairedUsers: List<String> = emptyList(),
    @SerialName("now_playing_title") val nowPlayingTitle: String? = null,
    @SerialName("now_playing") val nowPlaying: ScreenStatus? = null,
    /**
     * R270 (FR-R270-3) — the household display name of **the person actually watching**, resolved
     * server-side, or null when it cannot be resolved.
     *
     * This is not [pairedUsers]. The client used to render `pairedUsers.firstOrNull()`, which is the
     * first user *paired to the TV* — on a set two people have paired with, that named the wrong
     * household member roughly half the time, and it was empty for `kind = "tv"` (where `pairedUsers`
     * is never populated at all), producing *"Busy · is watching"*. The only identity the screen
     * itself reports is [ScreenStatus.sessionUserId], a Jellyfin user **id**, which FR-R270-3 forbids
     * showing.
     *
     * Resolved in ONE place on the server (FR-R270-5): busy and offline are one disclosure decision,
     * so if a future guest mode ever needs anonymity, this field stops being emitted and **both** rows
     * lose their detail together — one flag, not two client branches that can drift. A null here means
     * the client says *"In use"*, never *"Busy · is watching"* with a hole in it.
     */
    @SerialName("now_playing_user") val nowPlayingUser: String? = null,
) {
    /** Phase 111 compatibility name — an existing Home Assistant `media_player` built against the
     *  original shape reads `connected`, not `online`. */
    val connected: Boolean get() = online
}

/**
 * R270 (FR-R270-3) — the body of `POST /api/remote/play`'s 409.
 *
 * It used to be the bare [ScreenStatus], whose only identity is a user id — so the list and the
 * refusal could not possibly name the same person, and the acceptance criterion "the list and the 409
 * agree" was satisfied vacuously. One resolution, both surfaces.
 */
@Serializable
data class ScreenBusy(
    val status: ScreenStatus,
    /** Same value, same rules, same null meaning as [RemoteDevice.nowPlayingUser]. */
    val user: String? = null,
)

/** R265 — the envelope `POST /api/tv/playback/status` fans out over `/api/remote/events` (and
 *  `/api/tv/events`) via `TvEventBus.notifyDeviceStatus`; see that function's own doc for the wire shape
 *  this mirrors (`{"type":"device_status","device_id":…,"status":…}`). */
@Serializable
data class DeviceStatusEnvelope(
    val type: String = "",
    @SerialName("device_id") val deviceId: String = "",
    val status: ScreenStatus = ScreenStatus(),
)

@Serializable
data class RemotePlayRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("jellyfin_item_id") val jellyfinItemId: String,
    @SerialName("start_position_ms") val startPositionMs: Long = 0,
)

/** Phase 236 (FR-236-3) — every command `POST /api/remote/command` accepts. Only the fields a given
 *  [command] uses are read; the rest are ignored (never validated as "must be absent"), so an API caller
 *  can send one shape without a command-specific request type. */
@Serializable
data class RemoteCommandRequest(
    @SerialName("device_id") val deviceId: String,
    /** stop | pause | unpause | home | seek | skip | next | previous | set_audio | set_subtitle |
     *  set_subtitle_size | cancel_next_up | skip_segment | set_volume | mute */
    val command: String,
    @SerialName("position_ms") val positionMs: Long? = null,
    @SerialName("delta_ms") val deltaMs: Long? = null,
    val index: Int? = null,
    /** set_subtitle_size: S | M | L. */
    val size: String? = null,
    /** set_volume: 0–100. */
    val volume: Int? = null,
)

/** Phase 236 (FR-236-2, dev review item 1) — `POST /api/tv/screen/code` mints this for an unpaired
 *  receiver; open path, rate-limited like `/tv/login`. */
@Serializable
data class ScreenCodeRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String? = null,
    val platform: String? = null,
)

@Serializable
data class ScreenCodeResponse(
    val code: String,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("claim_secret") val claimSecret: String,
)

/** Phase 236 (FR-236-2) — `POST /api/remote/pair`, device token only (an API key has no Jellyfin user
 *  token to copy onto the receiver's row, and is refused with 403). */
@Serializable
data class RemotePairRequest(val code: String)

@Serializable
data class RemotePairResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
)

/** Phase 236 (FR-236-2) — the receiver polls `POST /api/tv/screen/claim` with this; open path,
 *  rate-limited. The secret never appears on screen (dev review item 1) — only the six-character code
 *  does, and the code alone can't retrieve a token. */
@Serializable
data class ScreenClaimRequest(
    val code: String,
    @SerialName("claim_secret") val claimSecret: String,
)

// ─── Browse facets ────────────────────────────────────────────────────────────

/**
 * Phase 216 (FR-216-5) — [logoUrl] is present ONLY when a logo was really captured for this value
 * (the server's `LogoDownloader.hasLogo` predicate); there is no placeholder, no sentinel and no
 * on-demand fetch, so an absent key is the normal, permanent state for most networks and for every
 * genre and tag. R243 renders the name as a wordmark in that case and never says "no logo".
 */
@Serializable
data class FacetItem(
    val name: String,
    val count: Int,
    val logoUrl: String? = null,
    /** Phase 232 — `"light"` | `"dark"`: the ink the logo is drawn in, so the client can pick a ground it
     *  is visible on (R259). Present only with [logoUrl] and only once judged; absent = unknown. */
    @kotlinx.serialization.SerialName("logo_ink") val logoInk: String? = null,
)

/**
 * Phase 216 (FR-216-1) — the browse facet bar's counts, now also the Discover wall's index (R243).
 * Every list is per-viewer (counted over what this device can see), count-descending with the
 * display name as tie-break, normalised so two spellings of one value cannot both appear.
 *
 * [library] = titles visible to this profile; [titles] = per list ("studios"/"networks"/"genres"/
 * "tags"), the number of DISTINCT titles carrying at least one value of that kind — never the sum
 * of the counts, since a film with two studios counts once here and once under each studio.
 * [scoped] = this profile sees less than the whole library (a library allow-list or a Jellyfin tag
 * policy narrows it), so a header can say the counts are narrowed rather than under-report silently.
 */
@Serializable
data class BrowseFacets(
    val genres: List<FacetItem> = emptyList(),
    val studios: List<FacetItem> = emptyList(),
    val networks: List<FacetItem> = emptyList(),
    val tags: List<FacetItem> = emptyList(),
    val library: Int = 0,
    val titles: Map<String, Int> = emptyMap(),
    val scoped: Boolean = false,
    /**
     * R267 (FR-R267-5c) — one count per browse kind (`"all"`, `"movie"`, `"series"`, `"musicvideo"`),
     * for this profile, in **one** response.
     *
     * The phone's Library type dropdown shows all four at once. `facets(kind)` answers for a single
     * slice per call, so four counts would otherwise be four round trips — four chances to disagree,
     * on a page whose whole point is that the count and the grid agree. The client may not sum them
     * itself either (render-never-compute). The server builds all four accumulators anyway.
     *
     * Defaulted, so a client reading an older server simply shows no counts rather than failing.
     */
    @SerialName("kind_counts") val kindCounts: Map<String, Int> = emptyMap(),
)

/** R267 (FR-R267-5c) — the wire keys for [BrowseFacets.kindCounts] and the `kind` query parameter,
 *  declared once so the server's slices and the client's dropdown cannot spell them differently. */
const val ALL_KIND = "all"
const val MUSIC_KIND = "musicvideo"

// ─── Errors ───────────────────────────────────────────────────────────────────

sealed class TvApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * R237 — [status] was always here and never consulted: the player retried a deterministic 409 on
     * the same 1/2/4/8 s schedule as a dropped packet, so a precise server-side diagnosis spent ~15 s
     * behind a spinner and was never rendered. [retryAfterSeconds] carries the server's own
     * `Retry-After` (Phase 182 FR-182-8 sends it with every 503) so a busy server is waited on for as
     * long as it asked for, rather than a guess.
     */
    class Http(
        val status: Int,
        override val message: String,
        val retryAfterSeconds: Int? = null,
    ) : TvApiError("HTTP $status: $message")
}
