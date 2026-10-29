package dev.jellystructure.tv

import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind

/**
 * DEB-2916 (FR-1, FR-4) — a *newest first* series: listed newest first, Play plays its latest episode, Continue Watching
 * offers only that episode, and nothing follows an episode. These are the pure rules; [dev.jellystructure.media.MediaStore]
 * supplies the one fact they cannot read from the item, whether an Auto-fetch numbered by air date feeds the series.
 */

/** FR-2 — why a series is, or is not, newest first, as the admin route says it. */
internal enum class NewestFirstSource(val wire: String) {
    /** Null, decided by Auto-fetch: the answer is whether a date-numbered fetcher feeds the series. */
    AUTO("auto"),
    /** The admin chose, on or off. Auto-fetch never changes it. */
    ADMIN("admin"),
    /** Null, and not fed by a date-numbered fetcher. */
    OFF("off"),
}

/** FR-1 — the source of [item]'s mark. [dateNumbered]: a date-numbered Auto-fetch feeds the series. */
internal fun newestFirstSource(item: MediaItem, dateNumbered: Boolean): NewestFirstSource = when {
    item.newestFirst != null -> NewestFirstSource.ADMIN
    dateNumbered -> NewestFirstSource.AUTO
    else -> NewestFirstSource.OFF
}

/** FR-1 — the one answer every rule calls. Only a TV series can be newest first. */
internal fun isNewestFirst(item: MediaItem, dateNumbered: Boolean): Boolean =
    item.kind == MediaKind.TV_SHOW && when (newestFirstSource(item, dateNumbered)) {
        NewestFirstSource.ADMIN -> item.newestFirst == true
        NewestFirstSource.AUTO -> true
        NewestFirstSource.OFF -> false
    }

/**
 * FR-4 — the newest episode: the last counted one (R346: season 1 or later, with a Jellyfin item) in season-then-episode
 * order; for a multi-episode file, the file's first part. Null when the series has no counted episode.
 */
internal fun newestEpisode(series: MediaItem): Episode? {
    val ordered = countedEpisodesInOrder(series)
    val last = ordered.lastOrNull() ?: return null
    return ordered.first { it.jellyfinId == last.jellyfinId }
}
