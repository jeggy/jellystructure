package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.BrowseFacets
import dev.jellystructure.shared.tv.FacetItem
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.SearchResults
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull


private const val SEARCH_SUGGESTION_LIMIT = 20
// R142: cap the played-state overlay fetch so a slow Jellyfin never hangs a browse/search response.
private const val HYDRATE_TIMEOUT_MS = 2_500L

class BrowseService(
    private val mediaStore: MediaStore,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
) {
    /** Paged browse grid. kind: "movie" | "series" | "mylist" | null (all). */
    suspend fun browse(
        device: DeviceData,
        kind: String?,
        genres: List<String> = emptyList(),
        studios: List<String> = emptyList(),
        networks: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        sort: String? = null,
        page: Int = 1,
        // R118: null = return the whole filtered set (the Ravilo browse grid wants the full catalog;
        // the lazy grid only renders visible cells). A non-null pageSize keeps paging available.
        pageSize: Int? = null,
    ): SearchResults = coroutineScope {
        val jellyfinBase  = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val tokenDeferred = async { jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken) }
        // liveItems() doesn't need the token; start it immediately in parallel.
        val allDeferred   = async { mediaStore.liveItems() }

        val mediaKind = when (kind) {
            "movie"  -> MediaKind.MOVIE
            "series" -> MediaKind.TV_SHOW
            else     -> null
        }

        val token = tokenDeferred.await()
        val all = if (kind == "mylist") {
            // While we waited for the token, allItems may have already finished. Kick off favorites
            // and await both — they overlap for whatever time remains.
            val favDeferred = async { jellyfinClient.getFavoriteItemIds(configStore.current.apiKeys.jellyfinUrl, token, device.jellyfinUserId) }
            val items       = allDeferred.await()
            val favoriteIds = favDeferred.await()
            items.filter { it.jellyfinId != null && favoriteIds.contains(it.jellyfinId) }
        } else {
            allDeferred.await()
        }

        val filtered = all
            .let { items -> if (mediaKind != null) items.filter { it.kind == mediaKind } else items }
            .let { items -> if (genres.isNotEmpty())   items.filter { i -> genres.any   { g -> i.genres.any  { it.equals(g, ignoreCase = true) } } } else items }
            .let { items -> if (studios.isNotEmpty())  items.filter { i -> studios.any  { s -> i.studio?.equals(s, ignoreCase = true) == true } } else items }
            .let { items -> if (networks.isNotEmpty()) items.filter { i -> networks.any { n -> i.network?.equals(n, ignoreCase = true) == true } } else items }
            .let { items -> if (tags.isNotEmpty())     items.filter { i -> tags.any     { t -> i.tags.any    { it.equals(t, ignoreCase = true) } } } else items }

        val sorted = when (sort) {
            "title" -> filtered.sortedBy { it.title.lowercase() }
            "year"  -> filtered.sortedByDescending { it.year ?: 0 }
            else    -> filtered.sortedByDescending { it.addedAt ?: it.scannedAt }
        }

        val cards = (if (pageSize == null) sorted
                     else sorted.drop((page - 1) * pageSize).take(pageSize))
            .map { it.toMediaCard() }
            .distinctBy { it.id }

        // R142: overlay Jellyfin played / in-progress state so grid tiles show ✓ / progress sliver.
        val ps = withTimeoutOrNull(HYDRATE_TIMEOUT_MS) {
            fetchPlaystate(jellyfinClient, jellyfinBase, token, device.jellyfinUserId, cards.map { it.id })
        } ?: emptyMap()
        SearchResults(query = kind ?: "all", items = cards.map { it.withPlaystate(ps) })
    }

    /** Multi-language search: matches title, originalTitle, and every titlesByLang value. */
    suspend fun search(device: DeviceData, query: String): SearchResults {
        val all = mediaStore.liveItems()
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')

        val cards = if (query.isBlank()) {
            all.sortedByDescending { it.addedAt ?: it.scannedAt }
                .take(SEARCH_SUGGESTION_LIMIT)
                .map { it.toMediaCard() }
                .distinctBy { it.id }
        } else {
            val q = query.lowercase()
            all.filter { item ->
                item.title.lowercase().contains(q) ||
                item.originalTitle?.lowercase()?.contains(q) == true ||
                item.titlesByLang.values.any { it.lowercase().contains(q) }
            }.sortedByDescending { it.addedAt ?: it.scannedAt }
                .take(100)
                .map { it.toMediaCard() }
                .distinctBy { it.id }
        }

        // R142: overlay Jellyfin played / in-progress state so search-result tiles show ✓ / progress sliver.
        val ps = withTimeoutOrNull(HYDRATE_TIMEOUT_MS) {
            val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
            fetchPlaystate(jellyfinClient, jellyfinBase, token, device.jellyfinUserId, cards.map { it.id })
        } ?: emptyMap()
        return SearchResults(query = query, items = cards.map { it.withPlaystate(ps) })
    }

    /** Available filter values + counts for browse filter chips. */
    fun facets(kind: String?): BrowseFacets {
        val mediaKind = when (kind) {
            "movie"  -> MediaKind.MOVIE
            "series" -> MediaKind.TV_SHOW
            else     -> null
        }
        val all = if (mediaKind != null) mediaStore.liveItems().filter { it.kind == mediaKind }
                  else mediaStore.liveItems()

        val genreCounts   = mutableMapOf<String, Int>()
        val studioCounts  = mutableMapOf<String, Int>()
        val networkCounts = mutableMapOf<String, Int>()
        val tagCounts     = mutableMapOf<String, Int>()

        for (item in all) {
            item.genres.forEach  { g -> genreCounts[g]   = (genreCounts[g]   ?: 0) + 1 }
            item.studio?.let     { s -> studioCounts[s]  = (studioCounts[s]  ?: 0) + 1 }
            item.network?.let    { n -> networkCounts[n] = (networkCounts[n] ?: 0) + 1 }
            item.tags.forEach    { t -> tagCounts[t]     = (tagCounts[t]     ?: 0) + 1 }
        }

        return BrowseFacets(
            genres   = genreCounts.entries.sortedByDescending { it.value }.map { FacetItem(it.key, it.value) },
            studios  = studioCounts.entries.sortedByDescending { it.value }.map { FacetItem(it.key, it.value) },
            networks = networkCounts.entries.sortedByDescending { it.value }.map { FacetItem(it.key, it.value) },
            tags     = tagCounts.entries.sortedByDescending { it.value }.map { FacetItem(it.key, it.value) },
        )
    }

    private fun MediaItem.toMediaCard(): MediaCard {
        val jId = jellyfinId
        val sonarrEnabled = configStore.current.sonarr?.enabled == true
        return MediaCard(
            id = jId ?: id,
            kind = if (kind == MediaKind.TV_SHOW) dev.jellystructure.shared.tv.MediaKind.SERIES
                   else dev.jellystructure.shared.tv.MediaKind.MOVIE,
            title = title,
            year = year,
            genre = genres.firstOrNull(),
            rating = null,
            posterUrl = RaviloImageUrl.poster(id),     // R133: keyed by MediaItem.id (on-disk artwork)
            backdropUrl = RaviloImageUrl.backdrop(id),
            upcomingEpisode = if (sonarrEnabled && kind == MediaKind.TV_SHOW &&
                sonarrStatus != "ended" && sonarrNextAiringDate != null &&
                sonarrNextAiringSeason != null && sonarrNextAiringEpisode != null)
                "S${sonarrNextAiringSeason.toString().padStart(2,'0')}E${sonarrNextAiringEpisode.toString().padStart(2,'0')}" else null,
        )
    }
}
