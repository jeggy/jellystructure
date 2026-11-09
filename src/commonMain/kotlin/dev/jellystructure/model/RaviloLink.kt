package dev.jellystructure.model

/** The Ravilo Web title page for a Jellyfin item, when that page exists. */
object RaviloLink {
    fun titleUrl(raviloWebUrl: String, kind: MediaKind, jellyfinId: String?): String? {
        val address = ServiceAddress.effective(raviloWebUrl, "Ravilo Web address") ?: return null
        if (jellyfinId.isNullOrBlank()) return null
        val route = when (kind) {
            MediaKind.MOVIE -> "movie"
            MediaKind.TV_SHOW -> "series"
            MediaKind.MUSIC_VIDEO -> return null
        }
        return "$address/#/$route/$jellyfinId"
    }
}
