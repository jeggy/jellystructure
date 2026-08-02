package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.Row
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** R189 FR-RV-TIZEN1-3 — renders `/api/tv/home` as a D-pad-navigable grid of poster tiles grouped by
 *  row, same feed every other Ravilo client renders (no new backend endpoint). Milestone 1 scope: no
 *  hero carousel, no channels nav, no Continue Watching special-casing beyond it being an ordinary row
 *  — see the phase spec's Out of scope. */
class HomeScreen : Screen {
    private var rows: List<Row> = emptyList()
    private var rowIndex = 0
    private var colIndex = 0
    private lateinit var container: HTMLElement

    override fun mount(container: HTMLElement) {
        this.container = container
        container.child("div", "home-screen") {
            child("div", "loading", "Loading…")
        }
        app.scope.launch {
            runCatching { app.api.getHome() }
                .onSuccess { feed -> rows = feed.rows.filter { it.items.isNotEmpty() }; render(feed) }
                .onFailure { renderError() }
        }
    }

    private fun render(feed: HomeFeed) {
        container.clear()
        val root = container.child("div", "home-screen")
        for ((ri, row) in rows.withIndex()) {
            root.child("div", "row-title", row.title)
            val rowEl = root.child("div", "row")
            for ((ci, card) in row.items.withIndex()) {
                rowEl.child("div", "tile") {
                    setAttribute("data-row", ri.toString())
                    setAttribute("data-col", ci.toString())
                    child("img", "poster") { setAttribute("src", card.posterUrl ?: "") }
                    child("div", "tile-title", card.title)
                }
            }
        }
        highlight()
    }

    private fun renderError() {
        container.clear()
        container.child("div", "home-screen") {
            child("p", "error", "Couldn't load the home screen. Check the connection and reload.")
        }
    }

    private fun highlight() {
        val tiles = container.querySelectorAll(".tile")
        for (i in 0 until tiles.length) {
            val t = tiles.item(i) as HTMLElement
            val r = t.getAttribute("data-row")?.toIntOrNull()
            val c = t.getAttribute("data-col")?.toIntOrNull()
            val focused = r == rowIndex && c == colIndex
            t.classList.toggle("focused", focused)
            if (focused) t.scrollIntoView()
        }
    }

    override fun onKey(ev: KeyboardEvent): Boolean {
        if (rows.isEmpty()) return false
        when (ev.key) {
            "ArrowRight" -> {
                val max = rows[rowIndex].items.size - 1
                if (colIndex < max) { colIndex++; highlight(); return true }
            }
            "ArrowLeft" -> {
                if (colIndex > 0) { colIndex--; highlight(); return true }
            }
            "ArrowDown" -> {
                if (rowIndex < rows.size - 1) {
                    rowIndex++
                    colIndex = colIndex.coerceAtMost(rows[rowIndex].items.size - 1)
                    highlight(); return true
                }
            }
            "ArrowUp" -> {
                if (rowIndex > 0) {
                    rowIndex--
                    colIndex = colIndex.coerceAtMost(rows[rowIndex].items.size - 1)
                    highlight(); return true
                }
            }
            "Enter" -> {
                val card: MediaCard = rows[rowIndex].items.getOrNull(colIndex) ?: return true
                app.show(DetailScreen(card))
                return true
            }
        }
        return false
    }
}
