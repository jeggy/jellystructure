package dev.jellystructure.media

import dev.jellystructure.model.MediaItem

/**
 * DEB-2916 (FR-1) — the [MediaItem.newestFirst] equivalent of [preserveMetadataLanguage], applied at both store write
 * choke points. A scan builds a fresh `MediaItem` with the field at its `null` default (the admin's choice is never
 * something a scan discovers), so without this guard a rescan would hand the series back to Auto-fetch.
 *
 * Only ever carries the value forward. There is no route back to automatic, and the one route that sets the field
 * writes with `respectNewestFirst = false`.
 */
internal fun preserveNewestFirst(fresh: MediaItem, existing: MediaItem?): MediaItem {
    val chosen = existing?.newestFirst ?: return fresh
    return fresh.copy(newestFirst = chosen)
}
