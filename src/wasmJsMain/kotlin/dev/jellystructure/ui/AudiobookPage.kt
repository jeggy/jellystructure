@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.Router
import dev.jellystructure.api.AudiobooksApi
import dev.jellystructure.model.AudiobookOrigin
import dev.jellystructure.model.AudiobookPageDto
import dev.jellystructure.model.AudiobookSuggestion
import dev.jellystructure.model.MusicArtworkDto
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLAudioElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.KeyboardEvent

/*
 * Phase 281 (FR-281-1..8) — the Audiobook page: the folder is the book. Details is an editor first, with the
 * Suggestions rail beside it; Parts (our order, drag to change it, never a rename), Chapters, Artwork, Listeners
 * (read-only) and History. There is no NFO tab — Jellyfin reads no metadata file for audiobooks. Everything shown is
 * what `GET /api/audiobooks/{id}/page` says; each action calls the server and re-reads.
 */

private var bkId = ""
private var bkPage: AudiobookPageDto? = null
private var bkTab = "details"
private var bkAudio: HTMLAudioElement? = null
private var bkPlaying: String? = null
private var bkArt: MusicArtworkDto? = null
private var bkDragFrom: Int? = null
private var bkSaveSays: String? = null

private val BK_TABS = listOf("details" to "Details", "parts" to "Parts", "chapters" to "Chapters", "artwork" to "Artwork", "listeners" to "Listeners", "history" to "History")

/** The Details fields, in the editor's order: key, label, placeholder, kind (`text` · `chips` · `lang` · `area`), wide. */
private data class BkField(val key: String, val label: String, val ph: String, val kind: String = "text", val wide: Boolean = false)
private val BK_FIELDS = listOf(
    BkField("title", "Title", "", wide = true), BkField("subtitle", "Subtitle", "none", wide = true),
    BkField("authors", "Authors", "add an author…", "chips"), BkField("narrators", "Narrators", "add a narrator…", "chips"),
    BkField("series", "Series", "none"), BkField("series_position", "Position in series", "—"),
    BkField("year", "Year", ""), BkField("publisher", "Publisher", "unknown"),
    BkField("language", "Language", "", "lang"), BkField("genres", "Genres", "add a genre…", "chips"),
    BkField("description", "Description", "None in the files and none from a provider. Type one if you have it.", "area", wide = true),
)
private val BK_LANGS = listOf("" to "—", "da" to "Danish", "en" to "English", "fo" to "Faroese", "no" to "Norwegian", "sv" to "Swedish", "de" to "German", "is" to "Icelandic")
private val BK_FIELD_LABEL = mapOf("title" to "Title", "subtitle" to "Subtitle", "authors" to "Author", "narrators" to "Narrator", "series" to "Series",
    "series_position" to "Position", "year" to "Year", "publisher" to "Publisher", "language" to "Language", "genres" to "Genres", "description" to "Description")

fun renderAudiobookPage(container: Element, scope: CoroutineScope, id: String, query: Map<String, String>) {
    bkAudio?.pause(); bkAudio = null
    bkId = id; bkPage = null; bkPlaying = null; bkArt = null; bkSaveSays = null
    bkTab = query["tab"]?.takeIf { t -> BK_TABS.any { it.first == t } } ?: "details"
    container.innerHTML = """
        <div id="bk-root">
        <div class="backrow"><a class="btn sm ghost" href="#/library?kind=audiobooks">‹ Library</a><span class="crumb" id="bk-crumb"></span></div>
        <div class="pagebar" id="bk-bar"><h1>…</h1></div>
        <div id="bk-head"><span class="muted tiny">Loading…</span></div>
        <div id="bk-banners"></div>
        <div class="tabs2" id="bk-tabs"></div>
        <div id="bk-panel"></div>
        <audio id="bk-audio" preload="none" style="display:none"></audio>
        </div>
    """.trimIndent()
    val root = document.getElementById("bk-root") as? HTMLElement ?: return
    bkAudio = document.getElementById("bk-audio") as? HTMLAudioElement
    bkAudio?.addEventListener("ended") { bkPlaying = null; bkPanel(scope) }
    root.addEventListener("click") { ev -> bkClick(ev.target as? Element ?: return@addEventListener, ev, scope) }
    root.addEventListener("change") { ev -> bkChange(ev.target as? Element ?: return@addEventListener, scope) }
    root.addEventListener("keydown") { ev ->
        val inp = ev.target as? HTMLInputElement ?: return@addEventListener
        val k = inp.getAttribute("data-chipin") ?: return@addEventListener
        if ((ev as? KeyboardEvent)?.key != "Enter" || inp.value.isBlank()) return@addEventListener
        ev.preventDefault()
        val cur = bkPage?.book?.let { bkListOf(it, k) }.orEmpty()
        bkEdit(scope, k, (cur + inp.value.trim()).joinToString("; "))
    }
    // FR-281-5 — drag a part row to reorder (our order; the files are not renamed).
    root.addEventListener("dragstart") { ev -> (ev.target as? Element)?.closest(".ab-tr")?.let { r -> bkDragFrom = r.getAttribute("data-i")?.toIntOrNull(); r.classList.add("drag") } }
    root.addEventListener("dragover") { ev ->
        val r = (ev.target as? Element)?.closest(".ab-tr") ?: return@addEventListener
        if (bkDragFrom == null) return@addEventListener
        ev.preventDefault()
        val over = root.querySelectorAll(".ab-tr.over")
        for (i in 0 until over.length) (over.item(i) as? Element)?.classList?.remove("over")
        r.classList.add("over")
    }
    root.addEventListener("drop") { ev ->
        val r = (ev.target as? Element)?.closest(".ab-tr") ?: return@addEventListener
        val from = bkDragFrom ?: return@addEventListener
        ev.preventDefault(); bkDragFrom = null
        val to = r.getAttribute("data-i")?.toIntOrNull() ?: return@addEventListener
        val p = bkPage ?: return@addEventListener
        if (from == to) { bkPanel(scope); return@addEventListener }
        val ids = p.parts.map { it.id }.toMutableList()
        val moved = ids.removeAt(from); ids.add(to, moved)
        scope.launch {
            if (AudiobooksApi.order(bkId, ids)) { muToast("Order kept here · the files are not renamed"); bkReload(scope) } else { muToast("That order wasn't saved"); bkPanel(scope) }
        }
    }
    root.addEventListener("dragend") { bkDragFrom = null; val l = root.querySelectorAll(".ab-tr.drag, .ab-tr.over"); for (i in 0 until l.length) (l.item(i) as? Element)?.classList?.let { it.remove("drag"); it.remove("over") } }
    bkReload(scope)
}

