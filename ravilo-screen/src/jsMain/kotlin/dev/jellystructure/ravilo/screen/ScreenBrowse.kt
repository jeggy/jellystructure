package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.shared.tv.BrowseCard
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.Person
import dev.jellystructure.shared.tv.Row
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.SearchResults
import dev.jellystructure.shared.tv.SeededBrowseResponse
import dev.jellystructure.shared.tv.SortDir
import dev.jellystructure.shared.tv.SortField
import dev.jellystructure.shared.tv.TileShape
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.browseOrder
import dev.jellystructure.shared.tv.initialBrowseSort
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * DEB-5631 (FR-49) — a person page's seed: Android TV's `openPersonBrowse`. [source] is the title the face was on (the breadcrumb).
 * The query is `cast_crew is_any_of [person.id]`, no media kind.
 */
internal class ScreenPersonSeed(val person: Person, val source: String) {
    val query = ConditionGroup(children = listOf(Condition(facet = "cast_crew", op = "is_any_of", values = listOf(person.id))))
}

/**
 * DEB-5690 (DEB-5685, FR-55) — a row's *See all*: Android TV's `SeededBrowseStore.load` for the row [row], on [channelId] (the channel
 * page's, null on Home). It reads once, the whole set, and orders it the way Android TV opens the same row.
 */
internal class ScreenRowSeed(val row: Row, private val channelId: String?) {
    private val sort: Pair<SortField, SortDir> =
        if (row.recommendations) SortField.SOURCE to SortDir.DESC else initialBrowseSort(row.sortBy, row.sortDescending)

    /** `recommendations` row → the viewer's list, the Continue row → every title it holds, else the row's own seed. */
    suspend fun read(api: TvApiClient): List<BrowseCard> {
        val items = when {
            row.recommendations -> api.recommendations(channelId).items
            row.kind == RowKind.CONTINUE -> api.continueAll(channelId).map { BrowseCard(card = it) }
            else -> api.browseSeeded(row.seedQuery, row.seedMediaKind).items
        }
        return browseOrder(items, sort.first, sort.second)
    }
}

/**
 * FR-6: the same paged browse endpoint and 60-card page as Android TV. A [seed] makes it FR-49's person page and a [rowSeed] FR-55's
 * row grid: one seeded read, no paging.
 */
internal class ScreenBrowse(
    val kind: String,
    private val tiles: ScreenHome,
    val seed: ScreenPersonSeed? = null,
    val rowSeed: ScreenRowSeed? = null,
    private val showProgress: () -> Boolean = { true },
) {
    private val cards = mutableListOf<MediaCard>()
    var page = 0
        private set
    var loading = false
    var complete = false
        private set
    /** DEB-4893 (FR-20) — the first page failed to load: the page shows the failure and Retry, not a skeleton that never ends. */
    var failed = false
    /** DEB-5696 (FR-60) — the cause of that failure, which says what the failure view offers. */
    var failedKind = ScreenLoadKind.UNREACHABLE
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

    /** FR-55 — a row's whole list, already in the order its row opens in; the count is what the grid draws. */
    fun appendRow(items: List<BrowseCard>) {
        page++
        cards.addAll(items.map { it.card })
        total = items.size
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
        } else if (rowSeed != null) {
            // FR-55 — the row's title, then the count; no breadcrumb.
            pageNode.appendChild(node("h1", "browse-heading").also { it.textContent = rowSeed.row.title })
        } else {
            val key = if (kind == "mylist") "nav.my_list" else "nav.${if (kind == "movie") "movies" else "series"}"
            pageNode.appendChild(node("h1", "browse-heading").also { it.textContent = ScreenLanguage.t(key) })
        }
        if (page == 0) {
            // FR-20 — the real heading stays (it is the page's title, not a stand-in); under it a skeleton, or the failure and Retry.
            pageNode.appendChild(if (failed) ScreenSkeleton.failure(failedKind) else ScreenSkeleton.grid())
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
            if (rowSeed != null && total == 0) {
                pageNode.appendChild(node("p", "browse-empty").also { it.textContent = ScreenLanguage.t("browse.empty") })
            }
            val grid = node("div", "browse-grid")
            if (fade) ScreenSkeleton.fadeIn(grid)
            cards.forEachIndexed { index, card ->
                // FR-55 — a Continue Watching grid's tiles are the row's: the episode badge and FR-5's progress bar under the same switch.
                val isContinue = rowSeed?.row?.kind == RowKind.CONTINUE
                grid.appendChild(tiles.tile(card, "browse-tile-$index", "browse-row-${index / 6}", TileShape.POSTER,
                    isContinue && showProgress(), if (isContinue) RowKind.CONTINUE else null))
            }
            pageNode.appendChild(grid)
        }
        mount.appendChild(pageNode)
    }

    private fun node(tag: String, className: String): HTMLElement =
        (document.createElement(tag) as HTMLElement).also { it.className = className }
}
