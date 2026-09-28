package dev.jellystructure.tv

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.TrackKind

/**
 * R187 (Quality facet) — the best (largest, since a scan only probes one file per episode/movie) video track's
 * tier: `4K` · `1080p` · `720p` · `SD`, with ` HDR` when the range says so. Moved out of [BrowseService] for R325.
 */
internal fun MediaItem.qualityLabel(): String? {
    val tracks = if (kind == MediaKind.TV_SHOW) episodes.flatMap { it.tracks } else tracks
    val video = tracks.filter { it.kind == TrackKind.VIDEO }.maxByOrNull { (it.width ?: 0) * (it.height ?: 0) } ?: return null
    val tier = when {
        (video.width ?: 0) >= 3840 || (video.height ?: 0) >= 2160 -> "4K"
        (video.width ?: 0) >= 1920 || (video.height ?: 0) >= 1080 -> "1080p"
        (video.width ?: 0) >= 1280 || (video.height ?: 0) >= 720  -> "720p"
        video.width != null || video.height != null -> "SD"
        else -> return null
    }
    return if (video.videoRange == "HDR") "$tier HDR" else tier
}

/** R325 (FR-R325-3) — the tile's badge: only `4K`, `HDR` or `4K HDR`; a 1080p or SD title carries none. */
internal fun MediaItem.qualityBadge(): String? = when (val q = qualityLabel()) {
    "4K", "4K HDR" -> q
    "1080p HDR", "720p HDR", "SD HDR" -> "HDR"
    else -> null
}
