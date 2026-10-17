package dev.jellystructure.ui

import dev.jellystructure.copyToClipboard
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

// DEB-1670 — the admin's CSP is `script-src 'self' 'wasm-unsafe-eval' 'unsafe-eval'` with no `'unsafe-inline'`, so the
// browser refuses every inline `onclick="…"`. Markup carries data attributes and these document-level listeners do the
// work, so a rendered fragment needs no wiring and survives a re-render. scripts/check-inline-handlers.sh keeps an
// inline `on…=` from creeping back into src/wasmJsMain.
//
//   data-copy="<text>"        click → the text on the clipboard, the button reads "Copied!" for 1.5 s
//   data-toggle-el="<id>"        click → show/hide that element
//   data-hide-el="<id>"          click → hide that element
//   data-tabswitch="<group>:<id>"   click → show #<id> of the group, hide its siblings, restyle the buttons
//   data-hover="lift|card|fill"  hover → the card lift / border / fill the old onmouseover set
//   data-stop-click           click inside it never reaches an ancestor's own click listener (see clickIsInside)
//   img[data-fallback-initials]  on error → the image is replaced by the initials, on data-fallback-color

private const val TAB_ACTIVE = "padding:6px 14px;border:none;cursor:pointer;background:none;border-bottom:2px solid var(--hi);font-weight:600;color:var(--ink);font-size:.82rem;"
private const val TAB_INACTIVE = "padding:6px 14px;border:none;cursor:pointer;background:none;border-bottom:2px solid transparent;color:var(--ink-soft);font-size:.82rem;"

internal fun permTabActiveStyle() = TAB_ACTIVE
internal fun permTabInactiveStyle() = TAB_INACTIVE

private var installed = false

internal fun installDelegatedActions() {
    if (installed) return
    installed = true

    document.addEventListener("click") { ev ->
        val t = ev.target as? Element ?: return@addEventListener
        t.closest("[data-copy]")?.let { btn -> copyAndFlash(btn); return@addEventListener }
        t.closest("[data-toggle-el]")?.let { b ->
            val el = document.getElementById(b.getAttribute("data-toggle-el") ?: return@let) as? HTMLElement ?: return@let
            el.style.display = if (el.style.display == "none") "" else "none"
            return@addEventListener
        }
        t.closest("[data-hide-el]")?.let { b ->
            (document.getElementById(b.getAttribute("data-hide-el") ?: return@let) as? HTMLElement)?.style?.display = "none"
            return@addEventListener
        }
        t.closest("[data-tabswitch]")?.let { b -> switchTab(b); return@addEventListener }
    }

    document.addEventListener("mouseover") { ev -> hover(ev as? MouseEvent ?: return@addEventListener, true) }
    document.addEventListener("mouseout") { ev -> hover(ev as? MouseEvent ?: return@addEventListener, false) }

    installImageFallback()
}

/** True when the click landed inside a `[data-stop-click]` — a card's own click listener calls this and returns. */
internal fun clickIsInside(ev: org.w3c.dom.events.Event): Boolean =
    (ev.target as? Element)?.closest("[data-stop-click]") != null

private fun copyAndFlash(btn: Element) {
    val text = btn.getAttribute("data-copy") ?: return
    copyToClipboard(text)
    flashCopiedLabel(btn)
}

/** The "Copied!" flip, 1.5 s. A second click inside the window keeps the label it first found. */
private fun flashCopiedLabel(btn: Element) {
    val label = btn.getAttribute("data-copy-label") ?: (btn.textContent ?: "Copy").also { btn.setAttribute("data-copy-label", it) }
    btn.textContent = "Copied!"
    window.setTimeout({ btn.textContent = label; btn.removeAttribute("data-copy-label"); null }, 1500)
}

private fun switchTab(btn: Element) {
    val spec = btn.getAttribute("data-tabswitch") ?: return
    val group = spec.substringBefore(':')
    val target = spec.substringAfter(':')
    val all = document.querySelectorAll("[data-tabswitch^=\"$group:\"]")
    for (i in 0 until all.length) {
        val b = all.item(i) as? HTMLElement ?: continue
        val id = b.getAttribute("data-tabswitch")!!.substringAfter(':')
        (document.getElementById(id) as? HTMLElement)?.style?.display = if (id == target) "" else "none"
        b.setAttribute("style", if (id == target) TAB_ACTIVE else TAB_INACTIVE)
    }
}

private fun hover(ev: MouseEvent, over: Boolean) {
    val el = (ev.target as? Element)?.closest("[data-hover]") as? HTMLElement ?: return
    // moving between this element's own children is not leaving it
    val to = ev.relatedTarget as? Element
    if (!over && to != null && el.contains(to)) return
    val s = el.style
    fun set(vararg kv: Pair<String, String>) { for ((k, v) in kv) s.setProperty(k, v) }
    when (el.getAttribute("data-hover")) {
        "lift" -> if (over) set("transform" to "translateY(-2px)", "box-shadow" to "var(--shadow)", "border-color" to "var(--hi)")
                  else set("transform" to "", "box-shadow" to "var(--shadow-s)", "border-color" to "")
        "card" -> if (over) set("box-shadow" to "var(--shadow)", "border-color" to "var(--hi)")
                  else set("box-shadow" to "var(--shadow-s)", "border-color" to "")
        "fill" -> set("background" to if (over) "var(--fill-2)" else "")
    }
}

// `error` does not bubble, so this one listens in the capture phase.
private fun installImageFallback(): Unit = js("""(function(){
    document.addEventListener('error', function(e){
        var img = e.target;
        if (!img || !img.tagName || img.tagName !== 'IMG' || !img.hasAttribute('data-fallback-initials')) return;
        var p = img.parentElement;
        if (!p) return;
        p.style.background = img.getAttribute('data-fallback-color') || '';
        var initials = img.getAttribute('data-fallback-initials');
        img.remove();
        p.insertAdjacentText('beforeend', initials);
    }, true);
})()""")
