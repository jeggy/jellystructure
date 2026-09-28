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

private var muView = "albums"
private var muQuery = ""
private var muSort: String? = null
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
    if (muView != "albums") add("mview=$muView")
    if (muQuery.isNotBlank()) add("q=${dev.jellystructure.encodeURIComponent(muQuery)}")
    muSort?.let { add("sort=$it") }
    muFacets.filterValues { it.isNotEmpty() }.forEach { (k, v) -> add("f.$k=${v.joinToString(",") { dev.jellystructure.encodeURIComponent(it) }}") }
}.joinToString("&").let { "#/library?$it" }

fun renderMusicLibrary(container: Element, scope: CoroutineScope, query: Map<String, String>) {
    muView = query["mview"]?.takeIf { it in setOf("albums", "artists", "songs") } ?: "albums"
    muQuery = query["q"].orEmpty()
    muSort = query["sort"]
    muFacets.clear(); muSelected.clear(); muOpenFacet = null; muDto = null
    query.filterKeys { it.startsWith("f.") }.forEach { (k, v) -> muFacets[k.removePrefix("f.")] = v.split(',').filter { it.isNotBlank() }.toMutableSet() }

    container.innerHTML = """
        <div class="pagebar">
          <h1>Library</h1>
          <span class="spacer"></span>
          <span class="searchwrap"><input id="mu-search" class="input" type="search" placeholder="⌕ search albums, artists, songs…" style="width:240px;flex-shrink:0;"></span>
          <span class="seg" id="kindseg">
            <span data-kind="">All</span><span data-kind="MOVIE">Movies</span><span data-kind="TV_SHOW">TV</span><span data-kind="MUSIC_VIDEO">Music videos</span><span class="on">Music</span>
          </span>
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
    val kinds = container.querySelectorAll("#kindseg [data-kind]")
    for (i in 0 until kinds.length) (kinds.item(i) as? HTMLElement)?.let { k ->
        k.addEventListener("click") { val v = k.getAttribute("data-kind").orEmpty(); App.navigate(if (v.isEmpty()) "/library" else "/library?kind=$v") }
    }
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
        val dto = MusicApi.browse(muView, muQuery, muFacets.mapValues { it.value.toSet() }, muSort)
        if (dto == null) {
            (document.getElementById("mu-lib") as? HTMLElement)?.innerHTML = """<div class="note red">Couldn't read the music library from the server.</div>"""
            return@launch
        }
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
        for ((k, l) in listOf("albums" to "Albums", "artists" to "Artists", "songs" to "Songs"))
            append("""<span data-mview="$k" class="${if (muView == k) "on" else ""}">$l</span>""")
        append("</span>")
        if (live) append(statusLine(d))
        append("</div>")
        append("""<p class="page-sub" style="margin-top:0">Albums, artists and songs from Jellyfin’s <b>${d.libraries.joinToString(" · ") { it.name.esc() }.ifEmpty { "music" }}</b> library, matched against <b>MusicBrainz</b> — what this page maintains is written to <span class="mono">album.nfo</span> / <span class="mono">artist.nfo</span>, never into the files. Not the <b>Music videos</b> library: those are films and stay under their own kind.</p>""")
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
        val n = f.values.count { it.on }
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
    append("</div>")
}

private fun activeBar(d: MusicBrowseDto): String {
    val act = d.facets.filter { f -> f.values.any { it.on } }
    if (act.isEmpty()) return ""
    return act.joinToString(""" <span class="tiny muted">and</span> """) { f ->
        """<span class="fxchip">${f.label.esc()} is ${f.values.filter { it.on }.joinToString(" or ") { it.label.esc() }} <span class="rm" data-frm="${f.key}" style="cursor:pointer">✕</span></span>"""
    } + """<span class="livecount" style="margin-left:6px;"><span class="n">${d.total}</span><span class="tiny muted"> ${muView} match</span></span><span class="tiny" style="margin-left:6px;cursor:pointer;color:var(--ink-soft);" data-fclear>clear all</span>"""
}

private fun selBar(): String {
    if (muView != "albums" || muSelected.isEmpty()) return """<div class="mu-selbar"></div>"""
    return buildString {
        append("""<div class="mu-selbar on"><b>${muSelected.size}</b> selected""")
        for ((k, l) in listOf("match" to "Match now", "covers" to "Fetch covers", "nfo" to "Write NFOs", "lock" to "Lock match", "clear" to "Clear match"))
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
            append("""<div class="mu-scroll"><table class="mu-tbl"><thead><tr><th>#</th><th>Title</th><th>Artist</th><th>Album</th><th>Length</th><th>Format</th><th>Lyrics</th><th>Match</th></tr></thead><tbody>""")
            for (t in d.songs) {
                val artists = t.artists.joinToString(" &amp; ") { """<a class="dim" href="#/artist/${it.artistId}">${it.name.esc()}</a>""" }
                append("""<tr><td class="n">${t.position ?: ""}</td><td>${t.albumId?.let { """<a href="#/album/$it">${t.title.esc()}</a>""" } ?: t.title.esc()}</td>""")
                append("""<td class="dim">$artists</td><td class="dim">${t.albumId?.let { """<a class="dim" href="#/album/$it">${t.album.orEmpty().esc()}</a>""" } ?: ""}</td>""")
                append("""<td class="num">${muLen(t.lengthMs)}</td><td><span class="mu-fmt${if (t.reencodes) " w" else ""}">${t.format.esc()}${if (t.reencodes) """<span class="re">re-encodes on a phone</span>""" else ""}</span></td>""")
                append("<td>${lyricsCell(t.lyrics)}</td><td>${recordingCell(t.recording, t.albumMatched)}</td></tr>")
            }
            append("</tbody></table></div>")
        }
        else -> buildString {
            append("""<div class="mu-grid${if (muSelected.isNotEmpty()) " selecting" else ""}">""")
            for (a in d.albums) {
                val url = if (a.cover) "/api/music/image/album/${a.id}?v=${a.v}" else null
                append("""<a class="mu-cell${if (a.id in muSelected) " on" else ""}" href="#/album/${a.id}" data-id="${a.id}"><span class="mu-sel" data-sel="${a.id}">✓</span>""")
                append(muCoverHtml(a.title, url, chip = muMatchChip(a.match)))
                append("""<div class="ttl">${a.title.esc()}</div><div class="sub">${a.artist.esc()}</div><div class="yr">${a.year?.let { "$it · " } ?: ""}${muPlural(a.songs, "song")}</div></a>""")
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
    t.closest("[data-mview]")?.let { v ->
        muView = v.getAttribute("data-mview") ?: "albums"; muSelected.clear(); muOpenFacet = null
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
    if (t.closest("[data-fclear]") != null) { muFacets.clear(); muLoad(scope); return }
    t.closest("[data-bulk]")?.let { b ->
        when (val k = b.getAttribute("data-bulk")) {
            "all" -> { muDto?.albums?.forEach { muSelected += it.id }; muRender(scope) }
            "none" -> { muSelected.clear(); muRender(scope) }
            null -> Unit
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
