package dev.jellystructure.media

import dev.jellystructure.model.MediaItem

/** DEB-5321 (FR-5) — where a season's name came from: the admin, Jellyfin's scan, or the `Season N` fallback. */
internal enum class SeasonNameSource(val wire: String) { ADMIN("admin"), JELLYFIN("jellyfin"), DEFAULT("default") }

internal data class SeasonName(val name: String, val source: SeasonNameSource)

/**
 * DEB-5321 (FR-2) — THE function that names a season of a series: the admin's name, else the scanned one, else
 * `Season N`. A blank value counts as absent. [season] is jellystructure's own number (`Episode.seasonNumber ?: 0`).
 */
internal fun seasonNameFor(item: MediaItem, season: Int): SeasonName {
    item.seasonNameOverrides[season]?.takeIf { it.isNotBlank() }?.let { return SeasonName(it, SeasonNameSource.ADMIN) }
    item.seasonNames[season]?.takeIf { it.isNotBlank() }?.let { return SeasonName(it, SeasonNameSource.JELLYFIN) }
    return SeasonName("Season $season", SeasonNameSource.DEFAULT)
}

/**
 * DEB-5321 (FR-1) — the [MediaItem.seasonNameOverrides] equivalent of [preserveNewestFirst], applied at both store
 * write choke points. A scan builds a fresh item with the map empty (the admin's names are never something a scan
 * discovers), so without this a rescan would drop them. The one route that changes the map writes with
 * `respectSeasonNameOverrides = false`.
 */
internal fun preserveSeasonNameOverrides(fresh: MediaItem, existing: MediaItem?): MediaItem {
    val stored = existing?.seasonNameOverrides?.takeIf { it.isNotEmpty() } ?: return fresh
    return fresh.copy(seasonNameOverrides = stored)
}
