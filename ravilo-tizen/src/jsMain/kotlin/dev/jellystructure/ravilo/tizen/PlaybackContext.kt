package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.Episode
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.TvSegmentMarkers

/** R189 milestone 2 — everything `PlayerScreen` needs beyond the `StreamTicket` itself: which item is
 *  actually playing (an episode id can differ from the series' own [parentCard.id]), its segment
 *  markers for Skip Intro/Credits, and the next episode to offer for autoplay (series only). */
data class PlaybackContext(
    val itemId: String,
    val displayTitle: String,
    val segments: TvSegmentMarkers,
    val nextEpisode: Episode?,
    val parentCard: MediaCard,
)