private fun bkListOf(b: dev.jellystructure.model.Audiobook, key: String): List<String> = when (key) {
    "authors" -> b.authors; "narrators" -> b.narrators; "genres" -> b.genres; else -> emptyList()
}

private fun bkReload(scope: CoroutineScope) {
    scope.launch {
        val p = AudiobooksApi.page(bkId)
        if (p == null) {
            (document.getElementById("bk-head") as? HTMLElement)?.innerHTML = """<div class="note red">This audiobook isn't in the library (any more).</div>"""
            return@launch
        }
        bkPage = p
        bkSaveSays = AudiobooksApi.saveSays(bkId)
        bkRender(scope)
    }
}

private fun bkRender(scope: CoroutineScope) {
    val p = bkPage ?: return
    val b = p.book
    document.title = "Jellystructure — ${b.title}"
    (document.getElementById("bk-crumb") as? HTMLElement)?.innerHTML = """<a href="#/library?kind=audiobooks">Audiobooks</a> / ${b.title.esc()}"""
    (document.getElementById("bk-bar") as? HTMLElement)?.innerHTML = bkBar(p)
    (document.getElementById("bk-head") as? HTMLElement)?.innerHTML = bkHead(p)
    (document.getElementById("bk-banners") as? HTMLElement)?.innerHTML = bkBanners(p)
    (document.getElementById("bk-tabs") as? HTMLElement)?.innerHTML = BK_TABS.joinToString("") { (k, l) -> """<span data-tab="$k" class="${if (bkTab == k) "on" else ""}">$l</span>""" }
    document.getElementById("bk-bar")?.let { muWireMenus(it) }
    bkPanel(scope)
}

private fun bkBar(p: AudiobookPageDto): String = buildString {
    val b = p.book
    append("""<h1>${b.title.esc()}${b.year?.let { """ <span class="muted">($it)</span>""" } ?: ""}</h1><span class="spacer"></span>""")
    if (p.jellyfinUrl != null) append("""<span class="menu-wrap"><span class="btn sm ghost menu-btn">External links <span class="caret">▾</span></span><div class="menu"><a class="menu-item" href="${p.jellyfinUrl.esc()}" target="_blank" rel="noopener"><span class="mi-ic">↗</span><span>Open in Jellyfin<span class="mi-sub">The book’s folder — Jellyfin lists ${muPlural(b.partCount, "separate item")}</span></span></a></div></span>""")
    append("""<span class="menu-wrap"><span class="btn sm ghost menu-btn">⋯</span><div class="menu">""")
    append("""<div class="menu-item" data-a="reread"><span class="mi-ic">⟲</span><span>Re-read the files<span class="mi-sub">Tags, part order and lengths from Jellyfin</span></span></div>""")
    append("""<div class="menu-item" data-a="ask"><span class="mi-ic">⌕</span><span>Ask the providers again<span class="mi-sub">iTunes · Google Books · Open Library · Audnexus</span></span></div>""")
    append("</div></span>")
    val says = bkSaveSays ?: "cover.jpg"
    val tags = p.writeTags
    append("""<span class="split"><span class="btn primary" data-a="savesync">Save &amp; Sync ↻</span><span class="btn primary split-caret menu-btn"><span class="caret">▾</span></span><div class="menu">""")
    append("""<div class="menu-item" data-a="savesync"><span class="mi-ic">↻</span><span>Save &amp; Sync<span class="mi-sub">${says.esc()}, then ask Jellyfin to re-read</span></span></div>""")
    append("""<div class="menu-item" data-a="save"><span class="mi-ic">↓</span><span>${if (tags) "Save → cover + tags" else "Save → cover.jpg"}<span class="mi-sub">${if (tags) "Writes title, author, narrator, description into every part" else "Tag writing is off in Settings — everything else stays here"}</span></span></div>""")
    append("""<div class="menu-item" data-a="sync"><span class="mi-ic">↻</span><span>Sync Jellyfin<span class="mi-sub">Ask Jellyfin to re-read (no rewrite)</span></span></div>""")
    append("</div></span>")
}

