package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.MediaCard
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** R189 milestone 2 — search, with a hand-rolled on-screen D-pad keyboard (old Tizen has no reliable
 *  IME concept for a self-hosted app the way a browser's native `<input>` gets one on a TV — this app
 *  owns text entry itself, same reasoning as the rest of this client). Results update from
 *  `GET /api/tv/search` on a short debounce after each keystroke. */
class SearchScreen : Screen {
    private lateinit var container: HTMLElement
    private lateinit var navBar: NavBar
    private var query = ""
    private var results: List<MediaCard> = emptyList()
    private var debounceTimer: Int? = null

    // Region: 0 = keyboard, 1 = results
    private var region = 0
    private var keyIndex = 0
    private var resultIndex = 0
    private val keys = ("qwertyuiop" + "asdfghjkl" + "zxcvbnm").toList().map { it.toString() } + listOf("SPACE", "DEL")
    private val columns = 8

    override fun mount(container: HTMLElement) {
        this.container = container
        render()
    }

    private fun render() {
        container.clear()
        navBar = NavBar(NavBar.NavTab.HOME, onNavigate = { tab ->
            when (tab) {
                NavBar.NavTab.HOME -> app.show(HomeScreen(), replaceStack = true)
                NavBar.NavTab.MOVIES -> app.show(BrowseScreen(dev.jellystructure.shared.tv.MediaKind.MOVIE), replaceStack = true)
                NavBar.NavTab.SERIES -> app.show(BrowseScreen(dev.jellystructure.shared.tv.MediaKind.SERIES), replaceStack = true)
            }
        }, onSearch = {}, onProfile = { app.show(ProfileMenuScreen()) })
        navBar.mount(container)

        val body = container.child("div", "search-body")
        body.child("div", "search-query", query.ifEmpty { "Search…" })
        val kb = body.child("div", "keyboard")
        for ((i, k) in keys.withIndex()) {
            kb.child("div", "key" + if (k == "SPACE") " key-space" else if (k == "DEL") " key-del" else "") {
                setAttribute("data-key-index", i.toString())
                textContent = when (k) { "SPACE" -> "space"; "DEL" -> "⌫"; else -> k }
            }
        }
        val grid = body.child("div", "poster-grid search-results")
        for ((i, card) in results.withIndex()) {
            grid.child("div", "tile grid-tile") {
                setAttribute("data-result-index", i.toString())
                child("img", "poster") { setAttribute("src", card.posterUrl ?: "") }
                child("div", "tile-title", card.title)
            }
        }
        highlight()
    }

    private fun highlight() {
        val keyEls = container.querySelectorAll(".key")
        for (i in 0 until keyEls.length) {
            (keyEls.item(i) as HTMLElement).classList.toggle("focused", !navBar.hasFocus() && region == 0 && i == keyIndex)
        }
        val tiles = container.querySelectorAll(".grid-tile")
        for (i in 0 until tiles.length) {
            val el = tiles.item(i) as HTMLElement
            val focused = !navBar.hasFocus() && region == 1 && i == resultIndex
            el.classList.toggle("focused", focused)
            if (focused) el.scrollIntoView()
        }
    }

    private fun updateQueryDisplay() {
        (container.querySelector(".search-query") as? HTMLElement)?.textContent = query.ifEmpty { "Search…" }
    }

    private fun onType(key: String) {
        when (key) {
            "SPACE" -> query += " "
            "DEL" -> query = query.dropLast(1)
            else -> query += key
        }
        updateQueryDisplay()
        debounceTimer?.let { window.clearTimeout(it) }
        if (query.isBlank()) { results = emptyList(); refreshResults(); return }
        debounceTimer = window.setTimeout({ runSearch() }, 400)
    }

    private fun runSearch() {
        app.scope.launch {
            runCatching { app.api.search(query) }
                .onSuccess { results = it.items; refreshResults() }
        }
    }

    private fun refreshResults() {
        val grid = container.querySelector(".search-results") as? HTMLElement ?: return
        grid.clear()
        for ((i, card) in results.withIndex()) {
            grid.child("div", "tile grid-tile") {
                setAttribute("data-result-index", i.toString())
                child("img", "poster") { setAttribute("src", card.posterUrl ?: "") }
                child("div", "tile-title", card.title)
            }
        }
        resultIndex = 0
        highlight()
    }

    override fun onKey(ev: KeyboardEvent): Boolean {
        if (navBar.hasFocus()) return navBar.onKey(ev) { region = 0; highlight() }
        when (ev.key) {
            "ArrowUp" -> {
                if (region == 1 && resultIndex < columns) { region = 0; highlight() }
                else if (region == 1) { resultIndex -= columns; highlight() }
                else { navBar.focusNav(); highlight() }
                return true
            }
            "ArrowDown" -> {
                if (region == 0) { region = 1; highlight() }
                else if (resultIndex + columns < results.size) { resultIndex += columns; highlight() }
                return true
            }
            "ArrowRight" -> {
                if (region == 0 && keyIndex < keys.size - 1) { keyIndex++; highlight() }
                else if (region == 1 && resultIndex < results.size - 1 && (resultIndex + 1) % columns != 0) { resultIndex++; highlight() }
                return true
            }
            "ArrowLeft" -> {
                if (region == 0 && keyIndex > 0) { keyIndex--; highlight() }
                else if (region == 1 && resultIndex % columns != 0 && resultIndex > 0) { resultIndex--; highlight() }
                return true
            }
            "Enter" -> {
                if (region == 0) onType(keys[keyIndex])
                else results.getOrNull(resultIndex)?.let { app.show(DetailScreen(it)) }
                return true
            }
        }
        return false
    }
}
