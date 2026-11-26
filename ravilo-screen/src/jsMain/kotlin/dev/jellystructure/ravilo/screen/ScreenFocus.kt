package dev.jellystructure.ravilo.screen

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * FR-4's single visible target, spatial arrows and per-route row memory, and where the page and rails rest (DEB-5487, FR-39,
 * FR-41). Arrows are judged on resting positions ([ScreenLane.restBox]), so a key pressed mid-move goes where the last one was
 * going.
 */
internal class ScreenFocus(
    private val root: () -> HTMLElement,
    private val route: () -> ScreenRouteEntry,
    private val onFocus: (HTMLElement) -> Unit = {},
) {
    init {
        // FR-42: the image cap is judged where a move rests, so a move that has finished trims what it left behind.
        document.addEventListener("transitionend", { event ->
            val moved = event.target as? HTMLElement
            if (moved != null && (moved.classList.contains("rail-track") || moved.classList.contains("lane-page"))) {
                (document.getElementById(route().focusId) as? HTMLElement)?.let(onFocus)
            }
        })
    }

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
        // DEB-5651 (FR-53): an episode tile and the toggle under it are twins, so Up/Down between the two rows lands on the one beside it, not on the row's older pick.
        element.getAttribute("data-twin")?.let { twin -> document.getElementById(twin)?.getAttribute("data-row")?.let { current.rowFocus[it] = twin } }
        val shell = root()
        val body = shell.querySelector("#shell-body") as? HTMLElement
        val laneOn = body?.firstElementChild?.classList?.contains("lane-page") == true
        if (body != null) {
            body.classList.toggle("lane", laneOn)
            if (laneOn) body.scrollTop = 0.0
        }
        // Chromium 63 (Tizen 5.0) ignores preventScroll, so a page or rail the move owns is put back after the native scroll.
        element.asDynamic().focus(js("({preventScroll: true})"))
        val track = ScreenLane.trackOf(element)
        if (laneOn) body?.scrollTop = 0.0
        (track?.parentElement as? HTMLElement)?.scrollLeft = 0.0
        if (ScreenLane.pageOf(element) != null) {
            ScreenLane.settle(element, shell)
        } else if (track != null) {
            // A title's page keeps its native vertical scroll (FR-39); its episode rail slides like any other (FR-41).
            ScreenLane.slide(element, track)
            (track.parentElement as? HTMLElement)?.scrollIntoView(false)
        } else {
            element.scrollIntoView(false)
        }
        onFocus(element)
    }

    fun move(dx: Int, dy: Int) {
        val all = targets()
        val current = all.firstOrNull { it.id == route().focusId } ?: return
        val shell = root()
        val from = ScreenLane.restBox(current, shell)
        val fx = from.centerX
        val fy = from.centerY
        val currentRow = current.getAttribute("data-row")
        val directional = all.filter { candidate ->
            if (candidate == current) return@filter false
            val rect = ScreenLane.restBox(candidate, shell)
            val x = rect.centerX - fx
            val y = rect.centerY - fy
            // Up/Down leaves the row: a neighbour whose centre is only a pixel or two lower (cast faces whose labels differ in height)
            // is the same row, not the one below (DEB-5649).
            (dx == 0 || x * dx > 0) && (dy == 0 || y * dy > BAND_TOLERANCE_PX)
        }
        // The row above rests partly under the bar (FR-39), so its centre can sit higher than the bar's: inside a page, the page's own
        // targets are visited before the bar is.
        val page = ScreenLane.pageOf(current)
        val inPage = if (page != null && dy != 0) directional.filter { ScreenLane.pageOf(it) == page } else directional
        val reachable = inPage.ifEmpty { directional }
        val candidates = if (dx != 0) directional.filter { it.getAttribute("data-row") == currentRow } else nearestBand(reachable, fy)
        val rows = route().rowFocus
        if (dy > 0 && dx == 0 && (currentRow == "bar" || currentRow == "heroes")) firstRowTarget(all, candidates, fy, rows)?.let {
            focus(it)
            return
        }
        candidates.minByOrNull { candidate ->
            val rect = ScreenLane.restBox(candidate, shell)
            val x = rect.centerX - fx
            val y = rect.centerY - fy
            val row = candidate.getAttribute("data-row")
            val rowBonus = if (dy != 0 && row != null && row != currentRow && rows[row] == candidate.id) -10000.0 else 0.0
            val distance = if (dx != 0) kotlin.math.abs(x) + kotlin.math.abs(y) * 2 else kotlin.math.abs(y) + kotlin.math.abs(x) * 2
            distance + rowBonus
        }?.let(::focus)
    }

    /**
     * DEB-5487 FR-40: Down from the hero or the bar enters the row below on its remembered tile, else its first tile, the
     * leftmost one (Android TV's `firstRowFR` with `focusRestorer`), never the tile nearest across.
     */
    private fun firstRowTarget(
        all: List<HTMLElement>,
        band: List<HTMLElement>,
        fy: Double,
        remembered: Map<String, String>,
    ): HTMLElement? {
        val nearest = band.minByOrNull { element ->
            kotlin.math.abs(ScreenLane.restBox(element, root()).centerY - fy)
        } ?: return null
        if (!nearest.id.startsWith("home-")) return null
        val row = nearest.getAttribute("data-row") ?: return null
        val members = all.filter { it.getAttribute("data-row") == row }
        return members.firstOrNull { it.id == remembered[row] } ?: members.firstOrNull()
    }

    /**
     * Up/Down visits the next band of targets, never one beyond it (DEB-4888): the old nearest-by-distance pick weighed
     * the sideways gap double, so a short Continue Watching rail far from the focused avatar lost to a long rail below it
     * and the row was skipped. The band is the targets sharing the nearest vertical centre; the pick within it is the
     * remembered tile, else the nearest across.
     */
    private fun nearestBand(directional: List<HTMLElement>, fy: Double): List<HTMLElement> {
        fun gap(element: HTMLElement): Double {
            return kotlin.math.abs(ScreenLane.restBox(element, root()).centerY - fy)
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
