package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.MusicApi
import dev.jellystructure.elemNearViewportBottom
import dev.jellystructure.historyReplaceState
import dev.jellystructure.model.MusicBrowseDto
import dev.jellystructure.model.MusicConvertRequest
import dev.jellystructure.model.MusicSongRow
import dev.jellystructure.observeSections
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
 * Phase 278 (FR-278-1..4) — Library → Music: Albums · Artists · Songs, the music facets, a selection with bulk
 * actions, and the states (not mapped, mapped but not scanned, empty, everything unmatched, partly matched, a
 * search with no results). Every row and every count comes from `GET /api/music/browse`; this page only renders.
 */

private var muView = "artists"   // Phase 287 (FR-287-1) — Artists first
private var muQuery = ""
private var muSort: String? = null
/** Phase 293 (FR-293-2) — a Dashboard row's triage key; the server narrows the list to it and chooses the view. */
private var muFilter: String? = null
/** Phase 292 (FR-292-11) — *Hide* per facet (`x.<key>=`), Songs by one artist (`artist=`), and the songs ticked. */
private val muHidden = LinkedHashMap<String, MutableSet<String>>()
private var muArtist: String? = null
private val muSongSel = LinkedHashSet<String>()
/**
 * Phase 292 (FR-292-16) — *select all shown* is a mode, not a list: with it on, the selection is **the filter**,
 * the bar says the whole-set number, and the write sends the filter for the server to resolve. Ticking any row
 * leaves the mode, so what the bar counts is always either the filter or the ticks, never a muddle of both.
 */
private var muSongAll = false
private val muFacets = LinkedHashMap<String, MutableSet<String>>()
private val muSelected = LinkedHashSet<String>()
private var muOpenFacet: String? = null
private var muDto: MusicBrowseDto? = null
private var muLoadJob: Job? = null
private var muPollJob: Job? = null
private var muDocWired = false
private var muScope: CoroutineScope? = null

// Phase 278 (FR-278-14) — the Songs rows arrive a page at a time and are appended at `#mu-sentinel`, which is the
// films Library's scroller (`Library.kt`: `LIB_SLICE`, `loadMore(reset)`, `libLoading`/`libPendingReset`) rather
// than a second one. `muPage` counts the pages loaded for the current filter set, so the next fetch is `muPage + 1`;
// a filter, facet, hidden value, search, sort, artist, version or *Show every copy* change goes through `muLoad`,
// which starts a fresh sequence. Albums and Artists are not paged — 809 and 377 rows — so `muEnd` is true for them
// as soon as the first answer lands.
private const val MU_SLICE = 100   // Songs rows fetched per page
private var muPage = 0
private var muLoaded = 0           // Songs rows in the table
private var muEnd = false
private var muFetching = false
/** A reset that arrives mid-fetch is remembered and run once the page in flight lands, never dropped. */
private var muPendingReset = false
/** Phase 292 (FR-292-12) — the ids the rows loaded so far stand for; a hand-tick's set is built out of these. */
private val muLoadedIds = LinkedHashSet<String>()

/**
 * Phase 292 (FR-292-16) — the Songs filter as this page is reading it, which is the same thing [muUrl] writes into
 * the address bar and [muFetch] asks `GET /api/music/browse` for. Sent instead of a list of ids, so the set the
 * server writes is the set on screen by construction. `page`/`page_size` are deliberately absent: the filter stands
 * for the whole filtered set, not for the pages that happen to be loaded.
 */
private fun muSongFilter() = dev.jellystructure.model.MusicSongFilter(
    q = muQuery.takeIf { it.isNotBlank() },
    f = muFacets.filterValues { it.isNotEmpty() }.mapValues { (_, v) -> v.toList() },
    x = muHidden.filterValues { it.isNotEmpty() }.mapValues { (_, v) -> v.toList() },
    artist = muArtist,
    copies = if (edEveryCopy) "all" else null,
    filter = muFilter,
)

private fun muUrl(): String = buildList {
    add("kind=music")
    muFilter?.let { add("filter=${dev.jellystructure.encodeURIComponent(it)}") }
    if (muView != "artists" && muFilter == null) add("mview=$muView")
    if (muQuery.isNotBlank()) add("q=${dev.jellystructure.encodeURIComponent(muQuery)}")
    muSort?.let { add("sort=$it") }
    muFacets.filterValues { it.isNotEmpty() }.forEach { (k, v) -> add("f.$k=${v.joinToString(",") { dev.jellystructure.encodeURIComponent(it) }}") }
    muHidden.filterValues { it.isNotEmpty() }.forEach { (k, v) -> add("x.$k=${v.joinToString(",") { dev.jellystructure.encodeURIComponent(it) }}") }
    muArtist?.let { add("artist=${dev.jellystructure.encodeURIComponent(it)}") }
}.joinToString("&").let { "#/library?$it" }

