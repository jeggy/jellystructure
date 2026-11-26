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
import kotlinx.browser.window
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

    /**
     * DEB-5698 (DEB-5685, FR-64) — one grid row: its tiles while it is within range of the focused row, else [gap], the empty box that
     * holds its place at the height the tiles had. [first] is where the row starts in the grid, for the rows after it to go before.
     */
    private class GridRow(var tiles: List<HTMLElement> = emptyList(), var gap: HTMLElement? = null) {
        val first: HTMLElement? get() = tiles.firstOrNull() ?: gap
    }

    private var pageNode: HTMLElement? = null
    private var grid: HTMLElement? = null
    private var countNode: HTMLElement? = null
    private val gridRows = mutableListOf<GridRow>()
    private var focusRow = 0
    /** A row's height as last measured (tile and its margin), 0 until a row has been laid out: the height of a box that never held tiles. */
    private var pitch = 0.0

    private fun countText(): String =
        if (total == 1) ScreenLanguage.t("browse.title_one")
        else t("browse.titles", ScreenLanguage.lang, mapOf("count" to total.toString()))

    /**
     * FR-64 — the focus is on [focusId]: the rows within three screens of its row are drawn, the others are boxes. Moves one row at a
     * time, so a press draws one row and empties one.
     */
    fun follow(focusId: String) {
        if (!focusId.startsWith(TILE_PREFIX)) return
        val row = (focusId.removePrefix(TILE_PREFIX).toIntOrNull() ?: return) / COLUMNS
        if (row == focusRow) return
        focusRow = row
        if (grid != null) sync()
    }

    fun render(mount: HTMLElement, fade: Boolean = false) {
        // FR-64 — a grid already drawn in this mount grows by its new cards: the tiles it holds are the same nodes.
        if (page > 0 && grid != null && pageNode?.parentElement === mount) {
            grow()
            return
        }
        mount.textContent = ""
        grid = null
        countNode = null
        gridRows.clear()
        val pageNode = node("div", "browse-page lane-page")
        this.pageNode = pageNode
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
            val count = node("p", "browse-count").also { it.textContent = countText() }
            countNode = count
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
            this.grid = grid
            repeat(rowCount()) { gridRows.add(GridRow()) }
            pageNode.appendChild(grid)
        }
        mount.appendChild(pageNode)
        if (grid != null) sync()
    }

    /** FR-64 — the next page's cards go after the drawn ones; nothing already in the grid is built again. */
    private fun grow() {
        countNode?.textContent = countText()
        val old = gridRows.size
        // A last row that was short is completed, or, as a box, drawn whole when it next comes within range.
        gridRows.lastOrNull()?.let { last ->
            if (last.tiles.isNotEmpty() && last.tiles.size < rowSize(old - 1)) fill(old - 1)
        }
        repeat(rowCount() - old) { gridRows.add(GridRow()) }
        sync()
    }

    private fun rowCount() = (cards.size + COLUMNS - 1) / COLUMNS

    /** How many cards row [row] holds: six, or what is left of the last. */
    private fun rowSize(row: Int) = minOf(COLUMNS, cards.size - row * COLUMNS)

    /** Rows within three screens of the focused one; a screen is the rows that fit the window, rounded up, so the span is never short. */
    private fun reach(): Int = if (pitch > 0) 3 * kotlin.math.ceil(window.innerHeight / pitch).toInt().coerceAtLeast(1) else 3 * FALLBACK_ROWS_PER_SCREEN

    /**
     * FR-64 — draws the rows within range of the focused one, and holds the place of every other with a box of the height its tiles had,
     * so the scroll position and the spatial moves of FR-4 are the same as with every tile drawn. The rows' height is measured from the
     * first one laid out; before that the rows beyond range are simply not there yet, and the second pass puts their boxes in.
     */
    private fun sync() {
        val low = focusRow - reach()
        val high = focusRow + reach()
        for (row in gridRows.indices) {
            val wanted = row in low..high
            val held = gridRows[row]
            if (wanted && held.tiles.isEmpty()) fill(row)
            else if (!wanted && held.tiles.isNotEmpty() && pitch > 0) hold(row)
            else if (!wanted && held.gap == null && held.tiles.isEmpty() && pitch > 0) hold(row)
        }
        if (pitch <= 0 && measure()) sync()
    }

    /** The height of a row of tiles including the margin under it, from the first drawn tile; false while nothing is laid out yet. */
    private fun measure(): Boolean {
        val tile = gridRows.firstOrNull { it.tiles.isNotEmpty() }?.tiles?.first() ?: return false
        val height = rowHeight(listOf(tile))
        if (height <= 0) return false
        pitch = height
        return true
    }

    private fun rowHeight(tiles: List<HTMLElement>): Double {
        val tallest = tiles.maxOf { it.offsetHeight.toDouble() }
        if (tallest <= 0) return 0.0
        val style = window.getComputedStyle(tiles.first())
        return tallest + (style.marginTop.removeSuffix("px").toDoubleOrNull() ?: 0.0) + (style.marginBottom.removeSuffix("px").toDoubleOrNull() ?: 0.0)
    }

    /** The element the rows after [row] start at, which row [row] goes before; null when it is the last placed one. */
    private fun anchor(row: Int): HTMLElement? {
        for (next in row + 1 until gridRows.size) gridRows[next].first?.let { return it }
        return null
    }

    /** Row [row] as tiles: built from its cards (or topped up, when it is a drawn short row that grew), in the place its box held. */
    private fun fill(row: Int) {
        val grid = grid ?: return
        val held = gridRows[row]
        val from = row * COLUMNS + held.tiles.size
        val to = row * COLUMNS + rowSize(row)
        val before = if (held.tiles.isEmpty()) held.gap ?: anchor(row) else anchor(row)
        val isContinue = rowSeed?.row?.kind == RowKind.CONTINUE
        val made = (from until to).map { index ->
            // FR-55 — a Continue Watching grid's tiles are the row's: the episode badge and FR-5's progress bar under the same switch.
            tiles.tile(cards[index], "browse-tile-$index", "browse-row-${index / COLUMNS}", TileShape.POSTER,
                isContinue && showProgress(), if (isContinue) RowKind.CONTINUE else null)
        }
        made.forEach { tile -> if (before != null) grid.insertBefore(tile, before) else grid.appendChild(tile) }
        held.gap?.let { grid.removeChild(it) }
        held.gap = null
        held.tiles = held.tiles + made
    }

    /** Row [row] as a box: the tiles go, and an empty box of the height they had takes their place. */
    private fun hold(row: Int) {
        val grid = grid ?: return
        val held = gridRows[row]
        val height = if (held.tiles.isNotEmpty()) rowHeight(held.tiles).takeIf { it > 0 } ?: pitch else pitch
        val box = held.gap ?: node("div", "browse-gap")
        box.style.height = "${height}px"
        if (box.parentNode == null) grid.insertBefore(box, held.first ?: anchor(row))
        held.tiles.forEach { grid.removeChild(it) }
        held.tiles = emptyList()
        held.gap = box
    }

    private fun node(tag: String, className: String): HTMLElement =
        (document.createElement(tag) as HTMLElement).also { it.className = className }

    private companion object {
        const val TILE_PREFIX = "browse-tile-"
        /** `.browse-grid .home-tile` is 16.666 % wide: six to a row. */
        const val COLUMNS = 6
        /** A screen's rows until the first row has been laid out and measured (a 1080-line screen shows about three). */
        const val FALLBACK_ROWS_PER_SCREEN = 3
    }
}
