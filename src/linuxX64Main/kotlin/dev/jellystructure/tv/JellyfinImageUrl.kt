package dev.jellystructure.tv

/** R85: image URLs are now relative /api/tv/image/{id}/{type} paths — served by ImageProxyService.
 *  The TV app resolves the base URL via LocalServerBaseUrl, keeping Jellyfin credentials off the app. */
object JellyfinImageUrl {
    fun poster(jellyfinId: String)     = "/api/tv/image/$jellyfinId/poster"
    fun backdrop(jellyfinId: String)   = "/api/tv/image/$jellyfinId/backdrop"
    fun heroBackdrop(jellyfinId: String) = "/api/tv/image/$jellyfinId/backdrop"
    fun logo(jellyfinId: String)       = "/api/tv/image/$jellyfinId/logo"
    fun still(jellyfinId: String)      = "/api/tv/image/$jellyfinId/still"
    fun avatar(userId: String)         = "/api/tv/image/$userId/avatar"
}