fun renderMusicLibrary(container: Element, scope: CoroutineScope, query: Map<String, String>) {
    muView = query["mview"]?.takeIf { it in setOf("albums", "artists", "songs") } ?: "artists"
    muQuery = query["q"].orEmpty()
    muSort = query["sort"]
    muFilter = query["filter"]?.takeIf { it.isNotBlank() }
    muFacets.clear(); muSelected.clear(); muOpenFacet = null; muDto = null; muHidden.clear(); muSongSel.clear(); muSongAll = false
    query.filterKeys { it.startsWith("f.") }.forEach { (k, v) -> muFacets[k.removePrefix("f.")] = v.split(',').filter { it.isNotBlank() }.toMutableSet() }
    query.filterKeys { it.startsWith("x.") }.forEach { (k, v) -> muHidden[k.removePrefix("x.")] = v.split(',').filter { it.isNotBlank() }.toMutableSet() }
    muArtist = query["artist"]?.takeIf { it.isNotBlank() }
    // Phase 292 — the mockup's and the spec's `vi=` / `vx=` read as the Version facet's Only / Hide.
    query["vi"]?.let { muFacets.getOrPut("version") { LinkedHashSet() } += it.split(',').filter { v -> v.isNotBlank() } }
    query["vx"]?.let { muHidden.getOrPut("version") { LinkedHashSet() } += it.split(',').filter { v -> v.isNotBlank() } }
    if (query["vi"] != null || query["vx"] != null) muView = "songs"

    container.innerHTML = """
        <div class="pagebar">
          <h1>Library</h1>
          <span class="spacer"></span>
          <span class="searchwrap"><input id="mu-search" class="input" type="search" placeholder="⌕ search albums, artists, songs…" style="width:240px;flex-shrink:0;"></span>
          ${libraryKindSeg("music")}
        </div>
        <div id="mu-lib"><span class="muted tiny">Loading…</span></div>
        <div class="row center" style="margin-top:14px;justify-content:center;min-height:22px;" id="mu-loadmore"></div>
        <div id="mu-sentinel" style="height:1px;"></div>
    """.trimIndent()
    (document.getElementById("mu-search") as? HTMLInputElement)?.let { inp ->
        inp.value = muQuery
        var debounce: Job? = null
        inp.addEventListener("input") {
            debounce?.cancel()
            debounce = scope.launch { delay(250); muQuery = inp.value; muSelected.clear(); muLoad(scope) }
        }
    }
    wireLibraryKindSeg(container)
    val root = document.getElementById("mu-lib") as? HTMLElement ?: return
    root.addEventListener("click") { ev -> muClick(ev.target as? Element ?: return@addEventListener, ev, scope) }
    root.addEventListener("change") { ev ->
        val sel = ev.target as? HTMLSelectElement ?: return@addEventListener
        if (sel.id == "mu-sort") { muSort = sel.value.takeIf { it.isNotEmpty() }; muLoad(scope) }
    }
    if (!muDocWired) {
        muDocWired = true
        document.addEventListener("click") { ev ->
            // FR-278-15 — closing an open facet is a chrome change; it must not touch the rows.
            if (muOpenFacet != null && (ev.target as? Element)?.closest(".mu-fc") == null && document.getElementById("mu-lib") != null) { muOpenFacet = null; muRenderFacets() }
        }
    }
    // FR-278-14 — the load-more line and the sentinel live *outside* `#mu-lib` on purpose: `muRender` reassigns that
    // element's `innerHTML`, and an IntersectionObserver whose target has been thrown away observes nothing. One
    // observer per visit to the page, as the films Library does.
    document.getElementById("mu-loadmore")?.addEventListener("click") { ev ->
        if ((ev.target as? Element)?.closest("[data-mumore]") != null) scope.launch { muFetch(scope, reset = false) }
    }
    observeSections("mu-sentinel", "700px 0px 700px 0px") { _ -> scope.launch { muFetch(scope, reset = false) } }
    muScope = scope
    muLoad(scope)
}

/** Everything that changes what the list is — a filter, a facet, a search, a sort, an artist, a version, a view,
 *  *Show every copy* — comes through here, and starts the sequence again at page 1 (FR-278-14). */
private fun muLoad(scope: CoroutineScope) {
    muLoadJob?.cancel()
    muLoadJob = scope.launch { muFetch(scope, reset = true) }
}

/**
 * FR-278-14 — the films Library's `loadMore`, transcribed. With [reset] it clears the sequence and renders the page
 * from scratch for the current filters; otherwise it fetches the next Songs page and **appends** it. After each page
 * it keeps fetching while the sentinel is still near the viewport, so a partial last screen is never mistaken for
 * the end. Re-entry is guarded by [muFetching], with [muPendingReset] so a filter change arriving mid-fetch is
 * honoured rather than lost.
 */
private suspend fun muFetch(scope: CoroutineScope, reset: Boolean) {
    if (muFetching) {
        if (reset) muPendingReset = true
        return
    }
    // Nothing loaded yet, or nothing left to load: an early sentinel fire has no sequence to continue.
    if (!reset && (muPage == 0 || muEnd || muView != "songs")) return
    muFetching = true
    try {
        if (reset) {
            historyReplaceState(muUrl())
            muPage = 0; muLoaded = 0; muEnd = false; muLoadedIds.clear()
            // FR-292-16 — a fresh sequence is a different list, so neither the ticks nor *select all shown* survive
            // it: a selection that outlived its filter would write a set nobody is looking at.
            muSongSel.clear(); muSongAll = false
            muSetLoadMore("")
        }
        var first = reset
        while (true) {
            if (!first) muSetLoadMore("""<span class="muted tiny">Loading more…</span>""")
            val dto = MusicApi.browse(muView, muQuery, muFacets.mapValues { it.value.toSet() }, muSort, muFilter, muHidden.mapValues { it.value.toSet() }, muArtist,
                everyCopy = muView == "songs" && edEveryCopy,   // Phase 305 (FR-305-13) — *Show every copy*
                // The window is asked for on every view: the server applies it to the Songs rows only, which is what
                // lets a Dashboard key — whose view the server chooses — arrive paged on its very first request.
                page = muPage + 1, pageSize = MU_SLICE)
            if (dto == null) {
                if (first) (document.getElementById("mu-lib") as? HTMLElement)?.innerHTML = """<div class="note red">Couldn't read the music library from the server.</div>"""
                else muSetLoadMore("""<span class="muted tiny">Couldn't load more — scroll to retry.</span>""")
                return
            }
            if (first) {
                // Phase 293 (FR-293-3/4) — the key chooses the view; an unknown key comes back without one and is dropped.
                muFilter = dto.filter
                muView = dto.view
                historyReplaceState(muUrl())
                vrRemember(dto.versionTypes)   // Phase 292 — chip names and colours
                // `muDto` keeps the first page's answer: its counts are whole-set, and a full re-render then falls
                // back to page 1 rather than to whichever page happened to land last.
                muDto = dto
                muRender(scope)
                if (dto.match.running) muPollMatch(scope)
            } else if (muView == "songs" && dto.songs.isNotEmpty()) muAppendSongs(dto.songs)

            muPage++
            muLoaded += dto.songs.size
            muLoadedIds += dto.songs.map { it.id }
            muEnd = muView != "songs" || dto.songs.isEmpty() || muLoaded >= dto.total
            first = false
            // Stop once the viewport is satisfied; the sentinel observer resumes the sequence on scroll.
            if (muEnd || !elemNearViewportBottom("mu-sentinel", 700)) break
        }
        muSetLoadMore(muLoadMoreLine())
    } finally {
        muFetching = false
        if (muPendingReset) {
            muPendingReset = false
            scope.launch { muFetch(scope, reset = true) }
        }
    }
}

