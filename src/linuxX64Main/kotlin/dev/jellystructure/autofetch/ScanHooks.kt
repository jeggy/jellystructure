package dev.jellystructure.autofetch

/**
 * DEB-2476 (DEB-2474, FR-15.4, FR-15.5, FR-8.2) — what the scan reads from Auto-fetch. A series fed by a page-only fetcher
 * carries a mark: the scan never searches TMDB for it, never takes a TMDB id for it from Jellyfin's provider ids, and takes
 * each episode's title, overview and air date from the fetcher's item row, matched by the placed path — the page's own words,
 * verbatim or empty.
 */
data class PageSeriesText(val description: String?)
data class PageEpisodeText(val title: String?, val overview: String?, val airDate: String?, val runtimeMinutes: Int?)

/** FR-8.4 — which of an episode's fields still read as the page's own words, because TMDB has left them blank. */
data class PageMarker(val title: Boolean, val overview: Boolean)

interface AutofetchScanHooks {
    fun isPageOnly(seriesPath: String): Boolean
    fun seriesText(seriesPath: String): PageSeriesText?
    /**
     * The page's words for the episode at [path]: all of them for a page-only fetcher (FR-15.4), and for a TMDB fetcher only the fields
     * FR-8.4's marker names, and only for an episode that was queued. An episode TMDB has is ordinary (FR-8.1): null.
     */
    fun episodeText(path: String): PageEpisodeText?
    /** FR-8.4 — the per-episode marker the metadata re-pull and the season sync read, or null for an ordinary episode. */
    fun pageMarker(path: String): PageMarker?
}

class StoreScanHooks(private val store: AutofetchStore) : AutofetchScanHooks {
    override fun isPageOnly(seriesPath: String): Boolean = runCatching { store.isPageOnlySeries(seriesPath) }.getOrDefault(false)

    override fun seriesText(seriesPath: String): PageSeriesText? = runCatching {
        store.fetchers().firstOrNull { it.pageOnly && it.seriesPath.trimEnd('/') == seriesPath.trimEnd('/') }?.let { PageSeriesText(it.seriesDescription) }
    }.getOrNull()

    override fun episodeText(path: String): PageEpisodeText? = runCatching {
        val item = store.itemByPlacedPath(path) ?: return@runCatching null
        val fetcher = store.fetcher(item.fetcherId) ?: return@runCatching null
        val runtime = item.duration?.let { s -> (s + 30) / 60 }
        if (fetcher.pageOnly) return@runCatching PageEpisodeText(item.title, item.description, item.published, runtime)
        // FR-8.1 — an episode TMDB has is ordinary: only a queued episode (or a field the marker still holds) carries the page's words.
        val row = store.queueRow(item.fetcherId, item.itemId) ?: return@runCatching null
        PageEpisodeText(
            title = item.title.takeIf { row.titleFromPage }, overview = item.description.takeIf { row.overviewFromPage },
            airDate = item.published.takeIf { row.state != "cleared" }, runtimeMinutes = runtime.takeIf { row.state != "cleared" },
        )
    }.getOrNull()

    override fun pageMarker(path: String): PageMarker? = runCatching {
        val item = store.itemByPlacedPath(path) ?: return@runCatching null
        store.queueRow(item.fetcherId, item.itemId)?.takeIf { it.markerOn }?.let { PageMarker(it.titleFromPage, it.overviewFromPage) }
    }.getOrNull()
}
