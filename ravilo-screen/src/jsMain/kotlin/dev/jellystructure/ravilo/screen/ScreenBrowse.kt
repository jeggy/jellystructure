package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.Person
import dev.jellystructure.shared.tv.SearchResults
import dev.jellystructure.shared.tv.SeededBrowseResponse
import dev.jellystructure.shared.tv.TileShape
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * DEB-5631 (FR-49) — a person page's seed: Android TV's `openPersonBrowse`. [source] is the title the face was on (the breadcrumb).
 * The query is `cast_crew is_any_of [person.id]`, no media kind.
 */
internal class ScreenPersonSeed(val person: Person, val source: String) {
    val query = ConditionGroup(children = listOf(Condition(facet = "cast_crew", op = "is_any_of", values = listOf(person.id))))
}

/** FR-6: the same paged browse endpoint and 60-card page as Android TV. A [seed] makes it FR-49's person page: one seeded read, no paging. */
internal class ScreenBrowse(val kind: String, private val tiles: ScreenHome, val seed: ScreenPersonSeed? = null) {
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

    /** FR-49 — the seeded endpoint answers the whole matching set at once, so it is one page and the grid is complete. */
    fun appendAll(result: SeededBrowseResponse) {
        page++
        cards.addAll(result.items.map { it.card })
        total = result.total
        complete = true
    }

    fun nearEnd(focusId: String): Boolean {
        val index = focusId.removePrefix("browse-tile-").toIntOrNull() ?: return false
        return page > 0 && !loading && !complete && index >= cards.size - 12
    }

    fun render(mount: HTMLElement, fade: Boolean = false) {
        mount.textContent = ""
        val pageNode = node("div", "browse-page lane-page")
        if (seed != null) {
            // FR-49 — breadcrumb (the title the face was on), the person's name, the role line, then the count; R187 FR-RV-BROWSE1-3.
            pageNode.appendChild(node("p", "browse-crumb").also { it.textContent = seed.source })
            pageNode.appendChild(node("h1", "browse-heading").also { it.textContent = seed.person.name })
            seed.person.role?.takeIf { it.isNotBlank() }?.let { role -> pageNode.appendChild(node("p", "browse-role").also { it.textContent = role }) }
        } else {
            val key = if (kind == "mylist") "nav.my_list" else "nav.${if (kind == "movie") "movies" else "series"}"
            pageNode.appendChild(node("h1", "browse-heading").also { it.textContent = ScreenLanguage.t(key) })
        }
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
            if (seed != null && total == 0) {
                pageNode.appendChild(node("p", "browse-empty").also { it.textContent = ScreenLanguage.t("browse.empty_channel") })
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