/** The line under the table: how far the sequence has got, and a way on without scrolling. */
private fun muLoadMoreLine(): String {
    val total = muDto?.total ?: muLoaded
    return when {
        muView != "songs" || (muEnd && muPage <= 1) -> ""
        muEnd -> """<span class="muted tiny">All $muLoaded rows loaded</span>"""
        else -> """<span class="btn sm ghost" data-mumore>Load more</span><span class="muted tiny" style="margin-left:8px">$muLoaded of $total</span>"""
    }
}

private fun muSetLoadMore(html: String) {
    (document.getElementById("mu-loadmore") as? HTMLElement)?.innerHTML = html
}

/**
 * FR-278-14/15 — the next page's rows go into the `<tbody>` that is already there. Parsed inside a throwaway
 * `<table>`, because a `<tr>` is only parsed as a row in table context.
 */
private fun muAppendSongs(rows: List<MusicSongRow>) {
    val body = document.getElementById("mu-rows") ?: return
    val tmp = document.createElement("div")
    tmp.innerHTML = """<table><tbody>${rows.joinToString("") { songRowHtml(it) }}</tbody></table>"""
    val src = tmp.querySelector("tbody") ?: return
    while (true) {
        val row = src.firstElementChild ?: break
        body.appendChild(row)
    }
}

/**
 * The whole page. Called when the list itself changes — on arrival and at the head of every fresh sequence — and
 * never for a click on the chrome: FR-278-15's four pieces (`#mu-stat`, `#mu-fbar`, `#mu-abar`, `#mu-sbar`) are
 * re-rendered on their own by the `muRender*` functions below, so the Songs pages appended into `#mu-rows` stay
 * where they are. Rebuilding the body for a dropdown is what made the Songs tab unusable at 10 723 rows (DEB-1107).
 */
private fun muRender(scope: CoroutineScope) {
    val root = document.getElementById("mu-lib") as? HTMLElement ?: return
    val d = muDto ?: return
    val h = d.health
    val live = d.mapped && h != null && (h.albums > 0 || h.tracks > 0)
    root.innerHTML = buildString {
        append("""<div class="mu-top"><span class="seg" id="mu-view">""")
        for ((k, l) in listOf("artists" to "Artists", "albums" to "Albums", "songs" to "Songs"))   // Phase 287 (FR-287-1)
            append("""<span data-mview="$k" class="${if (muView == k) "on" else ""}">$l</span>""")
        append("</span>")
        // `display:contents` so the status line's spans and buttons stay direct flex children of `.mu-top` — the
        // slot is there to be re-filled by `muRenderTop`, not to lay anything out.
        append("""<span id="mu-stat" style="display:contents">${if (live) statusLine(d) else ""}</span>""")
        append("</div>")
        append("""<p class="page-sub" style="margin-top:0">Albums, artists and songs from Jellyfin’s <b>${d.libraries.joinToString(" · ") { it.name.esc() }.ifEmpty { "music" }}</b> library, matched against <b>MusicBrainz</b> — what this page maintains is written to <span class="mono">album.nfo</span> / <span class="mono">artist.nfo</span>${if (d.writeTags) ", and into the music files’ tags (<i>Write tags into music files</i> is on)" else ""}. Not the <b>Music videos</b> library: those are films and stay under their own kind.</p>""")
        when {
            !d.mapped -> append("""<div class="mu-empty"><h3>No music library mapped</h3><p>Jellyfin’s music library appears under <b>Settings → Libraries</b> after <i>Refresh from Jellyfin</i>. Map it, and the next scan reads its albums, artists and songs.</p><a class="btn sm" href="#/settings?tab=libraries">Libraries ›</a></div>""")
            !live && !d.scanned -> {
                val paths = d.libraries.joinToString("<br>") { "<span class=\"mono\">${it.jellyfinPath.esc()} → ${it.localPath.esc()}</span>" }
                append("""<div class="mu-empty"><h3>Mapped — not scanned yet</h3><p>$paths</p><p>The first scan reads what Jellyfin has filed, then matches each album against MusicBrainz at one request a second.</p><span class="btn sm primary" data-act="scan">Scan now</span> <a class="btn sm ghost" href="#/settings?tab=libraries">Library card ›</a></div>""")
            }
            !live -> append("""<div class="mu-empty"><h3>Nothing filed as music yet</h3><p>The library is mapped but Jellyfin has no albums in it. Put folders under it as <span class="mono">Artist/Album/track</span> and scan.</p><a class="btn sm" href="#/settings?tab=libraries">Library card ›</a></div>""")
            else -> {
                append("""<div class="mu-facets" id="mu-fbar">${facetBar(d)}</div>""")
                append("""<div class="mu-active" id="mu-abar">${activeBar(d)}</div>""")
                append("""<div class="mu-selbar${if (selBarOn()) " on" else ""}" id="mu-sbar">${selBar()}</div>""")
                append("""<div id="mu-body">${body(d)}</div>""")
            }
        }
    }
    muWireMenus(root)
}