private fun bkHead(p: AudiobookPageDto): String = buildString {
    val b = p.book
    append("""<div class="mu-pb">""")
    append(muCoverHtml(b.title, p.coverUrl, extraClass = "bk-cover"))
    append("<div>")
    b.subtitle?.let { append("""<div class="muted" style="font-size:1rem;margin-bottom:4px">${it.esc()}</div>""") }
    if (b.authors.isNotEmpty()) append("""<div class="mu-by">by ${b.authors.joinToString(" &amp; ") { a -> """<a href="#/audiobook-author/${bkAuthorId(a)}">${a.esc()}</a>""" }}</div>""")
    append("""<div class="mu-pbm">""")
    append(if (b.narrators.isNotEmpty()) """<span>read by ${b.narrators.joinToString(", ").esc()}</span><span class="sep">·</span>""" else """<span style="color:var(--ink-dim)">no narrator</span><span class="sep">·</span>""")
    b.year?.let { append("""<span>$it</span><span class="sep">·</span>""") }
    append("""<span>${muTotal(b.durationMs)} · ${if (b.partCount == 1) "1 file" else "${b.partCount} parts"}</span>""")
    p.parts.map { it.format }.filter { it.isNotEmpty() }.distinct().takeIf { it.isNotEmpty() }?.let { append("""<span class="sep">·</span><span class="mu-fmt">${it.joinToString(" + ").esc()}</span>""") }
    b.series?.let { s -> append(""" <span class="chip" style="font-size:.74rem">${s.esc()}${b.seriesPosition?.let { " · Book ${it.esc()}" } ?: ""}</span>""") }
    append("</div>")
    append("""<div class="mu-acts"><span class="ab-srcchip">${if (b.locked) "$MU_LOCK " else ""}${p.source.esc()}</span><span class="chip" style="cursor:pointer" data-a="lock">${if (b.locked) "Locked · a re-read won’t change what you typed" else "Lock"}</span></div>""")
    append("""<div class="tiny muted" style="margin-top:10px;">Jellyfin reads no metadata file for audiobooks — ${if (p.writeTags) "so what you save here is written into the files’ own tags." else "what you type here reaches Ravilo, and Jellyfin’s own apps keep showing the files’ tags."}</div>""")
    p.library?.let { append("""<div class="tiny muted" style="margin-top:4px;">${it.esc()}${b.folderPath?.let { f -> """ · <span class="mono">${f.esc()}</span>""" } ?: ""}</div>""") }
    append("</div></div>")
}

/** The author page's id — the shared rule the server groups authors by. */
internal fun bkAuthorId(name: String): String = dev.jellystructure.model.AudiobookRules.authorId(name)

private fun bkBanners(p: AudiobookPageDto): String = buildString {
    val b = p.book
    if (p.splitPreview.size > 1) {
        val names = p.splitPreview.joinToString(" and ") { "<i>${it.title.esc()}</i>" }
        append("""<div class="mu-note w"><span class="badge warn">Needs you</span><div class="t"><b>This folder looks like ${if (p.splitPreview.size == 2) "two" else p.splitPreview.size.toString()} books.</b> The parts carry different book titles — $names.</div><span class="btn sm primary" data-a="split">Split into books…</span><span class="btn sm ghost" data-a="onebook">It’s one book</span></div>""")
    }
    if (p.splitSiblings.isNotEmpty()) {
        append("""<div class="note blue" style="margin-bottom:12px;display:flex;gap:10px;align-items:center;flex-wrap:wrap"><span>${if (b.splitFrom != null) "Split off one folder together with" else "This folder was split; the other part${if (p.splitSiblings.size == 1) " is" else "s are"}"} ${p.splitSiblings.joinToString(", ") { """<a href="#/audiobook/${it.id}">${it.title.esc()}</a>""" }}. The files were not moved.</span><span class="spacer" style="flex:1"></span><span class="btn sm ghost" data-a="join">Join back into one book</span></div>""")
    }
    if (b.gap.isNotEmpty() && !b.gapDismissed) {
        val g = b.gap.first()
        val why = if (b.gapKind == dev.jellystructure.model.AudiobookGap.NUMBERED_WRONG) " The file names and Jellyfin disagree about the numbers — it may just be numbered wrong." else ""
        append("""<div class="mu-note w"><span class="badge warn">A part is missing</span><div class="t"><b>Part ${b.gap.joinToString(", ")} ${if (b.gap.size == 1) "is" else "are"} not in the folder.</b> The numbering goes ${g - 1} → ${b.gap.last() + 1}. A listener will hear the book jump; Parts shows the gap.$why</div><span class="btn sm ghost" data-a="gapok">It’s just numbered wrong</span></div>""")
    }
    if (b.narrators.isEmpty()) append("""<div class="tiny" style="color:var(--ink-dim);margin:-4px 0 12px;">No narrator in the tags — type one in Details if you know it.</div>""")
}

