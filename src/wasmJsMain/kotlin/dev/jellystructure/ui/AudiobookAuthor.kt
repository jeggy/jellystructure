package dev.jellystructure.ui

import dev.jellystructure.Router
import dev.jellystructure.api.AudiobooksApi
import dev.jellystructure.model.AudiobookAuthorPageDto
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement

/*
 * Phase 281 (FR-281-9) — the Author page: name · sort name · *N books in the library*, the biography (typed here;
 * a provider only suggests, in one sentence), the books as 1:1 cells grouped by series, and History. The picture is
 * the name's initials — v1 keeps no author image (see the build notes).
 */

private var auId = ""
private var auPage: AudiobookAuthorPageDto? = null
private var auTab = "overview"
private var auEditing = false

fun renderAudiobookAuthor(container: Element, scope: CoroutineScope, id: String, query: Map<String, String>) {
    auId = id; auPage = null; auEditing = false
    auTab = query["tab"]?.takeIf { it in setOf("overview", "history") } ?: "overview"
    container.innerHTML = """
        <div id="au-root">
        <div class="backrow"><a class="btn sm ghost" href="#/library?kind=audiobooks&aview=authors">‹ Library</a><span class="crumb" id="au-crumb"></span></div>
        <div class="pagebar" id="au-bar"><h1>…</h1></div>
        <div id="au-head"><span class="muted tiny">Loading…</span></div>
        <div class="tabs2" id="au-tabs"><span data-tab="overview">Overview</span><span data-tab="history">History</span></div>
        <div id="au-panel"></div>
        </div>
    """.trimIndent()
    val root = document.getElementById("au-root") as? HTMLElement ?: return
    root.addEventListener("click") { ev -> auClick(ev.target as? Element ?: return@addEventListener, ev, scope) }
    auReload(scope, ask = false)
}

private fun auReload(scope: CoroutineScope, ask: Boolean) {
    scope.launch {
        val p = AudiobooksApi.authorPage(auId, ask)
        if (p == null) {
            (document.getElementById("au-head") as? HTMLElement)?.innerHTML = """<div class="note red">This author isn't in the audiobooks library (any more).</div>"""
            return@launch
        }
        auPage = p
        auRender(scope)
    }
}

private fun auRender(scope: CoroutineScope) {
    val p = auPage ?: return
    val a = p.author
    document.title = "Jellystructure — ${a.name}"
    (document.getElementById("au-crumb") as? HTMLElement)?.innerHTML = """<a href="#/library?kind=audiobooks">Audiobooks</a> / <a href="#/library?kind=audiobooks&aview=authors">Authors</a> / ${a.name.esc()}"""
    (document.getElementById("au-bar") as? HTMLElement)?.innerHTML = """<h1>${a.name.esc()}</h1>"""
    (document.getElementById("au-head") as? HTMLElement)?.innerHTML = buildString {
        append("""<div class="mu-pb"><div class="mu-circ" style="width:160px;${muWordmarkStyle(a.name)}">${muInitials(a.name).esc()}</div><div>""")
        a.sortName?.let { append("""<div class="mono tiny muted">${it.esc()}</div>""") }
        append("""<div class="mu-pbm"><span>${muPlural(p.books.size, "book")} in the library</span></div>""")
        append("</div></div>")
    }
    auPanel(scope)
}

private fun auPanel(scope: CoroutineScope) {
    val el = document.getElementById("au-panel") as? HTMLElement ?: return
    val p = auPage ?: return
    val tabs = document.querySelectorAll("#au-tabs [data-tab]")
    for (i in 0 until tabs.length) (tabs.item(i) as? HTMLElement)?.let { it.className = if (it.getAttribute("data-tab") == auTab) "on" else "" }
    if (auTab == "history") { el.innerHTML = """<span class="muted tiny">Loading…</span>"""; scope.launch { el.innerHTML = muHistory(auId) }; return }
    val a = p.author
    el.innerHTML = buildString {
        append("""<div class="mu-sec">Biography</div>""")
        when {
            auEditing -> append("""<div class="mu-bio"><textarea id="au-bio">${a.bio.orEmpty().esc()}</textarea></div><div class="row" style="gap:8px;margin-top:8px"><span class="btn sm primary" data-a="biosave">Save</span><span class="btn sm ghost" data-a="biocancel">Cancel</span></div>""")
            a.bio != null -> append("""<div class="mu-bio">${a.bio.esc()}</div><div class="tiny muted" style="margin-top:6px">Typed here · <a href="#" data-a="bioedit">Edit</a></div>""")
            else -> {
                append("""<div class="tiny muted">No biography. <a href="#" data-a="bioedit">Write one</a>.""")
                if (p.suggestion != null) append(""" <span class="chip" style="font-size:.7rem;margin-left:6px">Suggestion · ${p.suggestion.esc()}</span>""")
                else append(""" <a href="#" data-a="ask" style="margin-left:6px">Ask Open Library</a>""")
                append("</div>")
            }
        }
        // Books, grouped by series when any has one; the rest under *Books*.
        val bySeries = p.books.groupBy { it.series.orEmpty() }
        for (s in bySeries.keys.sortedWith(compareBy({ it.isEmpty() }, { it.lowercase() }))) {
            append("""<div class="mu-sec">${if (s.isEmpty()) "Books" else s.esc()}</div><div class="mu-grid">""")
            for (r in bySeries.getValue(s)) append(abCell(r))
            append("</div>")
        }
    }
}

private fun auClick(t: Element, ev: org.w3c.dom.events.Event, scope: CoroutineScope) {
    t.closest("#au-tabs [data-tab]")?.let { tab ->
        auTab = tab.getAttribute("data-tab") ?: "overview"
        Router.updateQuery(mapOf("tab" to auTab))
        auPanel(scope); return
    }
    val a = t.closest("[data-a]")?.getAttribute("data-a") ?: return
    ev.preventDefault()
    when (a) {
        "bioedit" -> { auEditing = true; auPanel(scope) }
        "biocancel" -> { auEditing = false; auPanel(scope) }
        "biosave" -> scope.launch {
            val text = (document.getElementById("au-bio") as? HTMLTextAreaElement)?.value
            val next = AudiobooksApi.editAuthor(auId, text) ?: return@launch muToast("That wasn't saved")
            auPage = auPage?.copy(author = next); auEditing = false; muToast("Saved"); auPanel(scope)
        }
        "ask" -> auReload(scope, ask = true)
    }
}