/** FR-278-15 — the status line alone, for a `muPollMatch` tick that has no *Matching…* button to write into. */
private fun muRenderTop() {
    val d = muDto ?: return
    val h = d.health
    if (!(d.mapped && h != null && (h.albums > 0 || h.tracks > 0))) return
    (document.getElementById("mu-stat") as? HTMLElement)?.innerHTML = statusLine(d)
}

/** FR-278-15 — the facet bar alone: opening, closing and ticking a facet never reaches the rows. */
private fun muRenderFacets() {
    val d = muDto ?: return
    (document.getElementById("mu-fbar") as? HTMLElement)?.innerHTML = facetBar(d)
}

/** FR-278-15 — the selection bar alone, with the row ticks mutated in place by [muSyncSongTicks]. */
private fun muRenderSel() {
    val el = document.getElementById("mu-sbar") as? HTMLElement ?: return
    el.innerHTML = selBar()
    el.classList.toggle("on", selBarOn())
    (document.getElementById("mu-scroll") as? HTMLElement)?.classList?.toggle("vr-selecting", muSongSel.isNotEmpty() || muSongAll)
}

/** Every loaded row's tick read back off the selection — *select all shown* and *clear* mutate, they do not rebuild. */
private fun muSyncSongTicks() {
    val rows = document.querySelectorAll("#mu-rows tr[data-sid]")
    for (i in 0 until rows.length) {
        val tr = rows.item(i) as? HTMLElement ?: continue
        muMarkSongRow(tr, muSongTicked(tr.getAttribute("data-sid") ?: continue))
    }
}

/** FR-292-16 — in *select all shown* every row is in the set, so every row reads ticked, pages yet to come too. */
private fun muSongTicked(id: String): Boolean = muSongAll || id in muSongSel

private fun muMarkSongRow(tr: HTMLElement, on: Boolean) {
    tr.classList.toggle("vr-on", on)
    (tr.querySelector(".vr-sel") as? HTMLElement)?.let { it.classList.toggle("on", on); it.textContent = if (on) "✓" else "" }
}

private fun statusLine(d: MusicBrowseDto): String {
    val h = d.health ?: return ""
    val need = h.needsYou + h.unmatched
    return buildString {
        append("""<span class="mu-status"><b>${h.albums}</b> albums · <b>${h.matched}</b> matched""")
        if (need > 0) append(""" · <b class="w">$need</b> <span class="w">${if (h.needsYou > 0 && h.unmatched == 0) "need you" else "need a match"}</span>""")
        append(" · ${h.tracks} songs · ${h.artists} artists")
        if (h.coversMissing > 0) append(""" · <span class="w">${h.coversMissing} without a cover</span>""")
        append("</span>")
        if (d.match.running) append("""<span class="btn sm primary is-off" id="mu-matching">Matching… ${d.match.done} of ${d.match.total}</span>""")
        else if (need > 0 && d.musicbrainzEnabled) append("""<span class="btn sm primary" data-act="matchall" title="MusicBrainz answers one request a second — about ${(need * 4 / 60).coerceAtLeast(1)} min">Match now</span>""")
        if (h.reencodes > 0) append("""<span class="btn sm" data-act="convert" title="${h.reencodes} songs a phone can only play by re-encoding">Convert… (${h.reencodes})</span>""")
    }
}

/** The contents of `#mu-fbar`; the `.mu-facets` element itself is the slot `muRender` writes, so it stays put. */
private fun facetBar(d: MusicBrowseDto): String = buildString {
    append("""<span class="muted tiny">filter:</span>""")
    for (f in d.facets) {
        val n = f.values.count { it.on || it.off }
        if (f.key == "version") { append(versionFacet(f, n)); continue }
        append("""<span class="mu-fc${if (muOpenFacet == f.key) " open" else ""}"><span class="mu-fbtn${if (n > 0) " on" else ""}" data-fopen="${f.key}">${f.label.esc()}${if (n > 0) """ <span class="c">$n</span>""" else ""} ▾</span><div class="mu-fpop">""")
        if (f.values.isEmpty()) append("""<div class="tiny muted" style="padding:6px 9px">None in the library</div>""")
        for (v in f.values) {
            append("""<div class="mu-fv${if (v.on) " on" else ""}${if (v.count == 0) " zero" else ""}" data-fk="${f.key}" data-fv="${v.value.esc()}"><span class="bx">${if (v.on) "✓" else ""}</span><span>${v.label.esc()}${v.note?.let { """<span class="nt">${it.esc()}</span>""" } ?: ""}</span><span class="ct">${v.count}</span></div>""")
        }
        append("</div></span>")
    }
    append("""<span class="spacer" style="flex:1"></span>""")
    if (muView == "albums") {
        append("""<select id="mu-sort" class="input" style="width:auto;font-size:.83rem;">""")
        for ((v, l) in listOf("" to "recently added ▾", "title" to "title A–Z", "year" to "year, newest first", "artist" to "artist A–Z"))
            append("""<option value="$v"${if ((muSort ?: "") == v) " selected" else ""}>$l</option>""")
        append("</select>")
    } else append("""<span class="tiny muted">sorted by ${if (muView == "songs") "album, then position" else "name"}</span>""")
    // Phase 305 (FR-305-13) — one row per song; the switch lists every file (a Dashboard key always does).
    if (muView == "songs") {
        val every = d.everyCopy
        val words = if (every) muPlural(d.total, "file") else "${muPlural(d.total, "song")} · ${muPlural(d.folded, "copy", "copies")} folded"
        append("""<span class="ed-sw${if (every) " on" else ""}" data-edevery title="${if (d.filter != null) "A Dashboard list always shows every file" else "Lists show every song once; the admin manages files"}"><i></i>Show every copy <span class="c">· $words</span></span>""")
    }
}

