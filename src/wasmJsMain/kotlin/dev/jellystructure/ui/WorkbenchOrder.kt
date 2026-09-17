package dev.jellystructure.ui

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.recencyKey
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowOrder
import dev.jellystructure.shared.tv.RowSort
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event

/**
 * Phase 225 (FR-225-9) — the row editor's **Order** section: a port of the design mockup's `orderHtml()`
 * (`design/app/ravilo-builders.js`, direction 2 · "Arrange the row") into the shipped Kotlin workbench.
 *
 * The preview is lined up by the SAME function the server serves the row with ([RowOrder.resolve]), so
 * what the admin arranges is what the TV shows. Everything is said in words — never "asc"/"desc".
 * State lives here; [Workbench] only opens it, feeds it the row's current matches, and reads the result.
 */
data class WbOrderResult(val sort: RowSort?, val pinned: List<String>, val limit: Int?)

private var ordActive = false
private var ordBy = "added"
private var ordDesc = true
private var ordHand = false                       // "Hand-picked first"
private var ordPins = mutableListOf<String>()
private var ordLimit = RowOrder.DEFAULT_LIMIT
private var ordMatches: List<MediaItem> = emptyList()
private var ordScopeName: String? = null          // the collection this row lives in, for the stale-pin note
private var ordSearch = ""
private var ordDragId: String? = null

private fun idOf(m: MediaItem) = m.jellyfinId ?: m.id
private fun words() = RowOrder.directionWords(ordBy, ordDesc)
private fun keyLabel(by: String) = when (by) { "title" -> "Title"; "year" -> "Release year"; else -> "Date added" }

internal fun wbOrderOpen(row: RowConfig?, collectionName: String?) {
    ordActive = row != null
    val s = row?.sort ?: RowSort()
    ordBy = s.by.takeIf { it in RowOrder.KEYS } ?: "added"; ordDesc = s.descending
    ordPins = row?.pinned.orEmpty().distinct().toMutableList()
    ordHand = ordPins.isNotEmpty()
    ordLimit = RowOrder.effectiveLimit(row?.limit)
    ordMatches = emptyList(); ordScopeName = collectionName; ordSearch = ""
}

internal fun wbOrderActive() = ordActive

/** What Apply writes. Absent stays absent (FR-225-11): the default order/count are `null`, and *Hand-picked
 *  first* with nothing picked saves as the plain key. */
fun workbenchOrderResult(): WbOrderResult {
    val sort = RowSort(ordBy, ordDesc).takeIf { it != RowSort() }
    return WbOrderResult(sort, if (ordHand) ordPins.toList() else emptyList(), ordLimit.takeIf { it != RowOrder.DEFAULT_LIMIT })
}

internal fun wbOrderSetMatches(items: List<MediaItem>) { ordMatches = items; wbOrderRender() }

private fun ordered(): List<MediaItem> = RowOrder.resolveAll(
    ordMatches, RowSort(ordBy, ordDesc), if (ordHand) ordPins else emptyList(),
    id = ::idOf, added = { it.recencyKey() }, year = { it.year }, title = { it.title }, sortName = { it.sortName },
)

// A toast is usually raised right BEFORE a re-render (which replaces the toast element), so it is held
// here and shown by the render that follows — seen in the browser 2026-09-17: the "released N hand-picks"
// message never appeared.
private var ordPendingToast: String? = null
private fun ordToast(msg: String) { ordPendingToast = msg }
private fun ordShowToast() {
    val msg = ordPendingToast ?: return
    val host = document.getElementById("wb-order-toast") as? HTMLElement ?: return
    ordPendingToast = null
    host.textContent = msg; host.style.opacity = "1"
    window.setTimeout({ (document.getElementById("wb-order-toast") as? HTMLElement)?.let { if (it.textContent == msg) it.style.opacity = "0" }; null }, 3200)
}

private fun thumb(m: MediaItem): String =
    if (!m.posterPath.isNullOrBlank()) """<img src="${posterSrc(m.posterPath, "https://image.tmdb.org/t/p/w185")}" alt="">""" else """<div class="wb-noimg">${m.title.take(2).esc()}</div>"""

