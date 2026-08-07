package dev.jellystructure.bazarr

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.nowEpochSec

/**
 * Phase 157 — resolves a jellystructure [MediaItem]/[Episode] to its Bazarr entry.
 *
 * Per the dev-review addendum: no path-matching is needed. Bazarr's own `/api/movies`/`/api/series`
 * return `imdbId`/`tvdbId` directly, and [MediaItem] already carries both — so this is a clean id
 * join (movies on `imdbId`, series on `tvdbId`, episodes on season+episode number once the series is
 * resolved), falling back to a path-prefix match only when the id is missing on either side.
 */
class BazarrService(
    private val configStore: ConfigStore,
    private val client: BazarrClient,
) {
    private val cacheTtlSec = 30L
    private var moviesCache: Pair<Long, List<BazarrMovie>>? = null
    private var seriesCache: Pair<Long, List<BazarrSeries>>? = null

    fun config() = configStore.current.bazarr?.takeIf { it.enabled && it.url.isNotBlank() }

    suspend fun movies(): List<BazarrMovie> {
        val cfg = config() ?: return emptyList()
        moviesCache?.let { (at, list) -> if (nowEpochSec() - at < cacheTtlSec) return list }
        val list = client.allMovies(cfg.url, cfg.apiKey)
        moviesCache = nowEpochSec() to list
        return list
    }

    suspend fun seriesList(): List<BazarrSeries> {
        val cfg = config() ?: return emptyList()
        seriesCache?.let { (at, list) -> if (nowEpochSec() - at < cacheTtlSec) return list }
        val list = client.allSeries(cfg.url, cfg.apiKey)
        seriesCache = nowEpochSec() to list
        return list
    }

    private fun pathMatch(bazarrPath: String, itemPath: String): Boolean {
        if (bazarrPath.isBlank() || itemPath.isBlank()) return false
        return bazarrPath == itemPath ||
            itemPath.startsWith(bazarrPath.trimEnd('/') + "/") ||
            bazarrPath.startsWith(itemPath.substringBeforeLast('/', itemPath).trimEnd('/') + "/")
    }

    suspend fun resolveMovie(item: MediaItem): BazarrMovie? {
        if (item.kind != MediaKind.MOVIE) return null
        val list = movies()
        val imdb = item.imdbId
        return (if (imdb != null) list.firstOrNull { it.imdbId == imdb } else null)
            ?: list.firstOrNull { pathMatch(it.path, item.path) }
    }

    suspend fun resolveSeries(item: MediaItem): BazarrSeries? {
        if (item.kind != MediaKind.TV_SHOW) return null
        val list = seriesList()
        val tvdb = item.tvdbId
        return (if (tvdb != null) list.firstOrNull { it.tvdbId == tvdb } else null)
            ?: list.firstOrNull { pathMatch(it.path, item.path) }
    }

    suspend fun resolveEpisode(sonarrSeriesId: Int, episode: Episode): BazarrEpisode? {
        val cfg = config() ?: return null
        val eps = client.episodesFor(cfg.url, cfg.apiKey, sonarrSeriesId)
        return eps.firstOrNull { it.season == episode.seasonNumber && it.episode == episode.episodeNumber }
    }
}
