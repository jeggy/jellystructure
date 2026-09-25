package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.TvSegmentMarkers

/** Lightweight episode descriptor passed to PlayerScreen for the in-player episode rail.
 *  [numberLabel] is null for a multi-episode-file group (its [title] is already "Episodes X-Y",
 *  so a leading number would either repeat or misrepresent it — the rail card omits the badge
 *  and the numeric prefix entirely in that case). [stillUrls] holds 1 URL for a lone episode or up
 *  to 3 for a group — the rail card renders it as a single image or a seamed triptych accordingly,
 *  matching the series-detail episode rail's treatment of the same group. */
data class PlayerEpisodeEntry(
    val id: String,
    val numberLabel: String?,
    val title: String,
    val kicker: String,        // e.g. "S1 · E3"
    val durationLabel: String, // e.g. "42m"
    val progressPct: Float,    // 0–1, from Jellyfin watched data
    val watched: Boolean,
    val stillUrls: List<String?>,
    /** Phase 150 — this entry's own intro/credits segments (R182 Skip Intro / Skip Credits). */
    val segments: TvSegmentMarkers = TvSegmentMarkers(),
    /** R194 — this entry's season's own poster URL (relative, like [stillUrls] — resolved against the
     *  server base URL by the caller), for the player's OS media-session artwork. Null when the season
     *  has no poster on disk; PlayerScreen falls back to the series' own poster in that case. */
    val seasonPosterUrl: String? = null,
)

/** Context built by SeriesDetailScreen when the user selects Play on an episode. */
data class EpisodePlayContext(
    val episodeId: String,
    val episodeTitle: String,
    val kicker: String?,
    val nextEpId: String?,
    val nextEpLabel: String?,
    val nextEpTitle: String?,
    val episodes: List<PlayerEpisodeEntry>,
    val currentEpIndex: Int,
    /** R181 — the series' own item id, for per-series remembered audio/subtitle choices. */
    val seriesId: String? = null,
    /** R181/R180 — the series' original-audio language, for the player's "Dubbed" audio badge. */
    val originalLanguage: String? = null,
    /** Phase 150 — the CURRENTLY PLAYING episode's own segments (R182 Skip Intro / Skip Credits). */
    val segments: TvSegmentMarkers = TvSegmentMarkers(),
    /** R194 — the series' own poster, for the player's OS media-session artwork fallback when the
     *  current episode's season has no poster of its own (see PlayerEpisodeEntry.seasonPosterUrl). */
    val seriesPosterUrl: String? = null,
    /** R303 (FR-R303-2) — the SERIES' clearlogo + ink and its name, for the player's top-right slot. */
    val logoUrl: String? = null,
    val logoInk: String? = null,
    val seriesName: String? = null,
)