private fun bkPanel(scope: CoroutineScope) {
    val el = document.getElementById("bk-panel") as? HTMLElement ?: return
    val p = bkPage ?: return
    val tabs = document.querySelectorAll("#bk-tabs [data-tab]")
    for (i in 0 until tabs.length) (tabs.item(i) as? HTMLElement)?.let { it.className = if (it.getAttribute("data-tab") == bkTab) "on" else "" }
    when (bkTab) {
        "parts" -> el.innerHTML = bkParts(p)
        "chapters" -> el.innerHTML = bkChapters(p)
        "artwork" -> { el.innerHTML = """<span class="muted tiny">Loading…</span>"""; scope.launch { bkArt = AudiobooksApi.artwork(bkId); el.innerHTML = bkArtwork(p) } }
        "listeners" -> el.innerHTML = bkListeners(p)
        "history" -> { el.innerHTML = """<span class="muted tiny">Loading…</span>"""; scope.launch { el.innerHTML = muHistory(bkId) } }
        else -> el.innerHTML = bkDetails(p)
    }
}

// ── Details: the editor and the Suggestions rail (FR-281-2/3) ──

private fun bkOrigin(p: AudiobookPageDto, key: String): String {
    val o = p.book.origins[key] ?: return ""
    val t = when (o) { AudiobookOrigin.FILES -> "from the files"; AudiobookOrigin.TYPED -> "typed here"; else -> "from $o" }
    return """<span class="src">$t</span>"""
}

private fun bkDetails(p: AudiobookPageDto): String = buildString {
    val b = p.book
    append("""<div class="ab-grid"><div><div class="ab-fields">""")
    for (f in BK_FIELDS) {
        val v = bkValue(b, f.key)
        append("""<div class="ab-f${if (f.wide) " wide" else ""}"><label>${f.label}${bkOrigin(p, f.key)}</label>""")
        when (f.kind) {
            "chips" -> {
                val list = bkListOf(b, f.key)
                append("""<div class="ab-chips${if (list.isEmpty()) " empty" else ""}">""")
                list.forEachIndexed { i, x -> append("""<span class="chip">${x.esc()} <span style="cursor:pointer;opacity:.6" data-rmchip="${f.key}:$i">✕</span></span>""") }
                append("""<input data-chipin="${f.key}" placeholder="${f.ph.esc()}"></div>""")
            }
            "lang" -> {
                append("""<select data-f="language">""")
                val opts = if (v != null && BK_LANGS.none { it.first == v }) BK_LANGS + (v to v) else BK_LANGS
                for ((c, l) in opts) append("""<option value="${c.esc()}"${if ((v ?: "") == c) " selected" else ""}>${l.esc()}</option>""")
                append("</select>")
            }
            "area" -> append("""<textarea data-f="${f.key}" placeholder="${f.ph.esc()}" class="${if (v.isNullOrBlank()) "empty" else ""}">${v.orEmpty().esc()}</textarea>""")
            else -> append("""<input data-f="${f.key}" value="${v.orEmpty().esc()}" placeholder="${f.ph.esc()}" class="${if (v.isNullOrBlank()) "empty" else ""}">""")
        }
        append("</div>")
    }
    append("</div>")
    append("""<div class="tiny muted" style="margin-top:10px">Saved as you type — the same write-through as everywhere else. ${if (p.writeTags) "Save writes it into the files." else "Ravilo shows it; Jellyfin’s own apps don’t, because tag writing is off."}</div></div>""")
    // The rail.
    append("""<div><div class="mu-sec" style="margin-top:0">Suggestions <span class="tiny muted" style="text-transform:none;letter-spacing:0;font-weight:500">""")
    append(b.suggestionsAskedAt?.let { "asked ${dev.jellystructure.formatStoredTs(it.toString())}" } ?: "not asked yet")
    append("""</span><span class="spacer" style="flex:1"></span><span class="btn sm ghost" data-a="ask">${if (b.suggestionsAskedAt == null) "Ask" else "Ask again"}</span></div><div class="ab-rail">""")
    if (b.suggestions.isEmpty()) append("""<div class="ab-sug none"><div class="nt">No provider asked yet. <b>Ask</b> checks iTunes, Google Books, Open Library and Audnexus — nothing is written until you apply it.</div></div>""")
    for (s in b.suggestions) append(bkSugCard(b, s))
    append("</div></div></div>")
}

internal fun bkValue(b: dev.jellystructure.model.Audiobook, key: String): String? = when (key) {
    "title" -> b.title; "subtitle" -> b.subtitle; "series" -> b.series; "series_position" -> b.seriesPosition
    "year" -> b.year?.toString(); "publisher" -> b.publisher; "language" -> b.language; "description" -> b.description
    else -> null
}