/** Phase 292 (FR-292-11) — the Version facet: each type and *No version*, with its count, *Only* and *Hide*. */
private fun versionFacet(f: dev.jellystructure.model.MusicFacet, n: Int): String = buildString {
    append("""<span class="mu-fc${if (muOpenFacet == f.key) " open" else ""}"><span class="mu-fbtn${if (n > 0) " on" else ""}" data-fopen="${f.key}">${f.label.esc()}${if (n > 0) """ <span class="c">$n</span>""" else ""} ▾</span><div class="mu-fpop vr-fpop">""")
    append("""<div class="tiny muted" style="padding:4px 8px 8px">A version belongs to a song. <b>Only</b> keeps the songs with it; <b>Hide</b> takes them out. A Session counts as Live.</div>""")
    for (v in f.values) {
        val name = if (v.value == "none") """<span class="tiny" style="font-weight:600">No version</span><span class="tiny muted">the originals</span>""" else vrChips(listOf(v.value)).ifEmpty { v.label.esc() }
        append("""<div class="vr-fv${if (v.count == 0) " zero" else ""}"><span class="nm">$name</span><span class="ct">${v.count}</span><span class="vr-ie"><span class="i${if (v.on) " on" else ""}" data-vfi="${v.value.esc()}">Only</span><span class="x${if (v.off) " on" else ""}" data-vfx="${v.value.esc()}">Hide</span></span></div>""")
    }
    append("</div></span>")
}

private fun activeBar(d: MusicBrowseDto): String {
    // Phase 292 — the Songs filter in words (*Songs by … · without Live, Remix*), built by the server.
    val words = d.sentence?.let { """<span class="vr-words">${it.esc()} <span class="rm" data-vwrm title="Clear the artist and the Version filter">✕</span></span>""" }
    val act = d.facets.filter { f -> f.key != "version" && f.values.any { it.on } }
    // Phase 293 (FR-293-4) — the Dashboard row's label, removable like any other filter.
    val issue = d.filterLabel?.let { """<span class="fxchip">Issue: ${it.esc()} <span class="rm" data-issue-rm style="cursor:pointer">✕</span></span>""" }
    if (act.isEmpty() && issue == null && words == null) return ""
    return (listOfNotNull(words, issue) + act.map { f ->
        """<span class="fxchip">${f.label.esc()} is ${f.values.filter { it.on }.joinToString(" or ") { it.label.esc() }} <span class="rm" data-frm="${f.key}" style="cursor:pointer">✕</span></span>"""
    }).joinToString(""" <span class="tiny muted">and</span> """) + """<span class="livecount" style="margin-left:6px;"><span class="n">${d.total}</span><span class="tiny muted"> ${muView} match</span></span><span class="tiny" style="margin-left:6px;cursor:pointer;color:var(--ink-soft);" data-fclear>clear all</span>"""
}

/** Whether `#mu-sbar` carries `on` — the class the sticky bar is shown by. */
private fun selBarOn(): Boolean = if (muView == "songs") muSongSel.isNotEmpty() || muSongAll else muView == "albums" && muSelected.isNotEmpty()

/** The contents of `#mu-sbar`. The element itself is `muRender`'s, so the bar keeps its sticky containing block. */
private fun selBar(): String {
    // Phase 292 (FR-292-12/16) — a song selection, and *Set version…* on it. Two shapes, and the bar says which:
    // the ticks (a list of ids), or *every song matching this filter* — whose number is the server's whole-set
    // `total`, which FR-278-14 computes over the filtered set and not over the pages loaded.
    if (muView == "songs" && muSongAll) {
        val total = muDto?.total ?: muLoaded
        return """<b>$total</b> song${if (total == 1) "" else "s"} selected<span class="tiny muted" style="margin-left:6px">every song matching this filter</span>${muVersionBulkBtnHtml(vrBulkPressBusy())}<span class="spacer" style="flex:1"></span><span class="tiny" style="cursor:pointer;color:var(--ink-soft);" data-vnone>✕ clear</span>"""
    }
    if (muView == "songs" && muSongSel.isNotEmpty()) {
        return """<b>${muSongSel.size}</b> song${if (muSongSel.size == 1) "" else "s"} selected${muVersionBulkBtnHtml(vrBulkPressBusy())}<span class="spacer" style="flex:1"></span><span class="tiny" style="cursor:pointer;color:var(--ink-soft);" data-vall>select all shown</span><span class="tiny" style="cursor:pointer;color:var(--ink-soft);" data-vnone>✕ clear</span>"""
    }
    if (muView != "albums" || muSelected.isEmpty()) return ""
    return buildString {
        append("""<b>${muSelected.size}</b> selected""")
        for ((k, l) in listOf("match" to "Match now", "covers" to "Fetch covers", "nfo" to "Write NFOs", "tags" to "Write tags…", "lock" to "Lock match", "clear" to "Clear match"))   // Phase 284 — Write tags…
            append("""<span class="btn sm${if (k == "match") " primary" else if (k == "clear") " ghost" else ""}" data-bulk="$k">$l</span>""")
        append("""<span class="spacer" style="flex:1"></span><span class="tiny" style="cursor:pointer;color:var(--ink-soft);" data-bulk="all">select all shown</span><span class="tiny" style="cursor:pointer;color:var(--ink-soft);" data-bulk="none">✕ clear</span>""")
    }
}

