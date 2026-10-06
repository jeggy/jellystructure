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
    val tier = resolutionTier(video.width, video.height) ?: return null
    return if (video.videoRange == "HDR") "$tier HDR" else tier
}

/**
 * R377 (FR-R377-1) — the smallest tier a picture fits in, by Jellyfin's own upper bounds
 * (`MediaStream.GetResolutionText`), so a film cropped to its scope shape (3832 × 1600) is still 4K and a 1918 × 802
 * web release still 1080p — the tier Jellyfin's own stream title gives. 1440p stays 1080p; 8K is 4K.
 */
internal fun resolutionTier(width: Int?, height: Int?): String? {
    if (width == null && height == null) return null
    val w = width ?: 0
    val h = height ?: 0
    return when {
        w > 2560 || h > 1440 -> "4K"
        w > 1280 || h > 962 -> "1080p"
        w > 1024 || h > 576 -> "720p"
        else -> "SD"
    }
}

/** R325 (FR-R325-3) — the tile's badge: only `4K`, `HDR` or `4K HDR`; a 1080p or SD title carries none. */
internal fun MediaItem.qualityBadge(): String? = when (val q = qualityLabel()) {
    "4K", "4K HDR" -> q
    "1080p HDR", "720p HDR", "SD HDR" -> "HDR"
    else -> null
}