private fun bkSugCard(b: dev.jellystructure.model.Audiobook, s: AudiobookSuggestion): String = buildString {
    val where = when (s.provider) { "Audnexus" -> "ASIN"; else -> "" }
    val head = """<div class="h">${s.provider.esc()}${if (where.isNotEmpty()) """<span class="p">$where</span>""" else ""}</div>"""
    val asin = if (s.provider == "Audnexus") """<div class="row center" style="gap:6px;margin-top:8px"><input id="bk-asin" class="input" placeholder="Audible ASIN, e.g. B0…" style="flex:1;font-size:.8rem;padding:5px 8px"><span class="btn sm ghost" data-a="asin">Ask</span></div>""" else ""
    if (!s.found) { append("""<div class="ab-sug none">$head<div class="nt">${s.note.esc()}</div>$asin</div>"""); return@buildString }
    append("""<div class="ab-sug">$head<div class="hit"><div class="th" style="${s.coverUrl?.let { muImageStyle(it) } ?: muWordmarkStyle(s.title ?: b.title)}"></div><div><b style="font-size:.84rem">${(s.title ?: "").esc()}</b><div class="tiny muted">${listOfNotNull(s.author, s.year?.toString()).joinToString(" · ").esc()}</div></div></div>""")
    append("""<div class="tiny muted" style="margin:8px 0 2px">What it would fill</div>""")
    for ((k, v) in s.fields) {
        val done = k in s.applied
        append("""<div class="fl${if (done) " done" else ""}"><span class="k">${BK_FIELD_LABEL[k] ?: k}</span><span class="v" title="${v.esc()}">${if (done) "✓ " else ""}${v.esc()}</span>""")
        if (!done) append("""<span class="btn sm ghost" data-apply="${s.provider.esc()}" data-field="$k">Apply</span>""")
        append("</div>")
    }
    if (s.fields.keys.any { it !in s.applied }) append("""<div style="margin-top:8px"><span class="btn sm" data-apply="${s.provider.esc()}" data-field="*">Apply all</span></div>""")
    append(asin)
    append("</div>")
}

// ── Parts (FR-281-5) ──

private fun bkParts(p: AudiobookPageDto): String = buildString {
    val b = p.book
    val short = p.parts.count { it.under5 }
    if (short > 0) append("""<div class="note blue" style="margin-bottom:12px">Jellyfin only saves a position after 5 minutes of a file, so it never remembers one in the ${if (short == 1) "part" else "$short parts"} shorter than that. Ravilo keeps the book’s position itself.</div>""")
    val showAlbum = p.parts.any { it.album != null }
    append("""<div class="mu-scroll"><table class="mu-tbl"><thead><tr><th></th><th>#</th><th>Title tag</th>${if (showAlbum) "<th>Album tag</th>" else ""}<th>Length</th><th>Format</th><th>Jellyfin position</th><th></th></tr></thead><tbody>""")
    var prev: Int? = null
    val gaps = if (b.gapDismissed) emptyList() else b.gap
    val cols = if (showAlbum) 6 else 5
    p.parts.forEachIndexed { i, r ->
        val n = r.number
        if (n != null) {
            gaps.filter { g -> g < n && (prev == null || g > prev!!) }.forEach { g ->
                append("""<tr class="gap"><td></td><td class="n">$g</td><td colspan="$cols">part $g · not in the folder</td></tr>""")
            }
            prev = n
        }
        val jp = when {
            r.under5 -> """<span class="tiny muted">— under 5 minutes</span>"""
            r.jellyfinPositionMs != null -> """<span class="mono tiny">saved at ${muLen(r.jellyfinPositionMs)}</span>"""
            else -> """<span class="tiny muted">—</span>"""
        }
        append("""<tr class="ab-tr" draggable="true" data-i="$i"><td class="ab-grab" title="Drag to reorder">⋮⋮</td><td class="n">${n ?: ""}</td><td>${r.title.esc()}</td>""")
        if (showAlbum) append("""<td class="dim">${r.album.orEmpty().esc()}</td>""")
        append("""<td class="num">${muLen(r.lengthMs)}</td><td><span class="mu-fmt">${r.format.esc()}</span></td><td>$jp</td>""")
        append("""<td>${if (r.browser) """<span class="mu-play${if (bkPlaying == r.id) " on" else ""}" data-play="${r.id}">${if (bkPlaying == r.id) "❚❚" else "▶"}</span>""" else ""}</td></tr>""")
    }
    append("</tbody></table></div>")
    append("""<div class="tiny muted" style="margin-top:10px">Drag a row when the numbering is wrong — the order is kept here and the phone plays it in this order; the files are not renamed. Jellyfin’s position is yours, as the admin signed in here.</div>""")
}

// ── Chapters (FR-281-6) ──

private fun bkChapters(p: AudiobookPageDto): String = buildString {
    val b = p.book
    val both = b.partCount == 1 && b.embeddedChapters.isNotEmpty()
    val src = if (b.chapterSource == "embedded" && b.embeddedChapters.isNotEmpty()) "Embedded in the file — ${muPlural(b.embeddedChapters.size, "chapter")}"
        else if (b.partCount == 1) "From the file boundaries — one file, so one chapter"
        else "From the file boundaries — the files carry no chapters of their own, so each part is one"
    append("""<div class="row center" style="gap:10px;margin-bottom:10px;flex-wrap:wrap"><span class="tiny muted">$src</span><span class="spacer"></span>""")
    if (both) append("""<span class="seg"><span data-chsrc="embedded" class="${if (b.chapterSource == "embedded") "on" else ""}">Use embedded</span><span data-chsrc="files" class="${if (b.chapterSource != "embedded") "on" else ""}">Use file boundaries</span></span>""")
    append("</div>")
    append("""<div class="mu-scroll"><table class="mu-tbl ab-ch"><thead><tr><th>#</th><th>Title · rename inline</th><th>Starts</th><th>Length</th></tr></thead><tbody>""")
    p.chapters.forEachIndexed { i, c ->
        append("""<tr><td class="n">${i + 1}</td><td><input value="${c.title.ifBlank { "Chapter ${i + 1}" }.esc()}" data-ch="$i"></td><td class="num mono" style="font-size:.76rem">${muLen(c.startMs)}</td><td class="num">${muLen(c.lengthMs)}</td></tr>""")
    }
    append("</tbody></table></div>")
}

