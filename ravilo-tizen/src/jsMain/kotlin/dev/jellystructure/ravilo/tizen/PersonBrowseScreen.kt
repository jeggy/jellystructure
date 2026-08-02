package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.Person
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/**
 * R190 §A/§B (Tizen) — OK on a cast/crew face opens this: the person's whole library filmography via
 * the same R187 `POST /tv/browse/seeded` mechanism the other Ravilo clients use (`cast_crew` condition,
 * [person.id] already the tmdbId stringified — see `Person`'s doc comment). No facet bar/sort here (this
 * client has none anywhere, matching [BrowseScreen]'s own scope) — a plain poster grid is the Tizen-
 * appropriate version FR-RV-PPL1-2 allows for a constrained client. No NavBar (reached only from a detail
 * face; Back returns to the detail, matching [LiveTvGuideScreen]'s own no-NavBar precedent).
 *
 * §C's Seerr overflow row is deliberately NOT built here: it opens the Seerr request flow, and the whole
 * Discover tab (which owns that flow) was explicitly scoped OUT of this Tizen build. Nothing in this
 * client can open a request detail today, so the row would have no destination — revisit if/when Discover
 * lands on Tizen.
 */
class PersonBrowseScreen(private val person: Person, private val sourceTitle: String) : Screen {
    private lateinit var container: HTMLElement
    private var items: List<MediaCard> = emptyList()
    private var gridIndex = 0
    private val columns = 6

    override fun mount(container: HTMLElement) {
        this.container = container
        container.child("div", "browse-screen") { child("div", "loading", "Loading…") }
        app.scope.launch {
            val query = ConditionGroup(children = listOf(Condition(facet = "cast_crew", op = "is_any_of", values = listOf(person.id))))
            runCatching { app.api.browseSeeded(query, null) }
                .onSuccess { resp -> items = resp.items.map { it.card }; render() }
                .onFailure { renderError() }
        }
    }

    private fun render() {
        container.clear()
        val root = container.child("div", "browse-screen") {
            child("div", "meta", sourceTitle)
            child("h1", "menu-title", person.name)
            person.role?.let { child("p", "meta", it) }
        }
        val grid = root.child("div", "poster-grid")
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
        val tiles = container.querySelectorAll(".grid-tile")
        for (i in 0 until tiles.length) {
            val el = tiles.item(i) as HTMLElement
            val focused = i == gridIndex
            el.classList.toggle("focused", focused)
            if (focused) el.scrollIntoView()
        }
    }

    private fun renderError() {
        container.clear()
        container.child("div", "browse-screen") { child("p", "error", "Couldn't load this person's titles.") }
    }

    override fun onKey(ev: KeyboardEvent): Boolean {
        if (items.isEmpty()) return false
        when (ev.key) {
            "ArrowRight" -> { if (gridIndex < items.size - 1 && (gridIndex + 1) % columns != 0) { gridIndex++; highlight() }; return true }
            "ArrowLeft" -> { if (gridIndex % columns != 0 && gridIndex > 0) { gridIndex--; highlight() }; return true }
            "ArrowDown" -> { if (gridIndex + columns < items.size) { gridIndex += columns; highlight() }; return true }
            "ArrowUp" -> { if (gridIndex - columns >= 0) { gridIndex -= columns; highlight() } else return app.back(); return true }
            "Enter" -> { items.getOrNull(gridIndex)?.let { app.show(DetailScreen(it)) }; return true }
            "Back", "Escape" -> return app.back()
        }
        return false
    }
}
