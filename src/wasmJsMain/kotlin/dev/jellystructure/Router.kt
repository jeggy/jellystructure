package dev.jellystructure

import kotlinx.browser.window

object Router {
    fun current(): String = window.location.hash.removePrefix("#").ifEmpty { "/" }

    fun navigate(path: String) {
        window.location.hash = "#$path"
    }

    fun init(handler: (String) -> Unit) {
        window.addEventListener("hashchange") {
            handler(current())
        }
    }
}
