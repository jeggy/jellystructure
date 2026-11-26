package dev.jellystructure.ravilo.screen

import dev.jellystructure.shared.tv.HomeFeed
import org.w3c.dom.HTMLElement

/**
 * DEB-5695 (DEB-5685, FR-59) — what a silent redraw of Home or a channel's feed keeps. Taken from the page on screen *before*
 * the new feed replaces the old one: the focused tile, each row's focus memory, and where each rail rests. The tile ids are
 * positional (`home-row-R-tile-T`), so each is carried to wherever its card is in the new feed — by its media id, else by its
 * index in the row, else (the row went) the first tile of the row now at that position. The hero slide lives in the route
 * and needs nothing here.
 */
internal class ScreenFeedKeep(private val old: HomeFeed, entry: ScreenRouteEntry, body: HTMLElement) {
    private val focusId = entry.focusId
    private val rowFocus = entry.rowFocus.toMap()
    /** By row id (`channels` for the channel rail), so a row that moved in the feed takes its rail with it. */
    private val rails: Map<String, Double> = ScreenLane.railsOf(body).mapKeys { (row, _) ->
        ROW.matchEntire(row)?.let { old.rows.getOrNull(it.groupValues[1].toInt())?.id } ?: row
    }

    /** Points [entry]'s focus and row memory at where their tiles are in [next]; a row that has gone keeps no memory. */
    fun retarget(entry: ScreenRouteEntry, next: HomeFeed) {
        entry.focusId = remap(focusId, next)
        entry.rowFocus.clear()
        for ((row, id) in rowFocus) {
            val key = if (ROW.matches(row)) rowIndex(row, next)?.let { "row-$it" } ?: continue else row
            entry.rowFocus[key] = remap(id, next)
        }
    }

    /** Sends each rail of the redrawn page back to where it rested; a row that has gone has none to send. */
    fun restoreRails(body: HTMLElement, next: HomeFeed) {
        for ((key, value) in rails) {
            val row = if (key == CHANNELS) key else next.rows.indexOfFirst { it.id == key }.takeIf { it >= 0 }?.let { "row-$it" } ?: continue
            ScreenLane.restoreRail(body, row, value)
        }
    }

    /** Where the row [key] (`row-N` of the old feed) is in [next], null when it has gone. */
    private fun rowIndex(key: String, next: HomeFeed): Int? {
        val gone = old.rows.getOrNull(ROW.matchEntire(key)!!.groupValues[1].toInt()) ?: return null
        return next.rows.indexOfFirst { it.id == gone.id }.takeIf { it >= 0 }
    }

    private fun remap(id: String, next: HomeFeed): String {
        TILE.matchEntire(id)?.let { m ->
            val r = m.groupValues[1].toInt()
            val t = m.groupValues[2].toInt()
            val row = old.rows.getOrNull(r)
            val at = row?.let { gone -> next.rows.indexOfFirst { it.id == gone.id } } ?: -1
            if (row == null || at < 0) {
                val near = r.coerceAtMost(next.rows.lastIndex)
                return if (near < 0) id else ScreenHome.tileId(near, 0)
            }
            val items = next.rows[at].items
            val found = items.indexOfFirst { it.id == row.items.getOrNull(t)?.id }
            return ScreenHome.tileId(at, if (found >= 0) found else t.coerceAtMost(items.lastIndex).coerceAtLeast(0))
        }
        CHANNEL.matchEntire(id)?.let { m ->
            val i = m.groupValues[1].toInt()
            if (next.channels.isEmpty()) return ScreenHome.tileId(0, 0)
            val found = next.channels.indexOfFirst { it.id == old.channels.getOrNull(i)?.id }
            return ScreenHome.channelId(if (found >= 0) found else i.coerceAtMost(next.channels.lastIndex))
        }
        return id
    }

    companion object {
        private const val CHANNELS = "channels"
        private val ROW = Regex("""^row-(\d+)$""")
        private val TILE = Regex("""^home-row-(\d+)-tile-(\d+)$""")
        private val CHANNEL = Regex("""^home-channel-(\d+)$""")

        /** Every image the redraw has drawn shows as it loads: the fade is for a load, and this is a redraw. */
        fun showImagesAtOnce(body: HTMLElement) {
            val images = body.getElementsByClassName("img-fade")
            for (index in 0 until images.length) {
                val image = images.item(index) as? HTMLElement ?: continue
                image.style.transition = "none"
                image.classList.add("loaded")
            }
        }
    }
}
