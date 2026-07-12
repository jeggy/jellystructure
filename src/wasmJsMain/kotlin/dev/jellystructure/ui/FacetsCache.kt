package dev.jellystructure.ui

import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.MetaFacets
import dev.jellystructure.api.TrackFacets

/**
 * Session-lifetime cache for the two facet-enumeration endpoints (distinct genres/studios/tags/
 * networks/audio-languages/codecs across the whole library). Shared by the Library workbench
 * ("Add filter") and the movie/series detail genre picker — previously each fetched independently
 * (Workbench had its own cache; MediaDetail had none at all, refetching on every single detail-page
 * open, arguably the most-visited screen in the app). Whichever screen opens first now warms it for
 * the other. Never invalidated: facet values change rarely, and a stale value only means a
 * brand-new studio/tag/genre briefly doesn't show up as a filter option until the next full page load.
 */
object FacetsCache {
    private var meta: MetaFacets? = null
    private var track: TrackFacets? = null

    suspend fun meta(): MetaFacets? = meta ?: MediaApi.metaFacets().also { meta = it }
    suspend fun track(): TrackFacets? = track ?: MediaApi.trackFacets().also { track = it }
}
