package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.MusicApi
import dev.jellystructure.historyReplaceState
import dev.jellystructure.model.MusicBrowseDto
import dev.jellystructure.model.MusicConvertRequest
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
private val muFacets = LinkedHashMap<String, MutableSet<String>>()
private val muSelected = LinkedHashSet<String>()
private var muOpenFacet: String? = null
private var muDto: MusicBrowseDto? = null
private var muLoadJob: Job? = null
private var muPollJob: Job? = null
private var muDocWired = false
private var muScope: CoroutineScope? = null

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
    muFacets.clear(); muSelected.clear(); muOpenFacet = null; muDto = null; muHidden.clear(); muSongSel.clear()
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
            if (muOpenFacet != null && (ev.target as? Element)?.closest(".mu-fc") == null && document.getElementById("mu-lib") != null) { muOpenFacet = null; muRender(muScope ?: return@addEventListener) }
        }
    }
    muScope = scope
    muLoad(scope)
}

private fun muLoad(scope: CoroutineScope) {
    muLoadJob?.cancel()
    muLoadJob = scope.launch {
        historyReplaceState(muUrl())
        val dto = MusicApi.browse(muView, muQuery, muFacets.mapValues { it.value.toSet() }, muSort, muFilter, muHidden.mapValues { it.value.toSet() }, muArtist,
            everyCopy = muView == "songs" && edEveryCopy)   // Phase 305 (FR-305-13) — *Show every copy*
        if (dto == null) {
            (document.getElementById("mu-lib") as? HTMLElement)?.innerHTML = """<div class="note red">Couldn't read the music library from the server.</div>"""
            return@launch
        }
        // Phase 293 (FR-293-3/4) — the key chooses the view; an unknown key comes back without one and is dropped.
        muFilter = dto.filter
        muView = dto.view
        historyReplaceState(muUrl())
        vrRemember(dto.versionTypes)   // Phase 292 — chip names and colours
        muDto = dto
        muRender(scope)
        if (dto.match.running) muPollMatch(scope)
    }
}

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
        if (live) append(statusLine(d))
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
                append(facetBar(d))
                append("""<div class="mu-active">${activeBar(d)}</div>""")
                append(selBar())
                append(body(d))
            }
        }
    }
    muWireMenus(root)
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

private fun facetBar(d: MusicBrowseDto): String = buildString {
    append("""<div class="mu-facets"><span class="muted tiny">filter:</span>""")
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
    append("</div>")
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

private fun selBar(): String {
    // Phase 292 (FR-292-12) — a song selection, and *Set version…* on it.
    if (muView == "songs" && muSongSel.isNotEmpty()) return """<div class="mu-selbar on"><b>${muSongSel.size}</b> song${if (muSongSel.size == 1) "" else "s"} selected<span class="btn sm primary" data-vbulk>Set version…</span><span class="spacer" style="flex:1"></span><span class="tiny" style="cursor:pointer;color:var(--ink-soft);" data-vall>select all shown</span><span class="tiny" style="cursor:pointer;color:var(--ink-soft);" data-vnone>✕ clear</span></div>"""
    if (muView != "albums" || muSelected.isEmpty()) return """<div class="mu-selbar"></div>"""
    return buildString {
        append("""<div class="mu-selbar on"><b>${muSelected.size}</b> selected""")
        for ((k, l) in listOf("match" to "Match now", "covers" to "Fetch covers", "nfo" to "Write NFOs", "tags" to "Write tags…", "lock" to "Lock match", "clear" to "Clear match"))   // Phase 284 — Write tags…
            append("""<span class="btn sm${if (k == "match") " primary" else if (k == "clear") " ghost" else ""}" data-bulk="$k">$l</span>""")
        append("""<span class="spacer" style="flex:1"></span><span class="tiny" style="cursor:pointer;color:var(--ink-soft);" data-bulk="all">select all shown</span><span class="tiny" style="cursor:pointer;color:var(--ink-soft);" data-bulk="none">✕ clear</span></div>""")
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
        "songs" -> buildString {
            append("""<div class="mu-scroll${if (muSongSel.isNotEmpty()) " vr-selecting" else ""}"><table class="mu-tbl"><thead><tr><th>#</th><th>Title</th><th>Artist</th><th>Album</th><th>Length</th><th>Format</th><th>Lyrics</th><th>Match</th></tr></thead><tbody>""")
            for (t in d.songs) {
                val artists = t.artists.joinToString(" &amp; ") { """<a class="dim" href="#/artist/${it.artistId}">${it.name.esc()}</a>""" }
                // Phase 292 (FR-292-10/12) — chips after the title; the number turns into a checkbox on hover.
                val on = t.id in muSongSel
                // Phase 305 (FR-305-13) — *Bonus* after the version chips; folded: *also on N releases*; every copy: the
                // other copies sit indented under their song with the reason.
                val cls = listOfNotNull(if (on) "vr-on" else null, if (t.copyOf != null) "ed-cp" else null).joinToString(" ")
                val also = if (t.alsoOn > 0) """<div><span class="ed-also" data-edcopies="${t.id}">also on ${muPlural(t.alsoOn, "release")}</span></div>""" else ""
                val why = t.reason?.let { r -> """<div class="ed-cpw">↳ ${r.esc()} · <span class="ed-also" data-edcopies="${t.id}">why</span></div>""" } ?: ""
                append("""<tr${if (cls.isNotEmpty()) " class=\"$cls\"" else ""}><td class="n"><span class="vr-num">${t.position ?: ""}</span><span class="vr-sel${if (on) " on" else ""}" data-vsel="${t.id}">${if (on) "✓" else ""}</span></td><td>${t.albumId?.let { """<a href="#/album/$it">${t.title.esc()}</a>""" } ?: t.title.esc()}${vrBadges(t.id, t.versions)}${edBonus(t.bonus)}${if (t.noWords) """<span class="tiny muted" style="margin-left:8px">no words</span>""" else ""}$also$why</td>""")
                append("""<td class="dim">$artists</td><td class="dim">${t.albumId?.let { """<a class="dim" href="#/album/$it">${t.album.orEmpty().esc()}</a>""" } ?: ""}</td>""")
                append("""<td class="num">${muLen(t.lengthMs)}</td><td><span class="mu-fmt${if (t.reencodes) " w" else ""}">${t.format.esc()}${if (t.reencodes) """<span class="re">re-encodes on a phone</span>""" else ""}</span></td>""")
                append("<td>${lyricsCell(t.lyrics)}</td><td>${recordingCell(t.recording, t.albumMatched)}</td></tr>")
            }
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
    t.closest("[data-vsel]")?.let { s -> ev.preventDefault(); val id = s.getAttribute("data-vsel")!!; if (!muSongSel.remove(id)) muSongSel += id; muRender(scope); return }
    if (t.closest("[data-vall]") != null) { muDto?.songs?.forEach { muSongSel += it.id }; muRender(scope); return }
    if (t.closest("[data-vnone]") != null) { muSongSel.clear(); muRender(scope); return }
    if (t.closest("[data-vbulk]") != null) { vrOpenBulk(scope, muSongSel.toList()) { muSongSel.clear(); muLoad(scope) }; return }
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
        muRender(scope); return
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
            else { muDto = muDto?.copy(match = st.match); muRender(scope) }
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
