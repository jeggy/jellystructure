package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.Channel
import dev.jellystructure.shared.tv.HomeFeed
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** R189 milestone 2 — a channel page (`GET /api/tv/channel/{id}`): the same row-of-tiles shape as Home,
 *  scoped to the channel, no hero/channel-rail. Reached by selecting a channel button on Home. */
class ChannelScreen(private val channel: Channel) : Screen {
    private lateinit var container: HTMLElement
    private lateinit var navBar: NavBar
    private var feed: HomeFeed? = null
    private var rowIndex = 0
    private var colIndex = 0
    private val rows get() = feed?.rows?.filter { it.items.isNotEmpty() } ?: emptyList()

    override fun mount(container: HTMLElement) {
        this.container = container
        container.child("div", "browse-screen") { child("div", "loading", "Loading…") }
        app.scope.launch {
            runCatching { app.api.getChannel(channel.id) }
                .onSuccess { f -> feed = f; render() }
                .onFailure { renderError() }
        }
    }

    private fun render() {
        container.clear()
        navBar = NavBar(NavBar.NavTab.HOME, onNavigate = { tab ->
            when (tab) {
                NavBar.NavTab.HOME -> app.show(HomeScreen(), replaceStack = true)
                NavBar.NavTab.MOVIES -> app.show(BrowseScreen(dev.jellystructure.shared.tv.MediaKind.MOVIE), replaceStack = true)
                NavBar.NavTab.SERIES -> app.show(BrowseScreen(dev.jellystructure.shared.tv.MediaKind.SERIES), replaceStack = true)
            }
        }, onSearch = { app.show(SearchScreen()) }, onProfile = { app.show(ProfileMenuScreen()) })
        navBar.mount(container)

        val body = container.child("div", "home-body")
        body.child("h2", "channel-page-title", channel.name)
        for ((ri, row) in rows.withIndex()) {
            body.child("div", "row-title", row.title)
            val rowEl = body.child("div", "row")
            for ((ci, card) in row.items.withIndex()) {
                rowEl.child("div", "tile") {
                    setAttribute("data-row", ri.toString())
                    setAttribute("data-col", ci.toString())
                    child("img", "poster") { setAttribute("src", card.posterUrl ?: "") }
                    if (card.watched) child("div", "badge-watched", "✓")
                    child("div", "tile-title", card.title)
                }
            }
        }
        highlight()
    }

    private fun highlight() {
        val tiles = container.querySelectorAll(".tile")
        for (i in 0 until tiles.length) {
            val t = tiles.item(i) as HTMLElement
            val r = t.getAttribute("data-row")?.toIntOrNull()
            val c = t.getAttribute("data-col")?.toIntOrNull()
            val focused = !navBar.hasFocus() && r == rowIndex && c == colIndex
            t.classList.toggle("focused", focused)
            if (focused) t.scrollIntoView()
        }
    }

    private fun renderError() {
        container.clear()
        container.child("div", "browse-screen") { child("p", "error", "Couldn't load this channel.") }
    }

    override fun onKey(ev: KeyboardEvent): Boolean {
        if (feed == null) return false
        if (navBar.hasFocus()) return navBar.onKey(ev) { rowIndex = 0; colIndex = 0; highlight() }
        if (rows.isEmpty()) return false
        when (ev.key) {
            "ArrowRight" -> { val max = rows[rowIndex].items.size - 1; if (colIndex < max) { colIndex++; highlight() }; return true }
            "ArrowLeft" -> { if (colIndex > 0) { colIndex--; highlight() }; return true }
            "ArrowDown" -> {
                if (rowIndex < rows.size - 1) { rowIndex++; colIndex = colIndex.coerceAtMost(rows[rowIndex].items.size - 1); highlight() }
                return true
            }
            "ArrowUp" -> {
                if (rowIndex > 0) { rowIndex--; colIndex = colIndex.coerceAtMost(rows[rowIndex].items.size - 1); highlight() }
                else { navBar.focusNav(); highlight() }
                return true
            }
            "Enter" -> { rows[rowIndex].items.getOrNull(colIndex)?.let { app.show(DetailScreen(it)) }; return true }
        }
        return false
    }
}
