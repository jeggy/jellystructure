package dev.jellystructure.ui

import org.w3c.dom.HTMLElement

/**
 * DEB-5688 (FR-3) — shows or hides a popup that is opened by an inline style, and marks it `pop-open` while it is shown.
 * wf.css lifts the card, box or section holding a `pop-open` element above the cards after it (every card's
 * `backdrop-filter` makes it its own stacking context, so the popup's own z-index cannot do that). A popup opened by
 * setting `style.display` calls this instead of setting it directly; one opened by a class it already has needs nothing.
 */
internal fun HTMLElement.setPopup(show: Boolean, display: String = "block") {
    style.display = if (show) display else "none"
    if (show) classList.add("pop-open") else classList.remove("pop-open")
}
