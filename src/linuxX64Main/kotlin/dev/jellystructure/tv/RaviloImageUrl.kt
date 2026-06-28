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
    fun still(seriesId: String, epFilename: String) = "/api/tv/image/$seriesId/still/${epFilename.encodeURLPathPart()}"
    fun avatar(userId: String)   = "/api/tv/image/user/$userId/avatar"
}