private fun body(d: MusicBrowseDto): String {
    val empty = when (muView) { "artists" -> d.artists.isEmpty(); "songs" -> d.songs.isEmpty(); else -> d.albums.isEmpty() }
    if (empty) return if (muQuery.isNotBlank()) """<div class="muted" style="padding:40px 4px;">Nothing in the music library matches “${muQuery.esc()}”.</div>"""
        else """<div class="muted" style="padding:40px 4px;">No $muView match these filters.</div>"""
    return when (muView) {
        "artists" -> buildString {
            append("""<div class="mu-agrid">""")
            for (r in d.artists) {
                val pic = if (r.picture) muImageStyle("/api/music/image/artist/${r.id}/thumb?v=${r.v}") else muWordmarkStyle(r.name)
                append("""<a class="mu-cell" href="#/artist/${r.id}" data-id="${r.id}"><div class="mu-circ" style="$pic">""")
                if (!r.picture) append("""${muInitials(r.name).esc()}${if (r.folder) """<i class="mu-nocov" title="No picture"></i>""" else ""}""")
                append(muMatchChip(r.match))
                append("""</div><div class="ttl">${r.name.esc()}</div><div class="yr">${if (r.albums > 0) muPlural(r.albums, "album") + " · " else "credited · "}${muPlural(r.songs, "song")}</div></a>""")
            }
            append("</div>")
        }
        // Phase 278 (FR-278-14) — the table shell and the first page's rows; every later page is appended into
        // `#mu-rows` by `muAppendSongs`, so the body is built once per sequence and then only mutated.
        "songs" -> buildString {
            append("""<div class="mu-scroll${if (muSongSel.isNotEmpty() || muSongAll) " vr-selecting" else ""}" id="mu-scroll"><table class="mu-tbl"><thead><tr><th>#</th><th>Title</th><th>Artist</th><th>Album</th><th>Length</th><th>Format</th><th>Lyrics</th><th>Match</th></tr></thead><tbody id="mu-rows">""")
            for (t in d.songs) append(songRowHtml(t))
            append("</tbody></table></div>")
        }
        else -> buildString {
            append("""<div class="mu-grid${if (muSelected.isNotEmpty()) " selecting" else ""}">""")
            val repeated = muRepeatedTitles(d.albums)
            for (a in d.albums) {
                val url = if (a.cover) "/api/music/image/album/${a.id}?v=${a.v}" else null
                append("""<a class="mu-cell${if (a.id in muSelected) " on" else ""}" href="#/album/${a.id}" data-id="${a.id}"><span class="mu-sel" data-sel="${a.id}">✓</span>""")
                append(muCoverHtml(a.title, url, chip = muAlbumChip(a)))
                append("""<div class="ttl" title="${a.title.esc()}">${a.title.esc()}</div><div class="sub">${a.artist.esc()}</div>${muFolderLine(a, repeated)}<div class="yr">${a.year?.let { "$it · " } ?: ""}${muPlural(a.songs, "song")}${if (a.extras > 0) " + " + muPlural(a.extras, "extra") else ""}</div></a>""")
            }
            append("</div>")
        }
    }
}

/** One Songs row. `data-sid` is what [muSyncSongTicks] reads a tick back onto without rebuilding the table. */
private fun songRowHtml(t: MusicSongRow): String = buildString {
    val artists = t.artists.joinToString(" &amp; ") { """<a class="dim" href="#/artist/${it.artistId}">${it.name.esc()}</a>""" }
    // Phase 292 (FR-292-10/12) — chips after the title; the number turns into a checkbox on hover.
    val on = muSongTicked(t.id)
    // Phase 305 (FR-305-13) — *Bonus* after the version chips; folded: *also on N releases*; every copy: the
    // other copies sit indented under their song with the reason.
    val cls = listOfNotNull(if (on) "vr-on" else null, if (t.copyOf != null) "ed-cp" else null).joinToString(" ")
    val also = if (t.alsoOn > 0) """<div><span class="ed-also" data-edcopies="${t.id}">also on ${muPlural(t.alsoOn, "release")}</span></div>""" else ""
    val why = t.reason?.let { r -> """<div class="ed-cpw">↳ ${r.esc()} · <span class="ed-also" data-edcopies="${t.id}">why</span></div>""" } ?: ""
    append("""<tr data-sid="${t.id}"${if (cls.isNotEmpty()) " class=\"$cls\"" else ""}><td class="n"><span class="vr-num">${t.position ?: ""}</span><span class="vr-sel${if (on) " on" else ""}" data-vsel="${t.id}">${if (on) "✓" else ""}</span></td><td>${t.albumId?.let { """<a href="#/album/$it">${t.title.esc()}</a>""" } ?: t.title.esc()}${vrBadges(t.id, t.versions)}${edBonus(t.bonus)}${if (t.noWords) """<span class="tiny muted" style="margin-left:8px">no words</span>""" else ""}$also$why</td>""")
    append("""<td class="dim">$artists</td><td class="dim">${t.albumId?.let { """<a class="dim" href="#/album/$it">${t.album.orEmpty().esc()}</a>""" } ?: ""}</td>""")
    append("""<td class="num">${muLen(t.lengthMs)}</td><td><span class="mu-fmt${if (t.reencodes) " w" else ""}">${t.format.esc()}${if (t.reencodes) """<span class="re">re-encodes on a phone</span>""" else ""}</span></td>""")
    append("<td>${lyricsCell(t.lyrics)}</td><td>${recordingCell(t.recording, t.albumMatched)}</td></tr>")
}

internal fun lyricsCell(state: String?): String = when (state) {
    "synced" -> """<span class="mu-ly" title="Synced lyrics">$MU_LYR synced</span>"""
    "plain" -> """<span class="mu-ly" title="Plain lyrics">$MU_LYR plain</span>"""
    "jellyfin" -> """<span class="mu-ly" title="Jellyfin found lyrics in the file or beside it">$MU_LYR in Jellyfin</span>"""
    else -> """<span class="mu-ly no">—</span>"""
}

