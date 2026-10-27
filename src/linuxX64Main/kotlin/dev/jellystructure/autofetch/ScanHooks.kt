package dev.jellystructure.autofetch

/**
 * DEB-2476 (DEB-2474, FR-15.4, FR-15.5, FR-8.2) — what the scan reads from Auto-fetch. A series fed by a page-only fetcher
 * carries a mark: the scan never searches TMDB for it, never takes a TMDB id for it from Jellyfin's provider ids, and takes
 * each episode's title, overview and air date from the fetcher's item row, matched by the placed path — the page's own words,
 * verbatim or empty.
 */
data class PageSeriesText(val description: String?)
data class PageEpisodeText(val title: String?, val overview: String?, val airDate: String?, val runtimeMinutes: Int?)

interface AutofetchScanHooks {
    fun isPageOnly(seriesPath: String): Boolean
    fun seriesText(seriesPath: String): PageSeriesText?
    fun episodeText(path: String): PageEpisodeText?
}

class StoreScanHooks(private val store: AutofetchStore) : AutofetchScanHooks {
    override fun isPageOnly(seriesPath: String): Boolean = runCatching { store.isPageOnlySeries(seriesPath) }.getOrDefault(false)

    override fun seriesText(seriesPath: String): PageSeriesText? = runCatching {
        store.fetchers().firstOrNull { it.pageOnly && it.seriesPath.trimEnd('/') == seriesPath.trimEnd('/') }?.let { PageSeriesText(it.seriesDescription) }
    }.getOrNull()

    override fun episodeText(path: String): PageEpisodeText? = runCatching {
        store.itemByPlacedPath(path)?.let { PageEpisodeText(it.title, it.description, it.published, it.duration?.let { s -> (s + 30) / 60 }) }
    }.getOrNull()
}