// ── Artwork (FR-281-7) ──

private fun bkArtwork(p: AudiobookPageDto): String = buildString {
    val art = bkArt
    append("""<div class="mu-sec">Currently in use</div><div class="mu-art">""")
    if (p.coverUrl != null) {
        append("""<div class="mu-aw use"><div class="im" style="${muImageStyle(p.coverUrl)}"></div><div class="cap"><b>${(art?.inUse ?: "cover").esc()}</b><span class="d">${art?.source?.let { "from ${it.esc()}" } ?: if (art?.inUse == "embedded") "embedded in the parts" else "on disk"}</span>""")
        if (art?.inUse != null && art.inUse != "embedded") append("""<span class="chip" style="font-size:.66rem;cursor:pointer;margin-left:auto" data-a="coverlock">${if (art.locked) "🔒 locked" else "🔓 lock"}</span><span class="chip" style="font-size:.66rem;cursor:pointer" data-a="coverclear">Clear</span>""")
        append("</div></div>")
    } else append("""<div class="mu-aw"><div class="im chk" style="display:flex;align-items:center;justify-content:center"><span class="tiny" style="color:var(--warn);font-weight:600">no cover.jpg · no embedded art</span></div><div class="cap"><b>cover</b><span class="d">missing — a task</span></div></div>""")
    append("""<label class="mu-aw" style="cursor:pointer;border-style:dashed"><div class="im" style="display:flex;align-items:center;justify-content:center;flex-direction:column;gap:6px;color:var(--ink-soft);font-size:.8rem">⬆<span>Upload a cover</span><span class="tiny muted">written as cover.jpg in the book folder</span></div><input type="file" id="bk-upload" accept="image/*" style="display:none"></label></div>""")
    append("""<div class="mu-sec">Candidates · from the providers’ suggestions</div>""")
    val cands = art?.candidates.orEmpty()
    if (cands.isEmpty()) append("""<div class="tiny muted">No candidates — ${if (p.book.suggestionsAskedAt == null) "ask the providers on Details first" else "no provider found this title"}. Upload a photo of the cover, or a scan.</div>""")
    else append(artGrid(cands, "book"))
}

// ── Listeners (FR-281-7, read-only) ──

private fun bkListeners(p: AudiobookPageDto): String = buildString {
    append("""<div class="tiny muted" style="margin-bottom:10px">Read-only — how far each person has got, from Ravilo. The admin never moves a listener’s position.</div>""")
    if (p.listeners.isEmpty()) { append("""<div class="muted" style="padding:20px 4px">Nobody has started this book in Ravilo yet.</div>"""); return@buildString }
    append("""<table class="mu-tbl"><tbody>""")
    for (l in p.listeners) {
        val pct = (l.progress * 100).toInt()
        val what = when {
            l.finishedAt != null -> "finished ${dev.jellystructure.formatStoredTs(l.finishedAt.toString())}"
            l.leftMs != null -> "${muTotal(l.leftMs)} left · part ${l.part ?: 1}${l.updatedAt?.let { " · " + dev.jellystructure.formatStoredTs(it.toString()) } ?: ""}"
            else -> """<span class="muted">not started</span>"""
        }
        append("""<tr><td style="width:44px"><span class="ab-ring" style="--p:$pct"></span></td><td><b>${l.name.esc()}</b></td><td>$what</td></tr>""")
    }
    append("</tbody></table>")
}

// ── actions ──

private fun bkEdit(scope: CoroutineScope, field: String, value: String?) {
    scope.launch {
        val b = AudiobooksApi.edit(bkId, field, value) ?: return@launch muToast("That wasn't saved")
        bkPage = bkPage?.copy(book = b)
        muToast("Saved")
        bkRender(scope)
    }
}

private fun bkChange(t: Element, scope: CoroutineScope) {
    (t as? HTMLInputElement)?.let { inp ->
        if (inp.id == "bk-upload") {
            val file = muPickedFile(inp) ?: return
            muToast("Uploading…")
            muUpload("/api/audiobooks/${bkId}/artwork/upload", file) { ok -> muToast(if (ok) "cover.jpg written · Jellyfin re-reads it on the next sync" else "That image couldn't be used"); bkReload(scope) }
            return
        }
        inp.getAttribute("data-f")?.let { bkEdit(scope, it, inp.value); return }
        inp.getAttribute("data-ch")?.toIntOrNull()?.let { i ->
            scope.launch { if (AudiobooksApi.chapters(bkId, null, mapOf(i.toString() to inp.value)) != null) muToast("Chapter renamed") else muToast("That wasn't saved") }
            return
        }
    }
    (t as? HTMLTextAreaElement)?.let { ta -> ta.getAttribute("data-f")?.let { bkEdit(scope, it, ta.value) }; return }
    (t as? HTMLSelectElement)?.let { s -> s.getAttribute("data-f")?.let { bkEdit(scope, it, s.value.ifEmpty { null }) } }
}

