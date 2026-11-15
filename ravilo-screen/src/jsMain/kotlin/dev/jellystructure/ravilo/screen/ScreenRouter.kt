package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.ravilo.receiver.ReceiverStrings
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** Every Back-stack entry owns both its focused element and its last focused tile per row. */
internal data class ScreenRouteEntry(
    val name: String,
    var focusId: String,
    val rowFocus: MutableMap<String, String> = mutableMapOf(),
)

/** The DOM shell stays mounted while R264's player covers it. */
internal class ScreenShell {
    private val root get() = document.getElementById("shell") as HTMLElement
    private val body get() = document.getElementById("shell-body") as HTMLElement
    private val entries = mutableListOf(ScreenRouteEntry("home", "shell-page-target"))
    private val focus = ScreenFocus({ root }, { entries.last() })
    private var visible = false
    private var player = false

    fun showHome() {
        visible = true
        if (!player) {
            root.classList.add("on")
            render()
        }
    }

    fun showPlayer() {
        if (!visible) return
        if (!player) entries.add(ScreenRouteEntry("player", ""))
        player = true
        root.classList.remove("on")
    }

    fun returnFromPlayer() {
        if (!visible) return
        if (player && entries.last().name == "player") entries.removeAt(entries.lastIndex)
        player = false
        root.classList.add("on")
        render()
    }

    fun handleKey(event: KeyboardEvent): Boolean {
        if (!visible || player) return false
        when {
            isBackKey(event) -> back()
            event.key == "Enter" -> activate()
            event.key == "ArrowLeft" -> focus.move(-1, 0)
            event.key == "ArrowRight" -> focus.move(1, 0)
            event.key == "ArrowUp" -> focus.move(0, -1)
            event.key == "ArrowDown" -> focus.move(0, 1)
            else -> return false
        }
        event.preventDefault()
        return true
    }

    /** Later pieces replace the placeholder with server-owned content and call this after rendering. */
    fun refocus() = focus.refocus()

    private fun render() {
        val current = entries.last()
        body.textContent = ""
        val placeholder = document.createElement("div") as HTMLElement
        placeholder.id = "shell-page-target"
        placeholder.className = "shell-target shell-placeholder"
        placeholder.setAttribute("data-row", "body")
        placeholder.setAttribute("tabindex", "-1")
        placeholder.textContent = t("nav.${current.name}", ReceiverStrings.lang)
        body.appendChild(placeholder)
        renderScreenNav(current.name, ReceiverStrings.lang)
        focus.refocus()
    }

    private fun activate() {
        val focused = entries.last().focusId
        if (!focused.startsWith("nav-")) return
        val destination = focused.removePrefix("nav-")
        if (entries.last().name == destination) {
            focus.focus(body.firstChild as HTMLElement)
            return
        }
        entries.add(ScreenRouteEntry(destination, "shell-page-target", mutableMapOf("bar" to focused)))
        render()
    }

    private fun back() {
        val current = entries.last()
        if (entries.size > 1) {
            entries.removeAt(entries.lastIndex)
            render()
            return
        }
        if (!current.focusId.startsWith("nav-")) {
            focus.focus(document.getElementById("nav-home") as HTMLElement)
            return
        }
        runCatching { TizenGlobal.application.getCurrentApplication().exit() }
            .onFailure { window.close() }
    }
}
