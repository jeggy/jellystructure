package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.AudiobooksApi
import dev.jellystructure.historyReplaceState
import dev.jellystructure.model.AudiobookRow
import dev.jellystructure.model.AudiobooksBrowseDto
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

/*
 * Phase 280 (FR-280-8) — Library → Audiobooks: Audiobooks · Authors · Series (Series only when any book has one),
 * the audiobook facets, and the states (not mapped, mapped but not scanned, empty, a search with no results). Every
 * row and every count comes from `GET /api/audiobooks/browse`; this page only renders. Borrows the Music kind's
 * grid and facet styles (`mu-*`) — one look for both.
 */

private var abView = "audiobooks"
private var abQuery = ""
private var abSort: String? = null
private val abFacets = LinkedHashMap<String, MutableSet<String>>()
private var abOpenFacet: String? = null
private var abDto: AudiobooksBrowseDto? = null
private var abLoadJob: Job? = null
private var abDocWired = false
private var abScope: CoroutineScope? = null

private fun abUrl(): String = buildList {
    add("kind=audiobooks")
    if (abView != "audiobooks") add("aview=$abView")
    if (abQuery.isNotBlank()) add("q=${dev.jellystructure.encodeURIComponent(abQuery)}")
    abSort?.let { add("sort=$it") }
    abFacets.filterValues { it.isNotEmpty() }.forEach { (k, v) -> add("f.$k=${v.joinToString(",") { dev.jellystructure.encodeURIComponent(it) }}") }
}.joinToString("&").let { "#/library?$it" }

/** The kind picker, shared by the films Library, Music and Audiobooks so the three agree. */
internal fun libraryKindSeg(on: String): String = buildString {
    append("""<span class="seg" id="kindseg">""")
    for ((k, l) in listOf("" to "All", "MOVIE" to "Movies", "TV_SHOW" to "TV", "MUSIC_VIDEO" to "Music videos", "music" to "Music", "audiobooks" to "Audiobooks"))
        append("""<span data-kind="$k"${if (k == on) """ class="on"""" else ""}>$l</span>""")
    append("</span>")
}

internal fun wireLibraryKindSeg(container: Element) {
    val kinds = container.querySelectorAll("#kindseg [data-kind]")
    for (i in 0 until kinds.length) (kinds.item(i) as? HTMLElement)?.let { k ->
        k.addEventListener("click") { val v = k.getAttribute("data-kind").orEmpty(); App.navigate(if (v.isEmpty()) "/library" else "/library?kind=$v") }
    }
}

fun renderAudiobookLibrary(container: Element, scope: CoroutineScope, query: Map<String, String>) {
    abView = query["aview"]?.takeIf { it in setOf("audiobooks", "authors", "series") } ?: "audiobooks"
    abQuery = query["q"].orEmpty()
    abSort = query["sort"]
    abFacets.clear(); abOpenFacet = null; abDto = null
    query.filterKeys { it.startsWith("f.") }.forEach { (k, v) -> abFacets[k.removePrefix("f.")] = v.split(',').filter { it.isNotBlank() }.toMutableSet() }

    container.innerHTML = """
        <div class="pagebar">
          <h1>Library</h1>
          <span class="spacer"></span>
          <span class="searchwrap"><input id="ab-search" class="input" type="search" placeholder="⌕ search titles, authors, narrators…" style="width:240px;flex-shrink:0;"></span>
          ${libraryKindSeg("audiobooks")}
        </div>
        <div id="ab-lib"><span class="muted tiny">Loading…</span></div>
    """.trimIndent()
    (document.getElementById("ab-search") as? HTMLInputElement)?.let { inp ->
        inp.value = abQuery
        var debounce: Job? = null
        inp.addEventListener("input") {
            debounce?.cancel()
            debounce = scope.launch { delay(250); abQuery = inp.value; abLoad(scope) }
        }
    }
    wireLibraryKindSeg(container)
    val root = document.getElementById("ab-lib") as? HTMLElement ?: return
    root.addEventListener("click") { ev -> abClick(ev.target as? Element ?: return@addEventListener, ev, scope) }
    root.addEventListener("change") { ev ->
        val sel = ev.target as? HTMLSelectElement ?: return@addEventListener
        if (sel.id == "ab-sort") { abSort = sel.value.takeIf { it.isNotEmpty() }; abLoad(scope) }
    }
    if (!abDocWired) {
        abDocWired = true
        document.addEventListener("click") { ev ->
            if (abOpenFacet != null && (ev.target as? Element)?.closest(".mu-fc") == null && document.getElementById("ab-lib") != null) { abOpenFacet = null; abRender() }
        }
    }
    abScope = scope
    abLoad(scope)
}

private fun abLoad(scope: CoroutineScope) {
    abLoadJob?.cancel()
    abLoadJob = scope.launch {
        historyReplaceState(abUrl())
        val dto = AudiobooksApi.browse(abView, abQuery, abFacets.mapValues { it.value.toSet() }, abSort)
        if (dto == null) {
            (document.getElementById("ab-lib") as? HTMLElement)?.innerHTML = """<div class="note red">Couldn't read the audiobooks from the server.</div>"""
            return@launch
        }
        abDto = dto
        abView = dto.view
        abRender()
    }
}

