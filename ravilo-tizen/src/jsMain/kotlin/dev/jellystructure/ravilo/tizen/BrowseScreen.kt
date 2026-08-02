package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** R189 milestone 2 — Movies/Series grid (`GET /api/tv/browse`), with a genre facet sidebar
 *  (`GET /api/tv/facets`). Focus regions: nav bar -> genre sidebar -> grid, left/right within the
 *  sidebar/grid, matching `NavBar`'s documented handoff contract. */
class BrowseScreen(private val kind: MediaKind) : Screen {
    private lateinit var container: HTMLElement
    private lateinit var navBar: NavBar
    private var items: List<MediaCard> = emptyList()
    private var genres: List<String> = emptyList()
    private var selectedGenre: String? = null

    // Region: 0 = genre sidebar, 1 = grid
    private var region = 0
    private var genreIndex = 0
    private var gridIndex = 0
    private val columns = 6

    override fun mount(container: HTMLElement) {
        this.container = container
        container.child("div", "browse-screen") { child("div", "loading", "Loading…") }
        app.scope.launch {
            val facets = runCatching { app.api.getFacets(kind.name) }.getOrNull()
            genres = facets?.genres?.map { it.name } ?: emptyList()
            loadGrid()
        }
    }

    private fun loadGrid() {
        app.scope.launch {
            runCatching { app.api.browse(kind = kind.name, genre = selectedGenre) }
                .onSuccess { result -> items = result.items; render() }
                .onFailure { renderError() }
        }
    }

    private fun render() {
        container.clear()
        navBar = NavBar(
            if (kind == MediaKind.MOVIE) NavBar.NavTab.MOVIES else NavBar.NavTab.SERIES,
            onNavigate = { tab ->
                when (tab) {
                    NavBar.NavTab.HOME -> app.show(HomeScreen(), replaceStack = true)
                    NavBar.NavTab.MOVIES -> if (kind != MediaKind.MOVIE) app.show(BrowseScreen(MediaKind.MOVIE), replaceStack = true)
                    NavBar.NavTab.SERIES -> if (kind != MediaKind.SERIES) app.show(BrowseScreen(MediaKind.SERIES), replaceStack = true)
                }
            },
            onSearch = { app.show(SearchScreen()) },
            onProfile = { app.show(ProfileMenuScreen()) },
        )
        navBar.mount(container)

        val body = container.child("div", "browse-body")
        if (genres.isNotEmpty()) {
            val sidebar = body.child("div", "genre-sidebar")
            sidebar.child("div", "genre-item") { setAttribute("data-genre-index", "0"); textContent = "All" }
            for ((i, g) in genres.withIndex()) {
                sidebar.child("div", "genre-item") { setAttribute("data-genre-index", (i + 1).toString()); textContent = g }
            }
        }
        val grid = body.child("div", "poster-grid")
        for ((i, card) in items.withIndex()) {
            grid.child("div", "tile grid-tile") {
                setAttribute("data-grid-index", i.toString())
                child("img", "poster") { setAttribute("src", card.posterUrl ?: "") }
                if (card.watched) child("div", "badge-watched", "✓")
                child("div", "tile-title", card.title)
            }
        }
        highlight()
    }

    private fun highlight() {
        val genreEls = container.querySelectorAll(".genre-item")
        for (i in 0 until genreEls.length) {
            val el = genreEls.item(i) as HTMLElement
            el.classList.toggle("focused", !navBar.hasFocus() && region == 0 && i == genreIndex)
        }
        val tiles = container.querySelectorAll(".grid-tile")
        for (i in 0 until tiles.length) {
            val el = tiles.item(i) as HTMLElement
            val focused = !navBar.hasFocus() && region == 1 && i == gridIndex
            el.classList.toggle("focused", focused)
            if (focused) el.scrollIntoView()
        }
    }

    private fun renderError() {
        container.clear()
        container.child("div", "browse-screen") { child("p", "error", "Couldn't load this list.") }
    }

    override fun onKey(ev: KeyboardEvent): Boolean {
        if (navBar.hasFocus()) {
            return navBar.onKey(ev) { region = if (genres.isNotEmpty()) 0 else 1; highlight() }
        }
        when (ev.key) {
            "ArrowUp" -> {
                navBar.focusNav(); highlight(); return true
            }
            "ArrowRight" -> {
                if (region == 0) { region = 1; highlight() }
                else if (gridIndex < items.size - 1 && (gridIndex + 1) % columns != 0) { gridIndex++; highlight() }
                return true
            }
            "ArrowLeft" -> {
                if (region == 1 && gridIndex % columns == 0) { region = 0; highlight() }
                else if (region == 1 && gridIndex > 0) { gridIndex--; highlight() }
                return true
            }
            "ArrowDown" -> {
                if (region == 0) {
                    if (genreIndex < genres.size) { genreIndex++; highlight() }
                } else if (gridIndex + columns < items.size) { gridIndex += columns; highlight() }
                return true
            }
            "Enter" -> {
                if (region == 0) {
                    selectedGenre = if (genreIndex == 0) null else genres[genreIndex - 1]
                    gridIndex = 0
                    loadGrid()
                } else {
                    items.getOrNull(gridIndex)?.let { app.show(DetailScreen(it)) }
                }
                return true
            }
        }
        return false
    }
}