private fun recordingCell(state: String?, albumMatched: Boolean): String = when {
    !albumMatched -> """<span class="mu-mt w">unmatched</span>"""
    state == "agrees" || state == "manual" -> """<span class="mu-mt ok">✓</span>"""
    state == "disagrees" -> """<span class="mu-mt w">different release</span>"""
    else -> """<span class="mu-mt d">—</span>"""
}

private fun muClick(t: Element, ev: org.w3c.dom.events.Event, scope: CoroutineScope) {
    t.closest("[data-sel]")?.let { s ->
        ev.preventDefault(); ev.stopPropagation()
        val id = s.getAttribute("data-sel") ?: return
        if (!muSelected.remove(id)) muSelected += id
        muRender(scope); return
    }
    if (muSelected.isNotEmpty()) t.closest(".mu-grid .mu-cell")?.let { cell ->
        ev.preventDefault()
        val id = cell.getAttribute("data-id") ?: return
        if (!muSelected.remove(id)) muSelected += id
        muRender(scope); return
    }
    // Phase 305 (FR-305-13) — the copies panel and *Show every copy*.
    t.closest("[data-edcopies]")?.let { c -> ev.preventDefault(); ev.stopPropagation(); edOpenCopies(scope, c.getAttribute("data-edcopies")!!) { muLoad(scope) }; return }
    if (t.closest("[data-edevery]") != null) { if (muDto?.filter == null) { edEveryCopy = !edEveryCopy; muLoad(scope) }; return }
    // Phase 292 — the chips open the version panel; a song's checkbox builds a selection for *Set version…*.
    t.closest("[data-ver]")?.let { v -> ev.preventDefault(); ev.stopPropagation(); vrOpenPanel(scope, v.getAttribute("data-ver")!!) { muLoad(scope) }; return }
    // FR-278-15 — ticking a song toggles its row's class and redraws the selection bar. Nothing else: a rebuild
    // here would throw away every appended page.
    t.closest("[data-vsel]")?.let { s ->
        ev.preventDefault()
        val id = s.getAttribute("data-vsel")!!
        // FR-292-16 — a tick leaves *select all shown*: the set becomes the ids of the rows loaded, which is what
        // the table has been showing ticked, with this one changed. The bar's number drops in plain sight.
        if (muSongAll) { muSongAll = false; muSongSel += muLoadedIds }
        val on = if (muSongSel.remove(id)) false else { muSongSel += id; true }
        (s.closest("tr") as? HTMLElement)?.let { muMarkSongRow(it, on) }
        muRenderSel(); return
    }
    // FR-292-16 — *select all shown* is the filter, not the rows loaded: the server resolves it on the write.
    if (t.closest("[data-vall]") != null) { muSongAll = true; muSyncSongTicks(); muRenderSel(); return }
    if (t.closest("[data-vnone]") != null) { muSongSel.clear(); muSongAll = false; muSyncSongTicks(); muRenderSel(); return }
    if (t.closest("[data-vbulk]") != null) {
        val filter = if (muSongAll) muSongFilter() else null
        // FR-292-17 — the refusal and the *Reading the versions…* label are both `vrOpenBulk`'s flag; the bar is
        // ours to redraw, and `muRenderSel` alone, because a rebuild would throw away every appended page.
        vrOpenBulk(scope, if (muSongAll) emptyList() else muSongSel.toList(), filter = filter, repaint = { muRenderSel() }, done = { muLoad(scope) }); return
    }
    t.closest("[data-vfi]")?.let { b -> ev.stopPropagation(); muToggleVersion(b.getAttribute("data-vfi")!!, only = true); muSongSel.clear(); muLoad(scope); return }
    t.closest("[data-vfx]")?.let { b -> ev.stopPropagation(); muToggleVersion(b.getAttribute("data-vfx")!!, only = false); muSongSel.clear(); muLoad(scope); return }
    if (t.closest("[data-vwrm]") != null) { muFacets.remove("version"); muHidden.remove("version"); muArtist = null; muLoad(scope); return }
    t.closest("[data-mview]")?.let { v ->
        // Phase 293 — a triage key belongs to one view; choosing another view leaves it.
        muView = v.getAttribute("data-mview") ?: "artists"; muSelected.clear(); muOpenFacet = null; muFilter = null; muSongSel.clear()
        muLoad(scope); return
    }
    t.closest("[data-fopen]")?.let { f ->
        ev.stopPropagation()
        val k = f.getAttribute("data-fopen")
        muOpenFacet = if (muOpenFacet == k) null else k
        muRenderFacets(); return   // FR-278-15 — a dropdown is chrome; the rows stay where they are
    }
    t.closest("[data-fk]")?.let { f ->
        ev.stopPropagation()
        val k = f.getAttribute("data-fk") ?: return
        val v = f.getAttribute("data-fv") ?: return
        val set = muFacets.getOrPut(k) { LinkedHashSet() }
        if (!set.remove(v)) set += v
        if (set.isEmpty()) muFacets.remove(k)
        muSelected.clear(); muLoad(scope); return
    }
    t.closest("[data-frm]")?.let { f -> muFacets.remove(f.getAttribute("data-frm")); muLoad(scope); return }
    if (t.closest("[data-fclear]") != null) { muFacets.clear(); muHidden.clear(); muArtist = null; muFilter = null; muLoad(scope); return }
    if (t.closest("[data-issue-rm]") != null) { muFilter = null; muSelected.clear(); muLoad(scope); return }
    t.closest("[data-bulk]")?.let { b ->
        when (val k = b.getAttribute("data-bulk")) {
            "all" -> { muDto?.albums?.forEach { muSelected += it.id }; muRender(scope) }
            "none" -> { muSelected.clear(); muRender(scope) }
            null -> Unit
            // Phase 284 (FR-284-10) — the confirm line says the skips as counts before anything runs.
            "tags" -> {
                val ids = muSelected.toList()
                scope.launch {
                    val pv = MusicApi.tagsPreview(ids) ?: return@launch muToast("The server didn’t answer")
                    val off = when { !pv.writeEnabled -> "Tag writing is off in Settings → Music providers"; !pv.taggerAvailable -> "This server has no tagger"; pv.songs == 0 -> "Nothing to write — none of these albums is matched yet"; else -> null }
                    muModal("""<h3>Write tags to ${pv.songs} song${if (pv.songs == 1) "" else "s"}?</h3>
                        <p class="tiny muted" style="line-height:1.6">${pv.selected} selected · ${pv.unmatched} unmatched (skipped — nothing to write yet) · ${pv.seeding} seeding · ${pv.wma} WMA (tagged; Jellyfin won’t read their ids)</p>
                        ${off?.let { """<div class="note warn"><span class="tiny">${it.esc()}</span></div>""" } ?: ""}
                        <div class="row" style="justify-content:flex-end;gap:8px;margin-top:12px;"><span class="btn ghost" data-m="no">Cancel</span>${if (off == null) """<span class="btn primary" data-m="go">Write ${pv.songs}</span>""" else ""}</div>""") {
                        scope.launch { muToast(MusicApi.bulk("tags", ids) ?: "That didn't work — the server said no"); muSelected.clear(); muLoad(scope) }
                    }
                }
            }
            else -> {
                val ids = muSelected.toList()
                scope.launch {
                    val sentence = MusicApi.bulk(k, ids)
                    muToast(sentence ?: "That didn't work — the server said no")
                    muSelected.clear()
                    if (k == "match") muPollMatch(scope) else muLoad(scope)
                }
            }
        }
        return
    }
    t.closest("[data-act]")?.let { a ->
        when (a.getAttribute("data-act")) {
            "matchall" -> scope.launch {
                if (MusicApi.matchNow()) { muToast("Matching against MusicBrainz — one request a second"); muPollMatch(scope) }
                else muToast("A matching pass is already running")
            }
            "scan" -> openPipelineRunDialog(scope, "Scan library", full = false) { skip ->
                if (dev.jellystructure.api.MediaApi.startScan(false, skip)) muToast("Scan started — the music library is read after the films") else muToast("The scan didn't start")
            }
            "convert" -> muOpenConvert(scope, MusicConvertRequest()) { muLoad(scope) }
        }
    }
}