/** *5 h 24 min* — hours and minutes, the way a book's length is said. */
internal fun abHours(ms: Long): String = muTotal(ms)

internal fun abCoverUrl(r: AudiobookRow): String? = if (r.cover) "/api/audiobooks/image/${r.id}?v=${r.v}" else null

internal fun abChip(r: AudiobookRow): String = when (r.flag) {
    "two_books" -> """<span class="mu-mchip w">two books?</span>"""
    "missing_part" -> """<span class="mu-mchip w">${r.missingPart?.let { "part $it missing" } ?: "a part missing"}</span>"""
    else -> ""
}

internal fun abCell(r: AudiobookRow, prefix: String = ""): String = buildString {
    append("""<a class="mu-cell" href="#/audiobook/${r.id}">""")
    append(muCoverHtml(r.title, abCoverUrl(r), chip = abChip(r)))
    append("""<div class="ttl">$prefix${r.title.esc()}</div><div class="sub">${r.authors.joinToString(", ").esc()}</div>""")
    append("""<div class="yr">${abHours(r.durationMs)} · ${if (r.parts == 1) "1 file" else "${r.parts} parts"}""")
    if (r.finishedBy > 0) append(""" · <span style="color:var(--ok)">✓ finished by ${r.finishedBy}</span>""")
    append("</div></a>")
}

private fun abRender() {
    val root = document.getElementById("ab-lib") as? HTMLElement ?: return
    val d = abDto ?: return
    val h = d.health
    val live = d.mapped && h != null && h.books > 0
    val libNames = d.libraries.joinToString(" · ") { it.name.esc() }.ifEmpty { "books" }
    root.innerHTML = buildString {
        append("""<div class="mu-top"><span class="seg" id="ab-view">""")
        val labels = mapOf("audiobooks" to "Audiobooks", "authors" to "Authors", "series" to "Series")
        for (k in d.views) append("""<span data-aview="$k" class="${if (abView == k) "on" else ""}">${labels[k] ?: k}</span>""")
        append("</span>")
        if (live) {
            val needs = h.missingParts + h.twoInOne
            append("""<span class="mu-status"><b>${h.books}</b> ${if (h.books == 1) "book" else "books"} · ${h.parts} files · ${abHours(h.durationMs)}""")
            if (needs > 0) append(""" · <b class="w">$needs</b> <span class="w">need${if (needs == 1) "s" else ""} you</span>""")
            if (h.coversMissing > 0) append(""" · <span class="w">${h.coversMissing} without a cover</span>""")
            append("</span>")
        }
        append("</div>")
        append("""<p class="page-sub" style="margin-top:0">Audiobooks from Jellyfin’s <b>$libNames</b> library. Jellyfin sees one item per file, so <b>the folder is the book</b> and the parts are put in order here. The files’ own tags come first, then what you type, then what a provider suggests.</p>""")
        when {
            !d.mapped -> append("""<div class="mu-empty"><h3>No audiobook library mapped</h3><p>Jellyfin’s <i>Books</i> library appears under <b>Settings → Libraries</b> after <i>Refresh from Jellyfin</i>. Map it, and the next scan groups its files into books.</p><a class="btn sm" href="#/settings?tab=libraries">Libraries ›</a></div>""")
            !live && !d.scanned -> {
                val paths = d.libraries.joinToString("<br>") { "<span class=\"mono\">${it.jellyfinPath.esc()} → ${it.localPath.esc()}</span>" }
                append("""<div class="mu-empty"><h3>Mapped — not scanned yet</h3><p>$paths</p><p>The first scan reads what Jellyfin has filed and groups the files into books, one folder each.</p><span class="btn sm primary" data-act="scan">Scan now</span> <a class="btn sm ghost" href="#/settings?tab=libraries">Library card ›</a></div>""")
            }
            !live -> append("""<div class="mu-empty"><h3>Nothing filed as audiobooks yet</h3><p>The <b>$libNames</b> library is mapped but holds no audiobooks. One folder per book, the parts inside it in order.</p><a class="btn sm" href="#/settings?tab=libraries">Library card ›</a></div>""")
            else -> {
                if (abView == "audiobooks") {
                    append(abFacetBar(d))
                    append("""<div class="mu-active">${abActiveBar(d)}</div>""")
                }
                append(abBody(d))
            }
        }
    }
    muWireMenus(root)
}

