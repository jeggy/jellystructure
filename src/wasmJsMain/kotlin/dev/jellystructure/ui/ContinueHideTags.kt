package dev.jellystructure.ui

import dev.jellystructure.api.MetadataApi
import dev.jellystructure.scrollIntoViewNearest
import dev.jellystructure.shared.tv.HideTagsPicker
import dev.jellystructure.shared.tv.TagCount
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * DEB-1264 slice 3 (FR-7..FR-10) — the *Hide titles tagged* sub-row under a Continue Watching system row, and its
 * picker. The same component serves Layout → Content rows (global and per-user) and a Custom collection's System
 * rows. It owns no state: the list is read through [mountHideTags]'s `tags` and written through `onChange`, which
 * only edits the editor's unsaved layout — the existing layout Save sends it (FR-9). Styling is `.cw-*` in wf.css.
 *
 * The parent row must carry `hascond` (so the sub-row wraps onto its own line) and `off` while the row is switched
 * off (the sheet then hides the sub-row and the tags stay in the layout).
 */

/** The mount point the editors drop into the parent row; [mountHideTags] fills it. */
internal const val HIDE_TAGS_MOUNT_ATTR = "data-cw-hide-tags"
internal fun hideTagsMountHtml(key: String): String = """<div class="cw-cond" $HIDE_TAGS_MOUNT_ATTR="${key.escHideTags()}"></div>"""

private fun String.escHideTags() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

// ── The catalogue: the server's tag list with title counts, as the admin's Metadata → Tags page reads it ──

private val CATALOGUE_TTL = 60.seconds
private var catalogue: List<TagCount>? = null
private var catalogueMark: TimeMark? = null
private var catalogueLoad: Deferred<List<TagCount>?>? = null

private fun freshCatalogue(): List<TagCount>? = catalogue?.takeIf { catalogueMark?.let { m -> m.elapsedNow() < CATALOGUE_TTL } == true }

/** A failed read is null and is not cached, so the next mount asks again (FR-9). */
private suspend fun loadCatalogue(scope: CoroutineScope): List<TagCount>? {
    freshCatalogue()?.let { return it }
    val load = catalogueLoad ?: scope.async {
        val r = MetadataApi.getTags("count")
        r?.let { HideTagsPicker.catalogueOf(it.jsTags.map { t -> TagCount(t.name, t.count) } + it.otherTags.map { t -> TagCount(t.name, t.count) }) }
    }.also { catalogueLoad = it }
    val result = try { load.await() } finally { if (catalogueLoad === load) catalogueLoad = null }
    if (result != null) { catalogue = result; catalogueMark = TimeSource.Monotonic.markNow() }
    return result
}

// ── Strings (FR-10): English only, as the round drew them ──

private const val L_FACET = "Hide titles tagged"
private const val L_ADD = "Add tag"
private const val L_TOP = "Most used tags"
private const val L_HINT = "Hides the exact tag only — never tags that merely contain it."
private const val L_NONE2 = "Tags come from your library — nothing is created here."
private fun lRemove(tag: String) = "Remove $tag"
private fun lPlaceholder(n: Int?) = if (n == null) "Search tags…" else "Search ${grouped(n)} tags…"
private fun lResults(n: Int, q: String) = "$n tag${if (n == 1) "" else "s"} contain “$q”"
private fun lNone(q: String) = "No tag called “$q”."
private fun lCount(n: Int?) = when (n) { null -> "–"; 1 -> "1 title"; else -> "$n titles" }
private fun grouped(n: Int): String = n.toString().reversed().chunked(3).joinToString(" ").reversed()

// Only one picker is open at a time (FR-8); a click anywhere else closes it.
private var closeOpenPicker: (() -> Unit)? = null
private var outsideClickWired = false

/**
 * Fills [host] (a `.cw-cond` inside the parent row, which the popup is positioned against) with the label, one chip
 * per tag in [tags] and the Add button. A change goes to [onChange] and the sub-row redraws from [tags] again.
 */