internal fun wbOrderRender() {
    if (!ordActive) return
    val host = document.getElementById("wb-order") as? HTMLElement ?: return
    val byId = ordMatches.associateBy(::idOf)
    val livePins = ordPins.filter { it in byId }
    val stale = ordPins.filter { it !in byId }
    val atCeiling = ordPins.size >= ordLimit
    fun seg(sel: String, extraHand: Boolean) = buildString {
        append("""<span class="seg">""")
        for (k in RowOrder.KEYS) append("""<span class="${if (!ordHand && ordBy == k && !extraHand || extraHand && ordBy == k) "on" else ""}" data-$sel="$k">${keyLabel(k)}</span>""")
        if (!extraHand) append("""<span class="${if (ordHand) "on" else ""}" data-$sel="hand">Hand-picked first</span>""")
        append("</span>")
    }
    val strip = if (!ordHand) "" else buildString {
        append("""<div class="wbo-strip" id="wbo-strip"><div class="wbo-zone" data-zone="pins">""")
        ordPins.forEachIndexed { i, id ->
            val m = byId[id]
            if (m != null) append("""<div class="wbo-tile pin" draggable="true" data-id="${id.esc()}" title="${m.title.esc()}"><b class="wbo-n">${i + 1}</b>${thumb(m)}<span class="wbo-x" data-unpin="${id.esc()}">✕</span></div>""")
            else append("""<div class="wbo-tile pin stale" data-id="${id.esc()}" title="No longer matches this row"><b class="wbo-n">${i + 1}</b><div class="wb-noimg">?</div><span class="wbo-x" data-unpin="${id.esc()}">✕</span></div>""")
        }
        if (ordPins.isEmpty()) append("""<span class="tiny muted" style="align-self:center;padding:0 8px">Click a title to hand-pick it →</span>""")
        append("""</div><div class="wbo-seam"><span>then ${if (ordBy == "title") "title " else ""}${words()}</span></div><div class="wbo-zone rest" data-zone="rest">""")
        val rest = ordered().filter { idOf(it) !in livePins }
        rest.take(14).forEach { m -> append("""<div class="wbo-tile${if (atCeiling) " locked" else ""}" draggable="${!atCeiling}" data-id="${idOf(m).esc()}" title="${m.title.esc()}">${thumb(m)}</div>""") }
        if (rest.size > 14) append("""<span class="wbo-more">+${rest.size - 14}</span>""")
        append("</div></div>")
        if (atCeiling) append("""<div class="tiny muted" style="margin-top:6px">All $ordLimit places are hand-picked — release one, or show more titles, to pick another.</div>""")
        else {
            append("""<div style="display:flex;gap:8px;align-items:center;margin-top:8px"><input id="wbo-search" class="input" placeholder="Pin a title…" value="${ordSearch.esc()}" style="max-width:240px">""")
            if (ordSearch.length >= 2) {
                val hits = ordMatches.filter { it.title.contains(ordSearch, ignoreCase = true) && idOf(it) !in ordPins }.take(6)
                append("""<span class="wbo-hits">""" + (if (hits.isEmpty()) """<span class="tiny muted">No match in this row</span>""" else hits.joinToString("") { """<span class="chip" style="cursor:pointer" data-pinhit="${idOf(it).esc()}">${it.title.esc()}</span>""" }) + "</span>")
            }
            append("</div>")
        }
        append("""<div style="display:flex;gap:10px;align-items:center;margin-top:10px"><span class="tiny muted">Then the rest by</span>${seg("rest", true)}<button class="btn sm ghost" id="wbo-dir2" type="button">${words()}</button></div>""")
        for (id in stale) append("""<div class="note warn" style="margin-top:8px"><span class="tiny">A hand-pick (<span class="mono">${id.take(8).esc()}…</span>) no longer matches this row${ordScopeName?.let { " in <b>${it.esc()}</b>" } ?: ""}. It stays in your list and is skipped on the TV until it matches again, or you release it.</span></div>""")
    }
    host.innerHTML = """
      <div class="wbo">
        <div class="wbo-head"><b>Order</b>
          ${seg("by", false)}
          ${if (ordHand) "" else """<button class="btn sm ghost" id="wbo-dir" type="button">${words()}</button>"""}
          <span class="spacer"></span>
          <span style="display:inline-flex;align-items:center;gap:8px;white-space:nowrap"><span class="tiny muted">Show</span><span class="wbo-step"><b id="wbo-dec" class="${if (ordLimit <= maxOf(RowOrder.MIN_LIMIT, ordPins.size)) "off" else ""}">−</b><span id="wbo-limit">$ordLimit</span><b id="wbo-inc" class="${if (ordLimit >= RowOrder.DEFAULT_LIMIT) "off" else ""}">+</b></span><span class="tiny muted">titles</span></span>
        </div>
        $strip
        <div id="wb-order-toast" class="wbo-toast"></div>
      </div>"""
    wbOrderWire()
    wbOrderRenderPreview()
    ordShowToast()
}