private fun abFacetBar(d: AudiobooksBrowseDto): String = buildString {
    append("""<div class="mu-facets"><span class="muted tiny">filter:</span>""")
    for (f in d.facets) {
        val n = f.values.count { it.on }
        append("""<span class="mu-fc${if (abOpenFacet == f.key) " open" else ""}"><span class="mu-fbtn${if (n > 0) " on" else ""}" data-fopen="${f.key}">${f.label.esc()}${if (n > 0) """ <span class="c">$n</span>""" else ""} ▾</span><div class="mu-fpop">""")
        if (f.values.isEmpty()) append("""<div class="tiny muted" style="padding:6px 9px">None in the library</div>""")
        for (v in f.values)
            append("""<div class="mu-fv${if (v.on) " on" else ""}${if (v.count == 0) " zero" else ""}" data-fk="${f.key}" data-fv="${v.value.esc()}"><span class="bx">${if (v.on) "✓" else ""}</span><span>${v.label.esc()}</span><span class="ct">${v.count}</span></div>""")
        append("</div></span>")
    }
    append("""<span class="spacer" style="flex:1"></span><select id="ab-sort" class="input" style="width:auto;font-size:.83rem;">""")
    for ((v, l) in listOf("" to "recently added ▾", "title" to "title A–Z", "author" to "author A–Z", "series" to "series, in order"))
        append("""<option value="$v"${if ((abSort ?: "") == v) " selected" else ""}>$l</option>""")
    append("</select></div>")
}

private fun abActiveBar(d: AudiobooksBrowseDto): String {
    val act = d.facets.filter { f -> f.values.any { it.on } }
    if (act.isEmpty()) return ""
    return act.joinToString(""" <span class="tiny muted">and</span> """) { f ->
        """<span class="fxchip">${f.label.esc()} is ${f.values.filter { it.on }.joinToString(" or ") { it.label.esc() }} <span class="rm" data-frm="${f.key}" style="cursor:pointer">✕</span></span>"""
    } + """<span class="livecount" style="margin-left:6px;"><span class="n">${d.total}</span><span class="tiny muted"> match</span></span><span class="tiny" style="margin-left:6px;cursor:pointer;color:var(--ink-soft);" data-fclear>clear all</span>"""
}

private fun abBody(d: AudiobooksBrowseDto): String = when (abView) {
    "authors" -> if (d.authors.isEmpty()) """<div class="muted" style="padding:40px 4px;">${if (abQuery.isNotBlank()) "No author matches “${abQuery.esc()}”." else "No authors."}</div>""" else buildString {
        append("""<div class="mu-agrid">""")
        for (a in d.authors) {
            append("""<a class="mu-cell" href="#/audiobook-author/${a.id}"><div class="mu-circ" style="${muWordmarkStyle(a.name)}">${muInitials(a.name).esc()}</div>""")
            append("""<div class="ttl">${a.name.esc()}</div><div class="yr">${muPlural(a.books, "book")}</div></a>""")
        }
        append("</div>")
    }
    "series" -> if (d.series.isEmpty()) """<div class="muted" style="padding:40px 4px;">No series.</div>""" else buildString {
        for (s in d.series)
            append("""<div class="mu-sec"><a href="#/library?kind=audiobooks&sort=series&q=${dev.jellystructure.encodeURIComponent(s.name)}">${s.name.esc()}</a> <span class="tiny muted" style="text-transform:none;letter-spacing:0;font-weight:500">${muPlural(s.books, "book")} in the library</span></div>""")
    }
    else -> if (d.books.isEmpty()) """<div class="muted" style="padding:40px 4px;">${if (abQuery.isNotBlank()) "No audiobook matches “${abQuery.esc()}”." else "No books match these filters."}</div>"""
        else """<div class="mu-grid">${d.books.joinToString("") { abCell(it) }}</div>"""
}

private fun abClick(t: Element, ev: org.w3c.dom.events.Event, scope: CoroutineScope) {
    t.closest("[data-aview]")?.let { v -> abView = v.getAttribute("data-aview") ?: "audiobooks"; abOpenFacet = null; abLoad(scope); return }
    t.closest("[data-fopen]")?.let { f ->
        ev.stopPropagation()
        val k = f.getAttribute("data-fopen")
        abOpenFacet = if (abOpenFacet == k) null else k
        abRender(); return
    }
    t.closest("[data-fk]")?.let { f ->
        ev.stopPropagation()
        val k = f.getAttribute("data-fk") ?: return
        val v = f.getAttribute("data-fv") ?: return
        val set = abFacets.getOrPut(k) { LinkedHashSet() }
        if (!set.remove(v)) set += v
        if (set.isEmpty()) abFacets.remove(k)
        abLoad(scope); return
    }
    t.closest("[data-frm]")?.let { f -> abFacets.remove(f.getAttribute("data-frm")); abLoad(scope); return }
    if (t.closest("[data-fclear]") != null) { abFacets.clear(); abLoad(scope); return }
    t.closest("[data-act]")?.let { a ->
        if (a.getAttribute("data-act") == "scan") openPipelineRunDialog(scope, "Scan library", full = false) { skip ->
            if (dev.jellystructure.api.MediaApi.startScan(false, skip)) muToast("Scan started — the audiobooks are read after the films") else muToast("The scan didn't start")
        }
    }
}
