package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.TileShape
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * DEB-4662 (FR-8) — Search: a real `<input>` (so Tizen raises its IME) and the movies and series
 * `search` answers under it, requested [DEBOUNCE_MS] after the last keystroke. The route's entry owns
 * this object, so Back from a result's page redraws the same text and the same answer without asking
 * again. No people and no music in v1.
 */
internal class ScreenSearch(
    private val api: TvApiClient,
    private val tiles: ScreenHome,
    /** The results under the field changed while this route is showing. */
    private val onResults: () -> Unit,
) {
    private var query = ""
    private var cards = emptyList<MediaCard>()
    /** The (trimmed) query [cards] answers; null before the first answer. */
    private var answered: String? = null
    private var pending: Job? = null

    fun render(mount: HTMLElement) {
        mount.textContent = ""
        val page = node("div", "browse-page search-page")
        page.appendChild(node("h1", "browse-heading").also { it.textContent = ScreenLanguage.t("nav.search") })
        val field = node("input", "shell-target search-field") as HTMLInputElement
        field.id = FIELD_ID
        field.type = "text"
        field.value = query
        field.placeholder = ScreenLanguage.t("search.placeholder")
        field.setAttribute("data-row", "field")
        field.setAttribute("tabindex", "-1")
        field.setAttribute("autocomplete", "off")
        field.addEventListener("input", { typed(field.value) })
        page.appendChild(field)
        page.appendChild(node("div", "search-results").also { it.id = RESULTS_ID })
        mount.appendChild(page)
        drawResults()
    }

    private fun typed(value: String) {
        query = value
        pending?.cancel()
        val asked = value.trim()
        if (asked.isEmpty()) {
            cards = emptyList(); answered = null
            drawResults()
            return
        }
        pending = GlobalScope.launch {
            delay(DEBOUNCE_MS)
            val result = runCatching { api.search(asked) }
            if (!isActive) return@launch
            val found = result.getOrNull() ?: return@launch
            cards = found.items.filter { it.kind == MediaKind.MOVIE || it.kind == MediaKind.SERIES }
            answered = asked
            drawResults()
            onResults()
        }
    }

    /** Redraws only what is under the field, so the field keeps its caret and the IME stays up. */
    private fun drawResults() {
        val mount = document.getElementById(RESULTS_ID) as? HTMLElement ?: return
        mount.textContent = ""
        val asked = answered
        if (query.trim().isEmpty() || asked == null) {
            mount.appendChild(line("search-hint", ScreenLanguage.t("search.suggestions")))
            return
        }
        if (cards.isEmpty()) {
            mount.appendChild(line("browse-empty", t("search.empty", ScreenLanguage.lang, mapOf("query" to asked))))
            return
        }
        mount.appendChild(line("browse-count", if (cards.size == 1) ScreenLanguage.t("search.result_one")
            else t("search.results", ScreenLanguage.lang, mapOf("count" to cards.size.toString()))))
        val grid = node("div", "browse-grid")
        cards.forEachIndexed { index, card ->
            grid.appendChild(tiles.tile(card, "$TILE_PREFIX$index", "search-row-${index / 6}", TileShape.POSTER))
        }
        mount.appendChild(grid)
        tiles.updateImages(FIELD_ID)
    }

    private fun line(className: String, text: String) = node("p", className).also { it.textContent = text }

    private fun node(tag: String, className: String): HTMLElement =
        (document.createElement(tag) as HTMLElement).also { it.className = className }

    companion object {
        const val DEBOUNCE_MS = 400L
        const val FIELD_ID = "search-input"
        const val TILE_PREFIX = "search-tile-"
        private const val RESULTS_ID = "search-results"
    }
}
