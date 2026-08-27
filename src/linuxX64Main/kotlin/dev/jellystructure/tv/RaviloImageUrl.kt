package dev.jellystructure.tv

import io.ktor.http.encodeURLPathPart

/** R133: image URLs are relative `/api/tv/image/...` paths served by [RaviloArtworkService] from
 *  jellystructure's OWN on-disk artwork (no Jellyfin). Keyed by **MediaItem.id**; episode stills by series
 *  id + episode filename; avatars by Jellyfin user id. The app resolves the base URL via LocalServerBaseUrl.
 *
 *  R214: [RaviloArtworkService]'s route responds with a 24h `Cache-Control: max-age` (Phase 118), and Coil's
 *  in-memory cache never revalidates a same-URL hit against the network at all — both were built on the
 *  (then-true) assumption that "artwork is immutable at a given URL... a changed poster gets a new
 *  path/version, not an in-place overwrite." Phase 176 broke that assumption on purpose (self-heals a
 *  stale file at the SAME url/path), which is why a corrected match could sit uncorrected in a live TV/
 *  phone app for up to 24h — or indefinitely, for a tile already resident in the app's memory cache. [v]
 *  (the asset's on-disk file size, from [dev.jellystructure.media.ArtworkDownloader.assetVersion]) makes
 *  the URL change exactly when the bytes do, which busts both cache layers by construction — no explicit
 *  invalidation call for any writer to forget. Every call site should pass it; it's optional only so a
 *  caller with no [dev.jellystructure.media.ArtworkDownloader] handy (rare) still compiles. */
object RaviloImageUrl {
    private fun withVersion(path: String, v: Long?) = if (v != null) "$path?v=$v" else path
    fun poster(id: String, v: Long? = null)       = withVersion("/api/tv/image/$id/poster", v)
    fun backdrop(id: String, v: Long? = null)     = withVersion("/api/tv/image/$id/backdrop", v)
    fun heroBackdrop(id: String, v: Long? = null) = withVersion("/api/tv/image/$id/backdrop", v)
    fun logo(id: String, v: Long? = null)         = withVersion("/api/tv/image/$id/logo", v)
    // Phase 149: epNum disambiguates when several episodes share epFilename (a multi-episode file) —
    // omitted for the ordinary non-ambiguous single-episode case.
    fun still(seriesId: String, epFilename: String, epNum: Int? = null): String {
        val base = "/api/tv/image/$seriesId/still/${epFilename.encodeURLPathPart()}"
        return if (epNum != null) "$base?ep=$epNum" else base
    }
    fun avatar(userId: String)   = "/api/tv/image/user/$userId/avatar"
    /** R194 — a season's own poster; 404s when the season has no poster on disk (client falls back to
     *  [poster], the series' own). */
    fun seasonPoster(seriesId: String, season: Int, v: Long? = null) =
        withVersion("/api/tv/image/$seriesId/season/$season/poster", v)
}