/** The Matches panel: in the chosen order, numbered when that order is not the default, faded with a
 *  dashed edge past the limit; the count line says where the limit falls. */
private fun wbOrderRenderPreview() {
    val prev = document.getElementById("wb-preview") as? HTMLElement ?: return
    val list = ordered()
    val numbered = ordHand || RowSort(ordBy, ordDesc) != RowSort() || ordLimit != RowOrder.DEFAULT_LIMIT
    prev.innerHTML = list.take(60).mapIndexed { i, m ->
        val past = i >= ordLimit
        """<div class="wb-pcard${if (past) " wbo-past" else ""}" title="${m.title.esc()}" style="position:relative">${thumb(m)}${if (numbered && !past) """<b class="wbo-n">${i + 1}</b>""" else ""}</div>"""
    }.joinToString("")
    (document.getElementById("wb-count") as? HTMLElement)?.let { el ->
        val total = list.size
        el.innerHTML = "<b>$total title(s) match</b>" + if (total > ordLimit) """<div class="tiny muted" style="margin-top:3px">The TV shows the first $ordLimit in this order, See all shows every one.</div>""" else ""
    }
}

private fun on(sel: String, type: String, handler: (HTMLElement, Event) -> Unit) {
    val els = document.querySelectorAll("#wb-order $sel")
    for (i in 0 until els.length) { val el = els.item(i) as? HTMLElement ?: continue; el.addEventListener(type) { e -> handler(el, e) } }
}

private fun pin(id: String, at: Int? = null) {
    if (id in ordPins) { if (at != null) { ordPins.remove(id); ordPins.add(at.coerceIn(0, ordPins.size), id) }; return }
    if (ordPins.size >= ordLimit) { ordToast("All $ordLimit places are hand-picked — release one, or show more titles, to pick another."); return }   // shown by the render every caller does next
    if (ordMatches.none { idOf(it) == id }) return                       // pins come only from the row's matches (FR-225-4)
    if (at == null) ordPins.add(id) else ordPins.add(at.coerceIn(0, ordPins.size), id)
}

private fun wbOrderWire() {
    on("[data-by]", "click") { el, _ ->
        val v = el.getAttribute("data-by") ?: return@on
        if (v == "hand") ordHand = true
        else {
            if (ordHand && ordPins.isNotEmpty()) ordToast("Released ${ordPins.size} hand-pick${if (ordPins.size == 1) "" else "s"} — the row is now lined up by ${keyLabel(v).lowercase()}.")
            if (ordHand) ordPins.clear()
            ordHand = false
            if (ordBy != v) { ordBy = v; ordDesc = v != "title" }        // each key's natural first direction
        }
        wbOrderRender()
    }
    on("[data-rest]", "click") { el, _ -> val v = el.getAttribute("data-rest") ?: return@on; if (ordBy != v) { ordBy = v; ordDesc = v != "title" }; wbOrderRender() }
    on("#wbo-dir", "click") { _, _ -> ordDesc = !ordDesc; wbOrderRender() }
    on("#wbo-dir2", "click") { _, _ -> ordDesc = !ordDesc; wbOrderRender() }
    on("#wbo-inc", "click") { _, _ -> if (ordLimit < RowOrder.DEFAULT_LIMIT) { ordLimit++; wbOrderRender() } }
    on("#wbo-dec", "click") { _, _ ->
        when {
            ordLimit <= RowOrder.MIN_LIMIT -> Unit
            ordLimit <= ordPins.size -> { ordToast("Release a hand-pick to show fewer than $ordLimit."); ordShowToast() }   // a pin is never dropped behind your back
            else -> { ordLimit--; wbOrderRender() }
        }
    }
    on("[data-unpin]", "click") { el, e -> e.stopPropagation(); ordPins.remove(el.getAttribute("data-unpin")); wbOrderRender() }
    on(".wbo-zone.rest .wbo-tile", "click") { el, _ -> el.getAttribute("data-id")?.let { pin(it) }; wbOrderRender() }
    on("[data-pinhit]", "click") { el, _ -> el.getAttribute("data-pinhit")?.let { pin(it) }; ordSearch = ""; wbOrderRender() }
    (document.getElementById("wbo-search") as? HTMLInputElement)?.let { input ->
        input.addEventListener("input") { ordSearch = input.value; wbOrderRender(); (document.getElementById("wbo-search") as? HTMLInputElement)?.let { n -> n.focus(); n.setSelectionRange(n.value.length, n.value.length) } }
    }
    // Drag: within the pins to reorder, across the seam to pin / release.
    on(".wbo-tile[draggable=true]", "dragstart") { el, _ -> ordDragId = el.getAttribute("data-id") }
    on(".wbo-zone", "dragover") { _, e -> e.preventDefault() }
    on(".wbo-zone", "drop") { zone, e ->
        e.preventDefault()
        val id = ordDragId ?: return@on; ordDragId = null
        if (zone.getAttribute("data-zone") == "rest") { ordPins.remove(id); wbOrderRender(); return@on }
        // drop index = the pin tile under the pointer, else the end
        val target = (e.target as? HTMLElement)?.closest(".wbo-tile.pin") as? HTMLElement
        val at = target?.getAttribute("data-id")?.let { ordPins.indexOf(it) }?.takeIf { it >= 0 }
        pin(id, at ?: ordPins.size.let { if (id in ordPins) it - 1 else it })
        wbOrderRender()
    }
}

