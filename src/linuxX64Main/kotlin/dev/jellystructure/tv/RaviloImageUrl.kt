package dev.jellystructure.tv

import io.ktor.http.encodeURLPathPart

/** R133: image URLs are relative `/api/tv/image/...` paths served by [RaviloArtworkService] from
 *  jellystructure's OWN on-disk artwork (no Jellyfin). Keyed by **MediaItem.id**; episode stills by series
 *  id + episode filename; avatars by Jellyfin user id. The app resolves the base URL via LocalServerBaseUrl. */
object RaviloImageUrl {
    fun poster(id: String)       = "/api/tv/image/$id/poster"
    fun backdrop(id: String)     = "/api/tv/image/$id/backdrop"
    fun heroBackdrop(id: String) = "/api/tv/image/$id/backdrop"
    fun logo(id: String)         = "/api/tv/image/$id/logo"
    // Phase 149: epNum disambiguates when several episodes share epFilename (a multi-episode file) —
    // omitted for the ordinary non-ambiguous single-episode case.
    fun still(seriesId: String, epFilename: String, epNum: Int? = null): String {
        val base = "/api/tv/image/$seriesId/still/${epFilename.encodeURLPathPart()}"
        return if (epNum != null) "$base?ep=$epNum" else base
    }
    fun avatar(userId: String)   = "/api/tv/image/user/$userId/avatar"
    /** R194 — a season's own poster; 404s when the season has no poster on disk (client falls back to
     *  [poster], the series' own). */
    fun seasonPoster(seriesId: String, season: Int) = "/api/tv/image/$seriesId/season/$season/poster"
}
