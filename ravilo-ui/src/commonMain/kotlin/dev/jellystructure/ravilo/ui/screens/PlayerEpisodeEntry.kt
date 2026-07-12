package dev.jellystructure.ravilo.ui.screens

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
)
