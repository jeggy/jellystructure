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

private const val DEFAULT_PAGE_SIZE = 40
private const val SEARCH_SUGGESTION_LIMIT = 20

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
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): SearchResults {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)

        val mediaKind = when (kind) {
            "movie"  -> MediaKind.MOVIE
            "series" -> MediaKind.TV_SHOW
            else     -> null
        }

        val all = if (kind == "mylist") {
            val favoriteIds = jellyfinClient.getFavoriteItemIds(
                configStore.current.apiKeys.jellyfinUrl, token, device.jellyfinUserId
            )
            mediaStore.allItems().filter { it.jellyfinId != null && favoriteIds.contains(it.jellyfinId) }
        } else {
            mediaStore.allItems()
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
            else    -> filtered.sortedByDescending { it.scannedAt }
        }

        val start = (page - 1) * pageSize
        val cards = sorted.drop(start).take(pageSize)
            .map { it.toMediaCard(jellyfinBase, token) }

        return SearchResults(query = kind ?: "all", items = cards)
    }

    /** Multi-language search: matches title, originalTitle, and every titlesByLang value. */
    suspend fun search(device: DeviceData, query: String): SearchResults {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)

        if (query.isBlank()) {
            val suggestions = mediaStore.allItems()
                .sortedByDescending { it.scannedAt }
                .take(SEARCH_SUGGESTION_LIMIT)
                .map { it.toMediaCard(jellyfinBase, token) }
            return SearchResults(query = "", items = suggestions)
        }

        val q = query.lowercase()
        val items = mediaStore.allItems().filter { item ->
            item.title.lowercase().contains(q) ||
            item.originalTitle?.lowercase()?.contains(q) == true ||
            item.titlesByLang.values.any { it.lowercase().contains(q) }
        }.sortedByDescending { it.scannedAt }
            .take(100)
            .map { it.toMediaCard(jellyfinBase, token) }

        return SearchResults(query = query, items = items)
    }

    /** Available filter values + counts for browse filter chips. */
    fun facets(kind: String?): BrowseFacets {
        val mediaKind = when (kind) {
            "movie"  -> MediaKind.MOVIE
            "series" -> MediaKind.TV_SHOW
            else     -> null
        }
        val all = if (mediaKind != null) mediaStore.allItems().filter { it.kind == mediaKind }
                  else mediaStore.allItems()

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

    private fun MediaItem.toMediaCard(jellyfinBase: String, token: String): MediaCard {
        val jId = jellyfinId
        return MediaCard(
            id = jId ?: id,
            kind = if (kind == MediaKind.TV_SHOW) dev.jellystructure.shared.tv.MediaKind.SERIES
                   else dev.jellystructure.shared.tv.MediaKind.MOVIE,
            title = title,
            year = year,
            genre = genres.firstOrNull(),
            rating = null,
            posterUrl = if (jId != null) "$jellyfinBase/Items/$jId/Images/Primary?api_key=$token" else null,
            backdropUrl = if (jId != null) "$jellyfinBase/Items/$jId/Images/Backdrop/0?api_key=$token" else null,
        )
    }
}