internal fun mountHideTags(host: HTMLElement, scope: CoroutineScope, tags: () -> List<String>, onChange: (List<String>) -> Unit) {
    if (!outsideClickWired) {
        outsideClickWired = true
        document.addEventListener("click") { _ -> closeOpenPicker?.invoke() }
    }
    var cat: List<TagCount>? = freshCatalogue()
    var open = false
    var query = ""
    var hov = 0

    fun rows(): List<TagCount> = cat?.let { HideTagsPicker.results(it, query) } ?: emptyList()

    fun markedName(name: String): String {
        val r = HideTagsPicker.matchRange(name, query) ?: return name.escHideTags()
        return name.substring(0, r.first).escHideTags() + "<mark>" + name.substring(r.first, r.last + 1).escHideTags() + "</mark>" + name.substring(r.last + 1).escHideTags()
    }

    fun resultsHtml(): String {
        val q = query.trim()
        val items = rows()
        if (items.isEmpty()) {
            // No match — or no catalogue to match against (FR-9). Nothing typed and nothing to list: the second line only.
            return """<div class="cw-none">${if (q.isEmpty()) "" else "<b>${lNone(q).escHideTags()}</b>"}<span>$L_NONE2</span></div>"""
        }
        val list = tags()
        val head = if (q.isEmpty()) L_TOP else lResults(items.size, q.lowercase()).escHideTags()
        return """<div class="cw-ph">$head</div>""" + items.mapIndexed { i, t ->
            val on = HideTagsPicker.isHidden(list, t.name)
            """<div class="cw-pi${if (on) " on" else ""}${if (i == hov) " hov" else ""}" data-pick="${t.name.escHideTags()}" role="checkbox" aria-checked="$on"><span class="cw-box">${if (on) "✓" else ""}</span><span class="nm">${markedName(t.name)}</span><span class="n${if (t.count == 0) " zero" else ""}">${lCount(t.count)}</span></div>"""
        }.joinToString("")
    }

    fun pickerHtml(): String =
        """<div class="cw-pick" role="dialog" aria-label="$L_ADD"><label class="cw-ps">⌕<input class="cw-q" value="${query.escHideTags()}" placeholder="${lPlaceholder(cat?.size).escHideTags()}" autocomplete="off" aria-label="${lPlaceholder(cat?.size).escHideTags()}"></label><div class="cw-res">${resultsHtml()}</div><div class="cw-pf">$L_HINT</div></div>"""

    fun chipHtml(tag: String): String {
        val n = HideTagsPicker.countOf(cat, tag)
        return """<span class="cw-chip" title="${"$tag · ${lCount(n)}".escHideTags()}"><span class="nm">${tag.escHideTags()}</span><span class="n${if (n == 0) " zero" else ""}">${n?.toString() ?: "–"}</span><button type="button" class="x" data-rm="${tag.escHideTags()}" aria-label="${lRemove(tag).escHideTags()}">✕</button></span>"""
    }

    fun focusSearch() {
        (host.querySelector(".cw-q") as? HTMLInputElement)?.let { it.focus(); it.setSelectionRange(it.value.length, it.value.length) }
    }

    fun render(focus: Boolean = false) {
        host.innerHTML = """<span class="cw-facet">$L_FACET</span>""" + tags().joinToString("") { chipHtml(it) } +
            """<button type="button" class="cw-add${if (open) " open" else ""}" aria-expanded="$open">＋ $L_ADD</button>""" + (if (open) pickerHtml() else "")
        if (open && focus) focusSearch()
    }

    /** Typing and the arrow keys redraw only the results, so the search field keeps its focus and caret. */
    fun renderResults() {
        host.querySelector(".cw-res")?.innerHTML = resultsHtml()
        host.querySelector(".cw-pi.hov")?.let { scrollIntoViewNearest(it) }
    }

    lateinit var closer: () -> Unit
    fun close(returnFocus: Boolean = false) {
        if (!open) return
        open = false; query = ""; hov = 0
        if (closeOpenPicker === closer) closeOpenPicker = null
        render()
        if (returnFocus) (host.querySelector(".cw-add") as? HTMLElement)?.focus()
    }
    closer = { close() }

    fun change(next: List<String>) { onChange(next); render(focus = open) }

    host.addEventListener("click") { ev ->
        ev.stopPropagation()   // the document's click-outside must not close the picker from a click inside it
        val t = ev.target as? Element ?: return@addEventListener
        t.closest("[data-rm]")?.getAttribute("data-rm")?.let { change(HideTagsPicker.removed(tags(), it)); return@addEventListener }
        t.closest("[data-pick]")?.getAttribute("data-pick")?.let { change(HideTagsPicker.toggled(tags(), it)); return@addEventListener }
        if (t.closest(".cw-add") != null) {
            if (open) close() else {
                closeOpenPicker?.invoke()
                open = true; closeOpenPicker = closer; render(focus = true)
            }
        }
    }
    host.addEventListener("input") { ev ->
        val inp = ev.target as? HTMLInputElement ?: return@addEventListener
        if (!inp.classList.contains("cw-q")) return@addEventListener
        query = inp.value; hov = 0; renderResults()
    }
    host.addEventListener("keydown") { ev ->
        if (!open) return@addEventListener
        val key = (ev as? KeyboardEvent)?.key ?: return@addEventListener
        val items = rows()
        when (key) {
            "Escape" -> { ev.preventDefault(); close(returnFocus = true) }
            "ArrowDown" -> { ev.preventDefault(); hov = minOf(items.size - 1, hov + 1).coerceAtLeast(0); renderResults() }
            "ArrowUp" -> { ev.preventDefault(); hov = maxOf(0, hov - 1); renderResults() }
            "Enter" -> items.getOrNull(hov)?.let { ev.preventDefault(); change(HideTagsPicker.toggled(tags(), it.name)) }
        }
    }

    render()
    if (cat == null) scope.launch {
        val loaded = loadCatalogue(scope)
        if (loaded != null && host.isConnected) { cat = loaded; render(focus = open) }
    }
}
