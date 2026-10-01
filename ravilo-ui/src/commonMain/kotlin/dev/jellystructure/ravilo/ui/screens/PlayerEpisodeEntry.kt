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
    val kicker: String,        // e.g. "S01E03" (R346)
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
    /** R350 (FR-R350-6) — the season this entry belongs to: its number (0 = Specials) and its own name, for the rail's
     *  header. Null on an entry built without them (the header then reads the number from [kicker]). */
    val seasonNumber: Int? = null,
    val seasonName: String? = null,
)

/**
 * R350 (FR-R350-6) — the player's episode rail (TV) and episode sheet (phone) are headed by their season: *Season 1*
 * in the viewer's language ([seasonWord] is `detail.season`), the season's own name for Specials. It was
 * `kicker.substringBefore("·")`, written for the old `S1 · E5` kicker — since R346 the kicker is `S01E05` and the
 * whole code came through (*S01E01* over episode 3).
 */
internal fun playerRailSeasonLabel(episodes: List<PlayerEpisodeEntry>?, seasonWord: String): String {
    val first = episodes?.firstOrNull() ?: return ""
    val n = first.seasonNumber ?: Regex("""S(\d+)E\d+""").find(first.kicker)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        n == null -> first.seasonName.orEmpty()
        n == 0 -> first.seasonName ?: "$seasonWord 0"
        else -> "$seasonWord $n"
    }
}

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
    /** R343 (FR-R343-2/4) — this play is a finished series' *Start over*: from 0:00, and the server clears
     *  the series once 5 % of it has played. Only the first episode carries it; auto-advance never does. */
    val startOver: Boolean = false,
)

/**
 * R343 (FR-R343-5) — the next-up card is announcing the next entry of a shuffle, not the next episode in
 * order: its kicker reads *UP NEXT · SHUFFLED*. Provided by the app around the player (a CompositionLocal,
 * not a `PlayerScreen` parameter — that composable sits at ART's register ceiling, see PlayerBookkeeping).
 */
val LocalShuffledNextUp = androidx.compose.runtime.staticCompositionLocalOf { false }
