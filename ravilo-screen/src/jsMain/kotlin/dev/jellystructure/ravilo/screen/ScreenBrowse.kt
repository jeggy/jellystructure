package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.SearchResults
import dev.jellystructure.shared.tv.TileShape
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/** FR-6: the same paged browse endpoint and 60-card page as Android TV. */
internal class ScreenBrowse(val kind: String, private val tiles: ScreenHome) {
    private val cards = mutableListOf<MediaCard>()
    var page = 0
        private set
    var loading = false
    var complete = false
        private set
    /** DEB-4893 (FR-20) — the first page failed to load: the page shows the failure and Retry, not a skeleton that never ends. */
    var failed = false
    private var total = 0

    fun append(result: SearchResults) {
        page++
        cards.addAll(result.items)
        total = result.total
        complete = result.items.size < 60 || cards.size >= total
    }

    fun nearEnd(focusId: String): Boolean {
        val index = focusId.removePrefix("browse-tile-").toIntOrNull() ?: return false
        return page > 0 && !loading && !complete && index >= cards.size - 12
    }

    fun render(mount: HTMLElement, fade: Boolean = false) {
        mount.textContent = ""
        val pageNode = node("div", "browse-page lane-page")
        val key = if (kind == "mylist") "nav.my_list" else "nav.${if (kind == "movie") "movies" else "series"}"
        pageNode.appendChild(node("h1", "browse-heading").also { it.textContent = ScreenLanguage.t(key) })
        if (page == 0) {
            // FR-20 — the real heading stays (it is the page's title, not a stand-in); under it a skeleton, or the failure and Retry.
            pageNode.appendChild(if (failed) ScreenSkeleton.failure() else ScreenSkeleton.grid())
        } else {
            val count = node("p", "browse-count").also {
                it.textContent = if (total == 1) ScreenLanguage.t("browse.title_one")
                    else t("browse.titles", ScreenLanguage.lang, mapOf("count" to total.toString()))
            }
            pageNode.appendChild(count)
            if (fade) ScreenSkeleton.fadeIn(count)
            if (kind == "mylist" && total == 0) {
                pageNode.appendChild(node("p", "browse-empty").also {
                    it.textContent = t("browse.mylist_empty_hint", ScreenLanguage.lang,
                        mapOf("button" to "+ ${ScreenLanguage.t("nav.my_list")}"))
                })
            }
            val grid = node("div", "browse-grid")
            if (fade) ScreenSkeleton.fadeIn(grid)
            cards.forEachIndexed { index, card ->
                grid.appendChild(tiles.tile(card, "browse-tile-$index", "browse-row-${index / 6}", TileShape.POSTER))
            }
            pageNode.appendChild(grid)
        }
        mount.appendChild(pageNode)
    }

    private fun node(tag: String, className: String): HTMLElement =
        (document.createElement(tag) as HTMLElement).also { it.className = className }
}
