package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.i18n.t
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** The same bar appears on every browsing route; there is no Discover or cast target. */
internal fun renderScreenNav(current: String, language: String) {
    for (name in listOf("home", "movies", "series", "search", "profile")) {
        val item = document.getElementById("nav-$name") as HTMLElement
        item.textContent = t("nav.$name", language)
        item.classList.toggle("cur", current == name)
    }
}

internal fun isBackKey(event: KeyboardEvent): Boolean =
    event.key == "Backspace" || event.key == "Escape" || event.key == "Exit" ||
        event.key == "XF86Back" || event.key == "GoBack" || event.keyCode == 10009
