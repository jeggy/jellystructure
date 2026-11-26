package dev.jellystructure.ravilo.screen

import org.w3c.dom.HTMLElement

/**
 * DEB-5487 (FR-39, FR-41): where a page and its rails rest, and the one write that moves them.
 *
 * A Home feed, a channel's feed, a grid and the search results are one `.lane-page` that `#shell-body` no longer scrolls; a
 * rail is a `.rail-track` inside a clipping `.home-rail` / `.detail-rail`. Both move by an opacity-free `transform`
 * transition (FR-14), and each remembers the value it was last sent to in `data-ty` / `data-tx`. Layout (`offsetTop`,
 * `offsetLeft`) ignores transforms, so a tile's *resting* position is its layout position plus those remembered values:
 * that is what the D-pad and the image cap judge, never the middle of a transition.
 */
internal object ScreenLane {
    /** Where the focused row's tiles start, from the top of the screen (Android TV's `EdgeBand.HOME`, 124 dp at scale 2). */
    const val LINE_PX = 248.0
    /** The left gutter a rail moves a cut tile to: the body's inset. */
    const val GUTTER_PX = 72.0
    const val FOCUS_SCALE = 1.10
    /** The ring is 4 px and grows with the tile. */
    private const val RING_PX = 5.0
    private const val TY = "data-ty"
    private const val TX = "data-tx"

    class Box(val left: Double, val top: Double, val width: Double, val height: Double) {
        val right get() = left + width
        val bottom get() = top + height
        val centerX get() = left + width / 2
        val centerY get() = top + height / 2
    }

    fun pageOf(element: HTMLElement): HTMLElement? {
        var node: HTMLElement? = element
        while (node != null) {
            if (node.classList.contains("lane-page")) return node
            node = node.parentElement as? HTMLElement
        }
        return null
    }

    fun trackOf(element: HTMLElement): HTMLElement? =
        (element.parentElement as? HTMLElement)?.takeIf { it.classList.contains("rail-track") }

    /** Where [element] rests, in the shell's (viewport) coordinates; a target outside a page is wherever it is drawn now. */
    fun restBox(element: HTMLElement, shell: HTMLElement): Box {
        if (pageOf(element) != null) layoutBox(element, shell, motion = true)?.let { return it }
        return screenBox(element)
    }

    fun screenBox(element: HTMLElement): Box {
        val rect = element.getBoundingClientRect()
        return Box(rect.left, rect.top, rect.width, rect.height)
    }

    /** [element]'s layout box up to [shell]; with [motion] the transforms the page and rails have been sent to are added. */
    private fun layoutBox(element: HTMLElement, shell: HTMLElement, motion: Boolean): Box? {
        var node: HTMLElement = element
        var x = 0.0
        var y = 0.0
        while (node != shell) {
            x += node.offsetLeft
            y += node.offsetTop
            val parent = node.offsetParent as? HTMLElement ?: return null
            if (motion && parent != shell) {
                if (parent.classList.contains("rail-track")) x += committed(parent, TX)
                if (parent.classList.contains("lane-page")) y += committed(parent, TY)
            }
            node = parent
        }
        return Box(x, y, element.offsetWidth.toDouble(), element.offsetHeight.toDouble())
    }

    /**
     * FR-39 and FR-41: send the page and the rail to where focusing [element] leaves them. One write each, so a press lands
     * once; a key pressed mid-move starts the next transition from where the last one is, and nothing queues.
     */
    fun settle(element: HTMLElement, shell: HTMLElement) {
        val page = pageOf(element)
        if (page != null) {
            val row = element.getAttribute("data-row")
            val onLine = (element.classList.contains("home-tile") || element.classList.contains("home-channel")) &&
                row != "browse-row-0" && row != "search-row-0"
            val top = if (onLine) layoutBox(element, shell, motion = false)?.top else null
            move(page, TY, "Y", if (top == null) 0.0 else minOf(0.0, LINE_PX - top))
        }
        trackOf(element)?.let { slide(element, it) }
    }

    /** FR-41: a rail moves only when the new tile would be cut, to the gutter at the start and to just fully visible at the end. */
    fun slide(element: HTMLElement, track: HTMLElement) {
        val rail = track.parentElement as? HTMLElement ?: return
        val width = rail.clientWidth.toDouble()
        val left = (track.offsetLeft + element.offsetLeft).toDouble()
        val size = element.offsetWidth.toDouble()
        val now = committed(track, TX)
        val reach = size * (FOCUS_SCALE - 1) / 2 + RING_PX
        val next = when {
            left + now < GUTTER_PX -> GUTTER_PX - left
            left + now + size + reach > width -> width - reach - size - left
            else -> now
        }
        move(track, TX, "X", next)
    }

    /** DEB-5695 (FR-59): where each of [mount]'s rails rests, by the row key its first tile carries (`data-row`), so a redraw can put them back. */
    fun railsOf(mount: HTMLElement): Map<String, Double> {
        val rails = mutableMapOf<String, Double>()
        val tracks = mount.getElementsByClassName("rail-track")
        for (index in 0 until tracks.length) {
            val track = tracks.item(index) as? HTMLElement ?: continue
            val row = (track.firstElementChild as? HTMLElement)?.getAttribute("data-row") ?: continue
            track.getAttribute(TX)?.toDoubleOrNull()?.let { rails[row] = it }
        }
        return rails
    }

    /** DEB-5695 (FR-59): sends the rail of [row] where it was, with no transition: a redraw is not a move. */
    fun restoreRail(mount: HTMLElement, row: String, value: Double) {
        val tracks = mount.getElementsByClassName("rail-track")
        for (index in 0 until tracks.length) {
            val track = tracks.item(index) as? HTMLElement ?: continue
            if ((track.firstElementChild as? HTMLElement)?.getAttribute("data-row") != row) continue
            track.setAttribute(TX, value.toString())
            track.style.transition = "none"
            track.style.transform = "translateX(${value}px)"
            track.getBoundingClientRect()
            track.style.transition = ""
        }
    }

    private fun committed(element: HTMLElement, attribute: String): Double = element.getAttribute(attribute)?.toDoubleOrNull() ?: 0.0

    /** A page or rail that has never been sent anywhere is drawn at its place with no transition: a redraw does not replay the move. */
    private fun move(element: HTMLElement, attribute: String, axis: String, value: Double) {
        val fresh = !element.hasAttribute(attribute)
        if (!fresh && committed(element, attribute) == value) return
        element.setAttribute(attribute, value.toString())
        if (fresh) element.style.transition = "none"
        element.style.transform = "translate$axis(${value}px)"
        if (fresh) {
            element.getBoundingClientRect()
            element.style.transition = ""
        }
    }
}