/**
 * Phase 287 (FR-287-4) — *Split into two books…* opens a preview first: two columns, a title field each, the parts
 * dragged (or nudged with ‹ ›) between them; *Split* stays disabled while a column is empty. The tags pre-fill the
 * columns (280's grouping); nothing moves on disk — the split is virtual, as 280 built it.
 */
private fun bkSplitPreview(p: AudiobookPageDto, scope: CoroutineScope) {
    val parts = p.parts.sortedBy { it.position }
    val pre = p.splitPreview.filter { it.partIds.isNotEmpty() }
    val cols = arrayOf(
        (pre.getOrNull(0)?.partIds ?: parts.map { it.id }).toMutableList(),
        pre.drop(1).flatMap { it.partIds }.toMutableList(),
    )
    val titles = arrayOf(pre.getOrNull(0)?.title ?: p.book.title, pre.getOrNull(1)?.title ?: "")
    fun row(id: String, col: Int): String {
        val r = parts.firstOrNull { it.id == id } ?: return ""
        val len = r.lengthMs?.let { " · " + muTotal(it) } ?: ""
        return """<div class="ab-spart" draggable="true" data-pid="${r.id.esc()}"><span class="ab-grab">⋮⋮</span><span class="mono tiny">${r.number ?: ""}</span><span class="t">${r.title.esc()}<span class="tiny muted">$len</span></span><span class="ab-mv" data-mv="${if (col == 0) 1 else 0}" title="${if (col == 0) "Move to book 2" else "Move to book 1"}">${if (col == 0) "›" else "‹"}</span></div>"""
    }
    fun partsHtml(i: Int) = cols[i].joinToString("") { row(it, i) }.ifEmpty { """<div class="ab-sempty">Drag parts here</div>""" }
    fun colHtml(i: Int) = """<div class="ab-scol" data-g="$i"><div class="ab-sh"><input data-gt="$i" value="${titles[i].esc()}" placeholder="Book ${i + 1}’s title"><span class="tiny muted">${if (i == 0) "keeps this page" else "gets a page of its own"} · <span data-gc="$i">${cols[i].size}</span> parts</span></div>
        <div class="ab-parts" data-g="$i">${partsHtml(i)}</div></div>"""
    muModal("""<h3>Split this folder into two books</h3>
        <p>Drag the parts between the columns and name each book. <b>The files are not moved</b> — Jellyfin keeps showing one folder; Ravilo shows two books.</p>
        <div class="ab-scols">${colHtml(0)}${colHtml(1)}</div>
        <div class="row" style="justify-content:flex-end;gap:8px;margin-top:12px;"><span class="btn ghost" data-m="no">Cancel</span><span class="btn primary" id="ab-split-go">Split</span></div>""") {}
    val m = document.getElementById("mu-modal") as? HTMLElement ?: return
    (m.querySelector(".card") as? HTMLElement)?.style?.maxWidth = "820px"
    fun refresh() {
        for (i in 0..1) {
            (m.querySelector(".ab-parts[data-g='$i']") as? HTMLElement)?.innerHTML = partsHtml(i)
            (m.querySelector("[data-gc='$i']") as? HTMLElement)?.textContent = cols[i].size.toString()
        }
        val ok = cols[0].isNotEmpty() && cols[1].isNotEmpty()
        (m.querySelector("#ab-split-go") as? HTMLElement)?.classList?.toggle("is-off", !ok)
    }
    fun move(id: String, to: Int) { cols[0].remove(id); cols[1].remove(id); cols[to].add(id); refresh() }
    var dragging: String? = null
    m.addEventListener("dragstart") { ev -> dragging = (ev.target as? Element)?.closest(".ab-spart")?.getAttribute("data-pid") }
    m.addEventListener("dragover") { ev -> if ((ev.target as? Element)?.closest(".ab-scol") != null) ev.preventDefault() }
    m.addEventListener("drop") { ev ->
        val col = (ev.target as? Element)?.closest(".ab-scol")?.getAttribute("data-g")?.toIntOrNull() ?: return@addEventListener
        ev.preventDefault(); dragging?.let { move(it, col) }; dragging = null
    }
    m.addEventListener("click") { ev ->
        val t = ev.target as? Element ?: return@addEventListener
        t.closest("[data-mv]")?.let { b -> val id = b.closest(".ab-spart")?.getAttribute("data-pid") ?: return@let; move(id, b.getAttribute("data-mv")!!.toInt()); return@addEventListener }
        if (t.closest("#ab-split-go") != null) {
            if (cols[0].isEmpty() || cols[1].isEmpty()) return@addEventListener
            val groups = (0..1).map { i -> dev.jellystructure.model.AudiobookSplitGroupRequest(((m.querySelector("[data-gt='$i']") as? HTMLInputElement)?.value ?: "").trim().ifBlank { "Book ${i + 1}" }, cols[i].toList()) }
            m.remove()
            scope.launch { if (AudiobooksApi.split(bkId, groups) != null) { muToast("Split · each book has its own page now"); bkReload(scope) } else muToast("That didn't work") }
        }
    }
    refresh()
}

