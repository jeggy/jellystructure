package dev.jellystructure.ravilo.tizen

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/** R189 — tiny direct-DOM helpers. No framework: old Tizen WebKit is the whole reason this app isn't
 *  Compose; a hand-rolled helper layer keeps screen code readable without pulling in a JS UI library
 *  whose own old-engine compatibility hasn't been vetted either. */

fun el(tag: String, className: String? = null, text: String? = null): HTMLElement {
    val e = document.createElement(tag) as HTMLElement
    if (className != null) e.className = className
    if (text != null) e.textContent = text
    return e
}

fun HTMLElement.child(tag: String, className: String? = null, text: String? = null, block: HTMLElement.() -> Unit = {}): HTMLElement {
    val e = el(tag, className, text)
    e.block()
    appendChild(e)
    return e
}

fun HTMLElement.clear() {
    while (firstChild != null) removeChild(firstChild!!)
}

fun input(type: String, placeholder: String, className: String? = null): HTMLInputElement {
    val i = document.createElement("input") as HTMLInputElement
    i.type = type
    i.placeholder = placeholder
    if (className != null) i.className = className
    return i
}

fun root(): HTMLElement = document.getElementById("app") as HTMLElement

/** Simple linear D-pad focus group: Up/Down (or Left/Right for a horizontal row) move an index,
 *  Enter/Return activates the focused element's `onSelect`. No Compose-style focus graph — Milestone
 *  1's screens are single lists/grids, not a general focus mesh. */
class FocusGroup(private val items: List<Pair<HTMLElement, () -> Unit>>, private val columns: Int = 1) {
    private var index = 0

    fun start() {
        if (items.isEmpty()) return
        highlight()
    }

    private fun highlight() {
        items.forEachIndexed { i, (elem, _) -> elem.classList.toggle("focused", i == index) }
        items.getOrNull(index)?.first?.scrollIntoView()
    }

    fun move(deltaIndex: Int): Boolean {
        val next = index + deltaIndex
        if (next < 0 || next >= items.size) return false
        index = next
        highlight()
        return true
    }

    fun activate() {
        items.getOrNull(index)?.second?.invoke()
    }

    fun columnsCount() = columns
}