internal const val WB_ORDER_CSS = """
        .wbo { margin:14px 0 4px; padding:12px 14px; border:1px solid var(--line); border-radius:10px; }
        .wbo-head { display:flex; gap:10px; align-items:center; flex-wrap:wrap; }
        .wbo-step { display:inline-flex; align-items:center; gap:8px; border:1px solid var(--line); border-radius:8px; padding:2px 8px; }
        .wbo-step b { cursor:pointer; user-select:none; padding:0 4px; } .wbo-step b.off { opacity:.3; cursor:default; }
        .wbo-strip { display:flex; align-items:stretch; gap:0; margin-top:12px; overflow-x:auto; padding-bottom:4px; }
        .wbo-zone { display:flex; gap:6px; min-height:96px; align-items:stretch; padding:2px; }
        .wbo-zone.rest { opacity:.55; }
        .wbo-seam { flex:none; display:flex; align-items:center; margin:0 10px; border-left:2px dashed var(--line-2, rgba(255,255,255,.28)); padding-left:8px; }
        .wbo-seam span { writing-mode:vertical-rl; transform:rotate(180deg); font-size:.68rem; color:var(--ink-dim, #8a8fa3); white-space:nowrap; }
        .wbo-tile { position:relative; flex:none; width:62px; aspect-ratio:2/3; border-radius:7px; overflow:hidden; background:var(--fill-3); cursor:pointer; }
        .wbo-tile img { width:100%; height:100%; object-fit:cover; pointer-events:none; }
        .wbo-tile.pin { cursor:grab; outline:2px solid var(--acc, #7b6ef0); }
        .wbo-tile.stale { outline-style:dashed; opacity:.5; } .wbo-tile.locked { cursor:not-allowed; }
        .wbo-n { position:absolute; top:3px; left:3px; min-width:16px; padding:0 4px; border-radius:6px; background:rgba(0,0,0,.72); color:#fff; font-size:.66rem; line-height:16px; text-align:center; }
        .wbo-x { position:absolute; top:3px; right:3px; width:16px; height:16px; border-radius:50%; background:rgba(0,0,0,.72); color:#fff; font-size:.6rem; line-height:16px; text-align:center; cursor:pointer; }
        .wbo-more { align-self:center; padding:0 8px; font-size:.75rem; color:var(--ink-dim, #8a8fa3); }
        .wbo-hits { display:flex; gap:6px; flex-wrap:wrap; }
        .wbo-past { opacity:.35; outline:1px dashed var(--line-2, rgba(255,255,255,.28)); }
        .wbo-toast { margin-top:8px; font-size:.78rem; color:var(--warn, #f5b74a); opacity:0; transition:opacity .25s; min-height:1em; }
"""