private fun bkClick(t: Element, ev: org.w3c.dom.events.Event, scope: CoroutineScope) {
    val p = bkPage ?: return
    val b = p.book
    t.closest("#bk-tabs [data-tab]")?.let { tab ->
        bkTab = tab.getAttribute("data-tab") ?: "details"
        Router.updateQuery(mapOf("tab" to bkTab))
        bkPanel(scope); return
    }
    t.closest("[data-rmchip]")?.let { c ->
        val (k, i) = (c.getAttribute("data-rmchip") ?: return).split(':').let { it[0] to it[1].toInt() }
        val list = bkListOf(b, k).toMutableList().also { if (i in it.indices) it.removeAt(i) }
        bkEdit(scope, k, list.joinToString("; ").ifEmpty { null }); return
    }
    t.closest("[data-apply]")?.let { a ->
        val prov = a.getAttribute("data-apply") ?: return
        val f = a.getAttribute("data-field") ?: return
        val fields = if (f == "*") b.suggestions.firstOrNull { it.provider == prov }?.fields?.keys?.filter { it !in (b.suggestions.first { s -> s.provider == prov }.applied) }.orEmpty() else listOf(f)
        scope.launch {
            val nb = AudiobooksApi.apply(bkId, prov, fields) ?: return@launch muToast("That wasn't applied")
            bkPage = bkPage?.copy(book = nb); muToast("Applied from $prov"); bkReload(scope)
        }
        return
    }
    t.closest("[data-chsrc]")?.let { c ->
        scope.launch { if (AudiobooksApi.chapters(bkId, c.getAttribute("data-chsrc"), null) != null) bkReload(scope) }
        return
    }
    t.closest("[data-play]")?.let { pl ->
        val pid = pl.getAttribute("data-play") ?: return
        val audio = bkAudio ?: return
        if (bkPlaying == pid) { audio.pause(); bkPlaying = null; bkPanel(scope); return }
        scope.launch {
            val url = AudiobooksApi.partStream(pid) ?: return@launch muToast("Jellyfin can’t direct-play that here")
            audio.src = url; audio.play(); bkPlaying = pid; bkPanel(scope)
        }
        return
    }
    t.closest("[data-use]")?.let { u ->
        val i = u.getAttribute("data-use")?.toIntOrNull() ?: return
        val c = bkArt?.candidates?.getOrNull(i) ?: return
        scope.launch { if (AudiobooksApi.useCover(bkId, c)) { muToast("cover.jpg written from ${c.source}"); bkReload(scope) } else muToast("That image couldn't be used") }
        return
    }
    val a = t.closest("[data-a]")?.getAttribute("data-a") ?: return
    ev.preventDefault()
    when (a) {
        "lock" -> scope.launch { AudiobooksApi.lock(bkId, !b.locked)?.let { bkPage = bkPage?.copy(book = it); bkRender(scope) } }
        "ask", "asin" -> scope.launch {
            val asin = if (a == "asin") (document.getElementById("bk-asin") as? HTMLInputElement)?.value else null
            muToast("Asking iTunes · Google Books · Open Library · Audnexus…")
            val nb = AudiobooksApi.suggest(bkId, asin) ?: return@launch muToast("The providers didn't answer")
            bkPage = bkPage?.copy(book = nb)
            if (bkTab != "details") bkTab = "details"
            bkRender(scope)
        }
        "reread" -> scope.launch { muToast("Re-reading ${muPlural(b.partCount, "file")} from Jellyfin"); if (bkPost("reread")) bkReload(scope) }
        "save", "savesync" -> scope.launch { muToast(AudiobooksApi.save(bkId, a == "savesync") ?: "That wasn't saved"); bkReload(scope) }
        "sync" -> scope.launch { muToast(if (bkPost("sync")) "Sync requested ↻" else "Jellyfin didn't answer") }
        "split" -> bkSplitPreview(p, scope)
        "join" -> scope.launch {
            val nb = AudiobooksApi.join(bkId) ?: return@launch muToast("That didn't work")
            muToast("Joined back into one book")
            if (nb.id != bkId) dev.jellystructure.App.navigate("/audiobook/${nb.id}") else bkReload(scope)
        }
        "onebook" -> scope.launch { if (AudiobooksApi.dismiss(bkId, "two_in_one") != null) { muToast("Noted · this flag won’t come back for this folder"); bkReload(scope) } }
        "gapok" -> scope.launch { if (AudiobooksApi.dismiss(bkId, "gap") != null) { muToast("Noted · this flag won’t come back for this folder"); bkReload(scope) } }
        "coverlock" -> scope.launch { AudiobooksApi.lockCover(bkId, !(bkArt?.locked ?: false)); bkPanel(scope) }
        "coverclear" -> scope.launch { if (AudiobooksApi.clearCover(bkId)) { muToast("Cover removed"); bkReload(scope) } }
    }
}

private suspend fun bkPost(what: String): Boolean = when (what) {
    "reread" -> AudiobooksApi.reread(bkId) != null
    "sync" -> AudiobooksApi.sync(bkId)
    else -> false
}
