package dev.jellystructure.ravilo.tizen

import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** R189 milestone 2 — "My List", client-local (see `WatchlistStore`'s doc comment for why). No nav bar
 *  here (reached only via the profile menu, mirroring `ravilo-ui`'s own "My List" placement) — Back
 *  returns to wherever the profile menu was opened from. */
class MyListScreen : Screen {
    private lateinit var container: HTMLElement
    private var index = 0
    private val items get() = WatchlistStore.getAll()
    private val columns = 6

    override fun mount(container: HTMLElement) {
        this.container = container
        render()
    }

    private fun render() {
        container.clear()
        val root = container.child("div", "browse-screen") {
            child("h1", "menu-title", "My List")
        }
        val grid = root.child("div", "poster-grid")
        if (items.isEmpty()) {
            grid.child("p", "error", "Nothing here yet — add a title from its detail page.")
        }
        for ((i, card) in items.withIndex()) {
            grid.child("div", "tile grid-tile") {
                setAttribute("data-index", i.toString())
                child("img", "poster") { setAttribute("src", card.posterUrl ?: "") }
                child("div", "tile-title", card.title)
            }
        }
        highlight()
    }

    private fun highlight() {
        val tiles = container.querySelectorAll(".grid-tile")
        for (i in 0 until tiles.length) (tiles.item(i) as HTMLElement).classList.toggle("focused", i == index)
    }

    override fun onKey(ev: KeyboardEvent): Boolean {
        if (items.isEmpty()) return false
        when (ev.key) {
            "ArrowRight" -> { if (index < items.size - 1 && (index + 1) % columns != 0) { index++; highlight() }; return true }
            "ArrowLeft" -> { if (index % columns != 0 && index > 0) { index--; highlight() }; return true }
            "ArrowDown" -> { if (index + columns < items.size) { index += columns; highlight() }; return true }
            "ArrowUp" -> { if (index - columns >= 0) { index -= columns; highlight() }; return true }
            "Enter" -> { items.getOrNull(index)?.let { app.show(DetailScreen(it)) }; return true }
        }
        return false
    }
}
