package dev.jellystructure.media

import dev.jellystructure.model.MediaItem

/**
 * DEB-2093 — restores Phase 131's "transient failures preserve the last good value" on the from-scratch
 * scan write path.
 *
 * A fresh scan builds its `MediaItem` with `imdbId = fetch.imdbId` and no `imdbRating`, and
 * `TmdbClient.getExternalIds` swallows any failure to null — so an ordinary rescan wiped the stored
 * rating, and a rescan whose external-ids call failed wiped the id as well, which nothing restores on
 * its own (`sync_imdb_ratings` skips items with no id). The re-pull paths already do
 * `fetch.imdbId ?: item.imdbId`; this is the same rule for [MediaStore.addOrUpdate].
 *
 * Carried only when the TMDB match is unchanged: a re-match (different `tmdbId`) means the stored id
 * belongs to another title, and a cleared or locked match ([clearTmdbMatch], [preserveTmdbMatchLock])
 * has no id to keep. Run AFTER [preserveTmdbMatchLock] so that guard's clearing wins.
 */
internal fun preserveImdb(fresh: MediaItem, old: MediaItem?): MediaItem {
    if (old == null || old.tmdbMatchLocked) return fresh
    val tmdbId = fresh.tmdbId ?: return fresh
    if (tmdbId != old.tmdbId) return fresh
    val id = fresh.imdbId?.takeIf { it.isNotBlank() } ?: old.imdbId?.takeIf { it.isNotBlank() } ?: return fresh
    val rating = fresh.imdbRating ?: old.imdbRating?.takeIf { id == old.imdbId }
    return fresh.copy(imdbId = id, imdbRating = rating)
}
