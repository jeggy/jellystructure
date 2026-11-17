package dev.jellystructure.ravilo.screen

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * DEB-4893 (DEB-4889, FR-20) — what a page draws while it loads: grey blocks in the places and at the sizes of what will replace
 * them, and no text. Each skeleton is ONE element (`.skeleton`) whose opacity pulses, so a 2019 set animates one layer, not one
 * per block (FR-14). The block where focus will land is a real `shell-target` named `shell-page-target`, so the router's handover
 * to the real target (DEB-4660), Back and Up behave as they did for the placeholder this replaces.
 */
internal object ScreenSkeleton {
    /** Home and a channel's feed: a hero block, then two rows of a heading bar and poster blocks with a caption bar each. */
    fun home(heroHeightPct: Int = 56): HTMLElement {
        val root = node("div", "skeleton skeleton-home")
        val hero = target("skel-block skel-hero")
        hero.style.height = "${heroHeightPct}vh"
        root.appendChild(hero)
        repeat(2) {
            val section = node("section", "home-section")
            section.appendChild(node("div", "skel-bar skel-heading"))
            val rail = node("div", "skel-rail")
            repeat(RAIL_BLOCKS) { rail.appendChild(tile()) }
            section.appendChild(rail)
            root.appendChild(section)
        }
        return root
    }

    /** A title's page, movie or series alike: the backdrop, the header's bars and two button blocks. */
    fun detail(): HTMLElement {
        val root = node("div", "detail skeleton skeleton-detail")
        root.appendChild(node("div", "detail-backdrop skel-backdrop"))
        val head = node("div", "detail-head")
        head.appendChild(node("div", "skel-bar skel-title"))
        head.appendChild(node("div", "skel-bar skel-meta"))
        val lines = node("div", "skel-overview")
        lines.appendChild(node("div", "skel-bar skel-line skel-line-long"))
        lines.appendChild(node("div", "skel-bar skel-line skel-line-short"))
        head.appendChild(lines)
        val actions = node("div", "detail-actions")
        actions.appendChild(target("skel-block detail-btn skel-btn skel-btn-primary", row = "actions"))
        actions.appendChild(node("div", "skel-block detail-btn skel-btn skel-btn-second"))
        head.appendChild(actions)
        root.appendChild(head)
        return root
    }

    /** Under a grid's real heading: a count bar and six columns of poster blocks. The first is the focus block. */
    fun grid(): HTMLElement {
        val root = node("div", "skeleton skeleton-grid")
        root.appendChild(node("div", "skel-bar skel-count"))
        val grid = node("div", "browse-grid")
        repeat(GRID_ROWS * 6) { index ->
            val tile = if (index == 0) target("home-tile", row = "body") else node("div", "home-tile")
            tile.appendChild(node("div", "tile-art skel-block"))
            tile.appendChild(node("div", "skel-bar skel-caption"))
            grid.appendChild(tile)
        }
        root.appendChild(grid)
        return root
    }

    /** A load that failed: the page's type, a title and a body line, and one Retry button that holds focus. */
    fun failure(): HTMLElement {
        val root = node("div", "load-failure")
        root.appendChild(text("h2", "load-failure-title", ScreenLanguage.t("error.play.unreachable.title")))
        root.appendChild(text("p", "load-failure-body", ScreenLanguage.t("error.play.unreachable.body")))
        val retry = text("div", "shell-target detail-btn load-failure-retry", ScreenLanguage.t("action.retry"))
        retry.id = FOCUS_ID
        retry.setAttribute("data-row", "body")
        retry.setAttribute("data-action", "retry")
        retry.setAttribute("tabindex", "-1")
        root.appendChild(retry)
        return root
    }

    /** Content that replaces a skeleton fades in over 220 ms (DEB-2204 FR-5). */
    fun fadeIn(element: HTMLElement?) { element?.classList?.add("arrive") }

    /** A hero or backdrop image fades in over its surface block once it has loaded, so a slow image never leaves a bare black area. */
    fun fadeOnLoad(img: HTMLElement): HTMLElement {
        img.classList.add("img-fade")
        img.addEventListener("load", { img.classList.add("loaded") })
        return img
    }

    private const val FOCUS_ID = "shell-page-target"
    private const val RAIL_BLOCKS = 10
    private const val GRID_ROWS = 3

    private fun tile(): HTMLElement {
        val tile = node("div", "home-tile")
        tile.appendChild(node("div", "tile-art skel-block"))
        tile.appendChild(node("div", "skel-bar skel-caption"))
        return tile
    }

    private fun target(className: String, row: String = "body"): HTMLElement {
        val block = node("div", "shell-target $className")
        block.id = FOCUS_ID
        block.setAttribute("data-row", row)
        block.setAttribute("tabindex", "-1")
        return block
    }

    private fun node(tag: String, className: String): HTMLElement =
        (document.createElement(tag) as HTMLElement).also { it.className = className }

    private fun text(tag: String, className: String, value: String): HTMLElement =
        node(tag, className).also { it.textContent = value }
}
