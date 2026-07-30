package dev.jellystructure.media

import dev.jellystructure.model.MediaItem

/** Asset keys that can appear in [MediaItem.lockedArtwork] — the item-level rail assets the admin
 *  artwork manager can pick or upload. Season posters and episode stills have no `MediaItem` field to
 *  lock, so they are locked purely by the on-disk `.manual` marker (see [ArtworkDownloader.markManual]). */
object ArtworkAsset {
    const val POSTER = "poster"
    const val BACKDROP = "backdrop"
    const val CLEARLOGO = "clearlogo"

    /** True when [asset] is a key we track in [MediaItem.lockedArtwork]. */
    fun isLockable(asset: String): Boolean = asset == POSTER || asset == BACKDROP || asset == CLEARLOGO
}

/**
 * Phase 133 / Phase 151 — a manually picked or uploaded poster/backdrop must survive every automatic
 * metadata pull (scheduled scan, `pull_tmdb`, `sync_*`, re-pull, realtime ingest), each of which
 * otherwise resets `posterPath`/`backdropPath` to TMDB's current default. Mirrors
 * `preserveJsTags`/`mergeUserGenres`: the Scanner never needs to know about the lock itself.
 *
 * Phase 151: extracted from `MediaStore` so it can be applied at **both** store write choke points and
 * unit-tested directly. Phase 133 guarded `addOrUpdate` only, which left the per-item "Sync" /
 * "Re-pull from Jellyfin" routes and `pushToJellyfin` — all of which persist a freshly-scanned item via
 * `updateOne` — resetting a locked poster on exactly the paths an operator clicks.
 */
internal fun preserveLockedArtwork(fresh: MediaItem, existing: MediaItem?): MediaItem {
    if (existing == null || existing.lockedArtwork.isEmpty()) return fresh
    var result = fresh.copy(lockedArtwork = existing.lockedArtwork)
    if (ArtworkAsset.POSTER in existing.lockedArtwork) result = result.copy(posterPath = existing.posterPath)
    if (ArtworkAsset.BACKDROP in existing.lockedArtwork) result = result.copy(backdropPath = existing.backdropPath)
    return result
}
