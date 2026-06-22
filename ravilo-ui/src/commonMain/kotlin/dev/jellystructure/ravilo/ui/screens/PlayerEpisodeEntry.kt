package dev.jellystructure.ravilo.ui.screens

/** Lightweight episode descriptor passed to PlayerScreen for the in-player episode rail. */
data class PlayerEpisodeEntry(
    val id: String,
    val n: Int,
    val title: String,
    val kicker: String,        // e.g. "S1 · E3"
    val durationLabel: String, // e.g. "42m"
    val progressPct: Float,    // 0–1, from Jellyfin watched data
    val watched: Boolean,
    val stillUrl: String?,
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