/** Phase 292 (FR-292-11) — a value is in *Only* or in *Hide*, never both; a second press takes it out. */
private fun muToggleVersion(value: String, only: Boolean) {
    val a = (if (only) muFacets else muHidden).getOrPut("version") { LinkedHashSet() }
    val b = (if (only) muHidden else muFacets)["version"]
    if (!a.remove(value)) { a += value; b?.remove(value) }
    if (a.isEmpty()) (if (only) muFacets else muHidden).remove("version")
    if (b?.isEmpty() == true) (if (only) muHidden else muFacets).remove("version")
}

/** *Matching… n of 30* on the button, then the page again when the pass ends. */
private fun muPollMatch(scope: CoroutineScope) {
    muPollJob?.cancel()
    muPollJob = scope.launch {
        while (true) {
            delay(1500)
            if (document.getElementById("mu-lib") == null) return@launch
            val st = MusicApi.status() ?: continue
            val btn = document.getElementById("mu-matching") as? HTMLElement
            if (btn != null) btn.textContent = "Matching… ${st.match.done} of ${st.match.total}"
            // FR-278-15 — the button has gone, so the status line is redrawn on its own; a tick every 1.5 s must
            // not rebuild ten thousand rows.
            else { muDto = muDto?.copy(match = st.match); muRenderTop() }
            if (!st.match.running) {
                st.match.lastSummary?.let { muToast(it) }
                muLoad(scope); return@launch
            }
        }
    }
}

/**
 * FR-278-7 — *Convert…*: asks the server what it would do, then says it plainly: the files are already lossy, so a
 * little more is lost; the originals move to a holding folder; seeding files are skipped. *Keep as they are* is the
 * default way out.
 */
internal fun muOpenConvert(scope: CoroutineScope, req: MusicConvertRequest, after: () -> Unit) {
    scope.launch {
        val plan = MusicApi.convertPlan(req) ?: return@launch muToast("Couldn't work out what to convert")
        if (plan.songs == 0) return@launch muToast(if (plan.seeding > 0) "All ${plan.seeding} are seeding in qBittorrent — nothing to convert" else "Nothing here needs converting")
        val songs = muPlural(plan.songs, "song")
        muModal("""<h3>Convert $songs so a phone can play them directly?</h3>
            <p>These are <b>${plan.formats.joinToString(" · ").esc()}</b> — a phone can only play them by asking Jellyfin to re-encode on every play, which is never gapless. A one-time repair job converts them to <b>AAC 192 kbps</b>.</p>
            <p><b>They are already lossy, so a little more is lost.</b> You are unlikely to hear it, and it cannot be undone from the new files. The originals are moved to a holding folder (<span class="mono">.js-quarantine</span> beside the library), not deleted.</p>
            ${if (plan.seeding > 0) """<p class="tiny">${muPlural(plan.seeding, "file")} seeding in qBittorrent ${if (plan.seeding == 1) "is" else "are"} skipped (cross-seed safety).</p>""" else """<p class="tiny">Files seeding in qBittorrent are skipped (cross-seed safety).</p>"""}
            <div class="row" style="justify-content:flex-end;gap:8px;margin-top:12px;"><span class="btn ghost" data-m="no">Keep as they are</span><span class="btn primary" data-m="go">Convert ${plan.songs}</span></div>""") {
            scope.launch {
                val r = MusicApi.convert(req)
                muToast(if (r?.job != null) "Convert job queued · ${muPlural(r.songs, "file")} · Activity → Jobs & workers" else "The job didn't start")
                after()
            }
        }
    }
}
