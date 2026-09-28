@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/*
 * Phase 278 — what the Music kind, the Album page and the Artist page share: the glyphs, the wordmark for a
 * missing cover (277 FR-277-3: the title on a gradient, plus a `--warn` dot on this side, where a missing cover is
 * a work item), the length formats, toasts and the pagebar menus. Classes are `mu-*` (design/app/music.css).
 */

internal const val MU_LOCK = """<svg viewBox="0 0 11 12"><rect x="1" y="5" width="9" height="6.2" rx="1.6" fill="currentColor"/><path d="M3.1 5V3.5a2.4 2.4 0 0 1 4.8 0V5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>"""
internal const val MU_LYR = """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 6h12M4 11h9M4 16h6"/><circle cx="17.5" cy="16.5" r="2.5"/><path d="M20 16.5V8l-2 1"/></svg>"""

internal val MU_TYPE_LABEL = mapOf("album" to "Album", "single" to "Single / EP", "compilation" to "Compilation", "live" to "Live", "soundtrack" to "Soundtrack")

/** The mockup's string hash (`h * 31 + c`, unsigned), so a title keeps its colour everywhere. */
private fun muHash(s: String): Long {
    var h = 0L
    for (c in s) h = (h * 31 + c.code) and 0xFFFFFFFFL
    return h
}

internal fun muWordmarkStyle(name: String): String {
    val h = (muHash(name) % 360).toInt()
    return "background:linear-gradient(150deg, hsl($h 46% 36%), hsl(${(h + 40) % 360} 52% 14%))"
}

internal fun muImageStyle(url: String): String = "background:#0c0e14 center/cover no-repeat url('${url.esc()}')"

internal fun muInitials(name: String): String =
    name.split(Regex("\\s+")).map { w -> w.filter { it.isLetter() } }.filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase()

/** `3:59`, `1:02:10`. */
internal fun muLen(ms: Long?): String {
    if (ms == null) return "—"
    val s = ((ms + 500) / 1000).toInt()
    val h = s / 3600; val m = s % 3600 / 60; val x = s % 60
    return (if (h > 0) "$h:${m.toString().padStart(2, '0')}" else "$m") + ":" + x.toString().padStart(2, '0')
}

