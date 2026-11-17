package dev.jellystructure.ravilo.screen

import org.w3c.dom.HTMLElement

/** FR-4's single visible target, spatial arrows, per-route row memory and scroll-into-view. */
internal class ScreenFocus(
    private val root: () -> HTMLElement,
    private val route: () -> ScreenRouteEntry,
    private val onFocus: (HTMLElement) -> Unit = {},
) {
    fun refocus() {
        val current = route()
        val visible = targets()
        val target = visible.firstOrNull { it.id == current.focusId }
            ?: visible.firstOrNull { it.id == "shell-page-target" }
            ?: visible.firstOrNull { it.getAttribute("data-row") != "bar" }
            ?: visible.firstOrNull()
        if (target != null) focus(target)
    }

    fun focus(element: HTMLElement) {
        for (target in targets()) target.classList.toggle("focused", target == element)
        val current = route()
        current.focusId = element.id
        element.getAttribute("data-row")?.let { current.rowFocus[it] = element.id }
        element.focus()
        element.scrollIntoView(false)
        onFocus(element)
    }

    fun move(dx: Int, dy: Int) {
        val all = targets()
        val current = all.firstOrNull { it.id == route().focusId } ?: return
        val from = current.getBoundingClientRect()
        val fx = from.left + from.width / 2
        val fy = from.top + from.height / 2
        val currentRow = current.getAttribute("data-row")
        val directional = all.filter { candidate ->
            if (candidate == current) return@filter false
            val rect = candidate.getBoundingClientRect()
            val x = rect.left + rect.width / 2 - fx
            val y = rect.top + rect.height / 2 - fy
            (dx == 0 || x * dx > 0) && (dy == 0 || y * dy > 0)
        }
        val candidates = if (dx != 0) directional.filter { it.getAttribute("data-row") == currentRow } else nearestBand(directional, fy)
        val rows = route().rowFocus
        candidates.minByOrNull { candidate ->
            val rect = candidate.getBoundingClientRect()
            val x = rect.left + rect.width / 2 - fx
            val y = rect.top + rect.height / 2 - fy
            val row = candidate.getAttribute("data-row")
            val rowBonus = if (dy != 0 && row != null && row != currentRow && rows[row] == candidate.id) -10000.0 else 0.0
            val distance = if (dx != 0) kotlin.math.abs(x) + kotlin.math.abs(y) * 2 else kotlin.math.abs(y) + kotlin.math.abs(x) * 2
            distance + rowBonus
        }?.let(::focus)
    }

    /**
     * Up/Down visits the next band of targets, never one beyond it (DEB-4888): the old nearest-by-distance pick weighed
     * the sideways gap double, so a short Continue Watching rail far from the focused avatar lost to a long rail below it
     * and the row was skipped. The band is the targets sharing the nearest vertical centre; the pick within it is the
     * remembered tile, else the nearest across.
     */
    private fun nearestBand(directional: List<HTMLElement>, fy: Double): List<HTMLElement> {
        fun gap(element: HTMLElement): Double {
            val rect = element.getBoundingClientRect()
            return kotlin.math.abs(rect.top + rect.height / 2 - fy)
        }
        val nearest = directional.minOfOrNull(::gap) ?: return directional
        return directional.filter { gap(it) <= nearest + BAND_TOLERANCE_PX }
    }

    private fun targets(): List<HTMLElement> {
        val nodes = root().getElementsByClassName("shell-target")
        val result = mutableListOf<HTMLElement>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? HTMLElement ?: continue
            val rect = element.getBoundingClientRect()
            if (rect.width > 0 && rect.height > 0) result.add(element)
        }
        return result
    }

    private companion object {
        const val BAND_TOLERANCE_PX = 24.0
    }
}
