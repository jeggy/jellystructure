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
 * DEB-5685 (FR-57) — the empty field asks `search("")` once per visit and draws the movies and series it answers under
 * `search.suggestions_label`; a *Clear* button at the field's end (shown only while the field holds text) empties it; and a
 * query whose answer fails replaces the results with `error.generic` and *Retry*.
 *
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
    /** FR-57 — `search("")` is asked once per visit (this object lives as long as the route entry); failed or empty keeps the hint. */
    private var suggestions = emptyList<MediaCard>()
    private var suggestionsAsked = false
    /** FR-57 — the (trimmed) query whose answer failed, drawn as `error.generic` and Retry in place of results; null when none did. */
    private var failedQuery: String? = null

    fun render(mount: HTMLElement) {
        mount.textContent = ""
        val page = node("div", "browse-page search-page lane-page")
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
        val bar = node("div", "search-bar")
        bar.appendChild(field)
        val clear = node("div", "shell-target detail-btn search-clear")
        clear.id = CLEAR_ID
        clear.textContent = ScreenLanguage.t("search.clear")
        clear.setAttribute("data-row", "field")
        clear.setAttribute("tabindex", "-1")
        clear.hidden = query.isEmpty()
        bar.appendChild(clear)
        page.appendChild(bar)
        page.appendChild(node("div", "search-results").also { it.id = RESULTS_ID })
        mount.appendChild(page)
        drawResults()
        loadSuggestions()
    }

    /** FR-57 — OK on *Clear*: the field is empty, and the suggestions show. The router puts focus back in the field. */
    fun clear() {
        val field = document.getElementById(FIELD_ID) as? HTMLInputElement
        field?.value = ""
        typed("")
    }

    /** FR-57 — Retry asks for the same text again, at once. */
    fun retry() {
        val asked = failedQuery ?: return
        ask(asked, 0)
    }

    private fun typed(value: String) {
        query = value
        (document.getElementById(CLEAR_ID) as? HTMLElement)?.hidden = value.isEmpty()
        pending?.cancel()
        val asked = value.trim()
        if (asked.isEmpty()) {
            cards = emptyList(); answered = null; failedQuery = null
            drawResults()
            onResults()
            return
        }
        ask(asked, DEBOUNCE_MS)
    }

    private fun ask(asked: String, wait: Long) {
        pending?.cancel()
        pending = GlobalScope.launch {
            if (wait > 0) delay(wait)
            val result = runCatching { api.search(asked) }
            if (!isActive) return@launch
            val found = result.getOrNull()
            if (found == null) {
                // FR-57 — the previous query's results must not stay under text they do not answer.
                cards = emptyList(); answered = null; failedQuery = asked
            } else {
                cards = found.items.filter { it.kind == MediaKind.MOVIE || it.kind == MediaKind.SERIES }
                answered = asked; failedQuery = null
            }
            drawResults()
            onResults()
        }
    }

    private fun loadSuggestions() {
        if (suggestionsAsked) return
        suggestionsAsked = true
        GlobalScope.launch {
            val found = runCatching { api.search("") }.getOrNull() ?: return@launch
            suggestions = found.items.filter { it.kind == MediaKind.MOVIE || it.kind == MediaKind.SERIES }
            if (query.trim().isEmpty()) {
                drawResults()
                onResults()
            }
        }
    }

    /** Redraws only what is under the field, so the field keeps its caret and the IME stays up. */
    private fun drawResults() {
        val mount = document.getElementById(RESULTS_ID) as? HTMLElement ?: return
        mount.textContent = ""
        val asked = answered
        if (query.trim().isEmpty()) {
            if (suggestions.isEmpty()) {
                mount.appendChild(line("search-hint", ScreenLanguage.t("search.suggestions")))
            } else {
                mount.appendChild(line("browse-count", ScreenLanguage.t("search.suggestions_label")))
                drawGrid(mount, suggestions)
            }
            return
        }
        if (failedQuery != null) {
            mount.appendChild(line("browse-empty", ScreenLanguage.t("error.generic")))
            val retry = node("div", "shell-target detail-btn search-retry")
            retry.id = RETRY_ID
            retry.textContent = ScreenLanguage.t("action.retry")
            retry.setAttribute("data-row", "search-row-0")
            retry.setAttribute("data-action", "retry")
            retry.setAttribute("tabindex", "-1")
            mount.appendChild(retry)
            return
        }
        if (asked == null) {
            mount.appendChild(line("search-hint", ScreenLanguage.t("search.suggestions")))
            return
        }
        if (cards.isEmpty()) {
            mount.appendChild(line("browse-empty", t("search.empty", ScreenLanguage.lang, mapOf("query" to asked))))
            return
        }
        mount.appendChild(line("browse-count", if (cards.size == 1) ScreenLanguage.t("search.result_one")
            else t("search.results", ScreenLanguage.lang, mapOf("count" to cards.size.toString()))))
        drawGrid(mount, cards)
    }

    private fun drawGrid(mount: HTMLElement, shown: List<MediaCard>) {
        val grid = node("div", "browse-grid")
        shown.forEachIndexed { index, card ->
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
        const val CLEAR_ID = "search-clear"
        const val RETRY_ID = "search-retry"
        /** A target under the field: a result tile, or the failed search's Retry. */
        fun isResult(id: String) = id.startsWith(TILE_PREFIX) || id == RETRY_ID
        private const val RESULTS_ID = "search-results"
    }
}