/** `9 min`, `1 h 12 min`. */
internal fun muTotal(ms: Long): String {
    val m = ((ms + 30_000) / 60_000).toInt()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

internal fun muPlural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

/** A cell's corner chip: nothing for a plain match (FR-278-2). */
internal fun muMatchChip(match: String): String = when (match) {
    "unmatched" -> """<span class="mu-mchip w">unmatched</span>"""
    "needs_you" -> """<span class="mu-mchip w">needs you</span>"""
    "locked" -> """<span class="mu-mchip" title="Locked · won’t be re-matched">$MU_LOCK</span>"""
    else -> ""
}

/** Phase 283 (FR-283-3/6) — an album cell's chip: a flag first (it says why the rest went as it did), else the match
 *  chip with the matcher's own note as its tooltip. */
internal fun muAlbumChip(a: dev.jellystructure.model.MusicAlbumRow): String {
    if (a.flags.isNotEmpty()) {
        val why = a.flags.joinToString(" · ") { if (it == "shared_album") "Several folders say they are this album" else "The folder and the songs disagree" }
        return """<span class="mu-mchip w" title="${why.esc()}">check</span>"""
    }
    val chip = muMatchChip(a.match)
    val note = a.note ?: return chip
    return chip.replaceFirst("<span class=\"mu-mchip", "<span title=\"${note.esc()}\" class=\"mu-mchip")
}

/** Phase 283 — the album folder's name under a title that repeats on the page, so ten tiles are ten things. */
internal fun muRepeatedTitles(rows: List<dev.jellystructure.model.MusicAlbumRow>): Set<String> =
    rows.groupBy { it.title.lowercase() }.filterValues { it.size > 1 }.keys

internal fun muFolderLine(a: dev.jellystructure.model.MusicAlbumRow, repeated: Set<String>): String =
    if (a.title.lowercase() in repeated && a.folder != null) """<div class="sub mono" style="font-size:.66rem" title="${a.folder.esc()}">${a.folder.esc()}</div>""" else ""

/** An album cover or its wordmark (FR-277-3). [cls] is `mu-cov`; [extra] is appended to the style. */
internal fun muCoverHtml(title: String, url: String?, extraClass: String = "", chip: String = "", extraStyle: String = ""): String =
    if (url != null) """<div class="mu-cov $extraClass" style="${muImageStyle(url)}$extraStyle">$chip</div>"""
    else """<div class="mu-cov $extraClass" style="${muWordmarkStyle(title)}$extraStyle"><span class="mu-wm">${title.esc()}</span><i class="mu-nocov" title="No cover on disk — a task on this side"></i>$chip</div>"""

internal fun muToast(msg: String) {
    val t = document.createElement("div") as HTMLElement
    t.className = "toast"; t.textContent = msg
    document.body?.appendChild(t)
    window.setTimeout({ t.remove(); null }, 2600)
}

/** The pagebar's dropdowns and split buttons (media.html's idiom): a `.menu-btn` opens its menu; an item, a click
 *  outside or Esc closes. The document-level handlers are installed once. */
private var muMenusGlobal = false
internal fun muWireMenus(root: Element) {
    val wraps = root.querySelectorAll(".menu-wrap, .split")
    for (i in 0 until wraps.length) {
        val w = wraps.item(i) as? HTMLElement ?: continue
        (w.querySelector(".menu-btn") as? HTMLElement)?.addEventListener("click") { ev ->
            ev.stopPropagation()
            val open = w.classList.contains("open")
            muCloseMenus()
            if (!open) w.classList.add("open")
        }
        val items = w.querySelectorAll(".menu-item")
        for (j in 0 until items.length) (items.item(j) as? HTMLElement)?.addEventListener("click") { muCloseMenus() }
    }
    if (!muMenusGlobal) {
        muMenusGlobal = true
        document.addEventListener("click") { ev -> if ((ev.target as? Element)?.closest(".menu-wrap, .split") == null) muCloseMenus() }
        document.addEventListener("keydown") { ev -> if ((ev as? KeyboardEvent)?.key == "Escape") muCloseMenus() }
    }
}

internal fun muCloseMenus() {
    val open = document.querySelectorAll(".menu-wrap.open, .split.open")
    for (i in 0 until open.length) (open.item(i) as? HTMLElement)?.classList?.remove("open")
}

/** A multipart upload (`file`) through the browser; [done] gets whether the server said yes. */
internal fun muUpload(url: String, file: JsAny, done: (Boolean) -> Unit): Unit =
    js("{ const fd = new FormData(); fd.append('file', file); fetch(url, { method: 'POST', body: fd, credentials: 'same-origin' }).then(r => done(r.ok), () => done(false)); }")

/** The picked file of an `<input type=file>`, or null. */
internal fun muPickedFile(input: JsAny): JsAny? = js("(input.files && input.files.length) ? input.files[0] : null")

/** A small confirmation panel over the page (`mu-modal`); [onGo] runs on the primary button. */
internal fun muModal(html: String, onGo: () -> Unit) {
    document.getElementById("mu-modal")?.remove()
    val m = document.createElement("div") as HTMLElement
    m.id = "mu-modal"; m.className = "mu-modal on"
    m.innerHTML = """<div class="card">$html</div>"""
    document.body?.appendChild(m)
    m.addEventListener("click") { ev ->
        val t = ev.target as? Element ?: return@addEventListener
        when {
            t == m || t.closest("[data-m=no]") != null -> m.remove()
            t.closest("[data-m=go]") != null -> { m.remove(); onGo() }
        }
    }
}
