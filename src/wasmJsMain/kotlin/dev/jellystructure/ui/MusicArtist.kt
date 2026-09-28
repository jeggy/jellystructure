@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.Router
import dev.jellystructure.api.MusicApi
import dev.jellystructure.model.MusicAlbumRow
import dev.jellystructure.model.MusicArtistPageDto
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement

/*
 * Phase 278 (FR-278-8) — the Artist page: the picture, the sort name, *Group · NO · 1999–*, the match chip, what the
 * library holds and what they are credited on, then Overview (the biography, albums grouped Albums · Singles & EPs ·
 * Compilations · Live, and 277's Videos group) · Artwork · Genres · NFO · History. Artists are matched through their
 * albums' credits (276), so there is no artist search here.
 */

private var arId = ""
private var arPage: MusicArtistPageDto? = null
private var arTab = "overview"
private var arEditing = false

private val AR_TABS = listOf("overview" to "Overview", "artwork" to "Artwork", "genres" to "Genres", "nfo" to "NFO (raw)", "history" to "History")

fun renderMusicArtist(container: Element, scope: CoroutineScope, id: String, query: Map<String, String>) {
    arId = id; arPage = null; arEditing = false
    arTab = query["tab"]?.takeIf { t -> AR_TABS.any { it.first == t } } ?: "overview"
    container.innerHTML = """
        <div id="ar-root">
        <div class="backrow"><a class="btn sm ghost" href="#/library?kind=music&mview=artists">‹ Library</a><span class="crumb" id="ar-crumb"></span></div>
        <div class="pagebar" id="ar-bar"><h1>…</h1></div>
        <div id="ar-head"><span class="muted tiny">Loading…</span></div>
        <div class="tabs2" id="ar-tabs">${AR_TABS.joinToString("") { (k, l) -> """<span data-tab="$k">$l</span>""" }}</div>
        <div id="ar-panel"></div>
        </div>
    """.trimIndent()
    val root = document.getElementById("ar-root") as? HTMLElement ?: return
    root.addEventListener("click") { ev -> arClick(ev.target as? Element ?: return@addEventListener, ev, scope) }
    root.addEventListener("change") { ev ->
        val inp = ev.target as? HTMLInputElement ?: return@addEventListener
        val kind = inp.getAttribute("data-upload") ?: return@addEventListener
        val file = muPickedFile(inp) ?: return@addEventListener
        muToast("Uploading…")
        muUpload("/api/music/artist/$arId/artwork/upload?kind=$kind", file) { ok -> muToast(if (ok) "Picture written · Jellyfin re-reads it on the next sync" else "That image couldn't be used"); arReload(scope) }
    }
    arReload(scope)
}

private fun arReload(scope: CoroutineScope) {
    scope.launch {
        val p = MusicApi.artistPage(arId)
        if (p == null) {
            (document.getElementById("ar-head") as? HTMLElement)?.innerHTML = """<div class="note red">This artist isn't in the music library (any more).</div>"""
            return@launch
        }
        arPage = p
        arRender(scope)
    }
}

private fun arRender(scope: CoroutineScope) {
    val p = arPage ?: return
    val r = p.artist
    document.title = "Jellystructure — ${r.name}"
    (document.getElementById("ar-crumb") as? HTMLElement)?.innerHTML = """<a href="#/library?kind=music">Music</a> / <a href="#/library?kind=music&mview=artists">Artists</a> / ${r.name.esc()}"""
    (document.getElementById("ar-bar") as? HTMLElement)?.innerHTML = arBar(p)
    (document.getElementById("ar-head") as? HTMLElement)?.innerHTML = arHead(p)
    document.getElementById("ar-bar")?.let { muWireMenus(it) }
    arPanel(scope)
}

private val AR_URLS = mapOf("wikipedia" to "Wikipedia", "wikidata" to "Wikidata", "official homepage" to "Official site", "discogs" to "Discogs", "allmusic" to "AllMusic", "bandcamp" to "Bandcamp", "last.fm" to "Last.fm", "image" to "Picture on Commons")

private fun arBar(p: MusicArtistPageDto): String {
    val r = p.artist
    val matched = r.matchState == "matched"
    fun link(label: String, url: String, sub: String) =
        """<a class="menu-item" href="${url.esc()}" target="_blank" rel="noopener"><span class="mi-ic">↗</span><span>$label<span class="mi-sub">${sub.esc()}</span></span></a>"""
    return buildString {
        append("""<h1>${r.name.esc()}</h1><span class="spacer"></span>""")
        append("""<span class="menu-wrap"><span class="btn sm ghost menu-btn">External links <span class="caret">▾</span></span><div class="menu">""")
        p.jellyfinUrl?.let { append(link("Open in Jellyfin", it, "Artist in the Jellyfin web UI")) }
        r.mbid?.let { append(link("MusicBrainz", "https://musicbrainz.org/artist/$it", "musicbrainz.org/artist/${it.take(8)}…")) }
        for ((k, u) in r.urls) AR_URLS[k]?.let { append(link(it, u, "from MusicBrainz’s URL relationships")) }
        append("</div></span>")
        append("""<span class="menu-wrap"><span class="btn sm ghost menu-btn">⋯</span><div class="menu">""")
        if (matched || r.matchLocked) append("""<div class="menu-item" data-a="lock"><span class="mi-ic">${if (r.matchLocked) "🔓" else "🔒"}</span><span>${if (r.matchLocked) "Unlock match" else "Lock match"}<span class="mi-sub">${if (r.matchLocked) "Let the next album match update it" else "An album match never changes this artist"}</span></span></div>""")
        if (matched) append("""<div class="menu-item" data-a="clear"><span class="mi-ic">✕</span><span>Clear match<span class="mi-sub">Forget the MusicBrainz id and lock it</span></span></div>""")
        append("""<div class="menu-item" style="cursor:default;opacity:.7"><span class="mi-ic">ⓘ</span><span>Matched through albums<span class="mi-sub">An artist takes its id from an album match’s credits</span></span></div>""")
        append("</div></span>")
        if (r.path != null) {
            append("""<span class="split"><span class="btn primary" data-a="savesync">Save &amp; Sync ↻</span><span class="btn primary split-caret menu-btn"><span class="caret">▾</span></span><div class="menu">""")
            append("""<div class="menu-item" data-a="savesync"><span class="mi-ic">↻</span><span>Save &amp; Sync<span class="mi-sub">Write artist.nfo, then ask Jellyfin to re-read it</span></span></div>""")
            append("""<div class="menu-item" data-a="save"><span class="mi-ic">↓</span><span>Save → NFO<span class="mi-sub">Write artist.nfo in the artist folder</span></span></div>""")
            append("</div></span>")
        }
    }
}

private fun arHead(p: MusicArtistPageDto): String {
    val r = p.artist
    val facts = listOfNotNull(r.type, r.country, r.lifeSpan).joinToString(" · ")
    val pic = p.pictureUrl?.let { muImageStyle(it) } ?: muWordmarkStyle(r.name)
    val chip = when {
        r.matchState == "matched" -> """<span class="mu-match${if (r.matchLocked) " lk" else ""}"><span class="l1">${if (r.matchLocked) "$MU_LOCK Locked" else """<span class="dot"></span>MusicBrainz · artist"""} ${r.mbid?.let { """<a class="mono tiny" href="https://musicbrainz.org/artist/$it" target="_blank" rel="noopener" style="font-weight:500">${it.take(8)} ▸</a>""" } ?: ""}</span></span>"""
        p.albums.isEmpty() && p.creditedOn.size == 1 -> """<span class="mu-match w"><span class="l1"><span class="dot"></span>No MusicBrainz match</span><span class="l2">Credited on one ${if (p.creditedOn[0].type == "compilation") "compilation " else ""}track only — nothing to match against yet</span></span>"""
        r.matchLocked -> """<span class="mu-match lk"><span class="l1">$MU_LOCK Unmatched · locked</span><span class="l2">Cleared by hand — no album match changes it until you unlock it</span></span>"""
        else -> """<span class="mu-match w"><span class="l1"><span class="dot"></span>No MusicBrainz match</span><span class="l2">Matched when one of their albums is — Find match… on an album</span></span>"""
    }
    return buildString {
        append("""<div class="mu-pb"><div class="mu-circ" style="width:160px;$pic">""")
        if (p.pictureUrl == null) append("""${muInitials(r.name).esc()}${if (r.path != null) """<i class="mu-nocov"></i>""" else ""}""")
        append("</div><div>")
        append("""<div class="mu-mono tiny muted">${(r.mbSortName ?: r.sortName ?: r.name).esc()}</div>""")
        if (facts.isNotEmpty() || r.disambiguation != null) append("""<div class="mu-pbm">${if (facts.isNotEmpty()) "<span>${facts.esc()}</span>" else ""}${r.disambiguation?.let { """<span class="sep">·</span><span class="muted">${it.esc()}</span>""" } ?: ""}</div>""")
        append("""<div class="mu-pbm"><span>${muPlural(p.albums.size, "album")}</span><span class="sep">·</span><span>${muPlural(p.songs, "song")} in the library</span>""")
        if (p.creditedOn.isNotEmpty()) append("""<span class="sep">·</span><span>credited on ${p.creditedOn.joinToString(", ") { """<a href="#/album/${it.id}">${it.title.esc()}</a>""" }}</span>""")
        append("</div>")
        append("""<div class="mu-acts">$chip</div>""")
        if (p.pictureUrl == null && r.path != null) append("""<div class="tiny" style="margin-top:10px;color:var(--warn)">No artist picture — <a href="#" data-tabgo="artwork">choose one in Artwork</a>. MusicBrainz has none; they come from fanart.tv or Wikimedia Commons.</div>""")
        append("</div></div>")
    }
}

private fun arPanel(scope: CoroutineScope) {
    val tabs = document.querySelectorAll("#ar-tabs [data-tab]")
    for (i in 0 until tabs.length) (tabs.item(i) as? HTMLElement)?.let { it.className = if (it.getAttribute("data-tab") == arTab) "on" else "" }
    val el = document.getElementById("ar-panel") as? HTMLElement ?: return
    val p = arPage ?: return
    when (arTab) {
        "artwork" -> { el.innerHTML = """<span class="muted tiny">Asking fanart.tv and Commons…</span>"""; scope.launch { el.innerHTML = arArtwork(p) } }
        "genres" -> el.innerHTML = arGenres(p)
        "nfo" -> { el.innerHTML = """<span class="muted tiny">Loading…</span>"""; scope.launch { el.innerHTML = arNfo(p) } }
        "history" -> { el.innerHTML = """<span class="muted tiny">Loading…</span>"""; scope.launch { el.innerHTML = muHistory(p.artist.id) } }
        else -> el.innerHTML = arOverview(p)
    }
}

private fun albumCell(a: MusicAlbumRow): String =
    """<a class="mu-cell" href="#/album/${a.id}">${muCoverHtml(a.title, if (a.cover) "/api/music/image/album/${a.id}?v=${a.v}" else null, chip = muMatchChip(a.match))}<div class="ttl">${a.title.esc()}</div><div class="yr">${a.year?.let { "$it · " } ?: ""}${muPlural(a.songs, "song")}</div></a>"""

private fun arOverview(p: MusicArtistPageDto): String {
    val r = p.artist
    val groups = listOf(
        "Albums" to setOf("album", "soundtrack"), "Singles & EPs" to setOf("single"), "Compilations" to setOf("compilation"), "Live" to setOf("live"),
    )
    return buildString {
        append("""<div class="mu-sec">Biography</div>""")
        val bio = p.biography
        when {
            arEditing -> append("""<div class="mu-bio"><textarea id="ar-bio">${(r.biographyEdited ?: bio).orEmpty().esc()}</textarea><div class="row" style="gap:8px;margin-top:8px;"><span class="btn sm primary" data-a="biosave">Save → artist.nfo</span><span class="btn sm ghost" data-a="biocancel">Cancel</span>${if (r.biographyEdited != null) """<span class="btn sm ghost" data-a="bioreset">Use Wikipedia’s again</span>""" else ""}</div></div>""")
            bio != null -> {
                append("""<div class="mu-bio">${bio.esc().replace("\n", "<br>")}</div>""")
                val src = if (r.biographyEdited != null) "written here" else (r.biographySource ?: "Wikipedia") + " · via MusicBrainz’s URL relationships"
                append("""<div class="tiny muted" style="margin-top:6px;">Source: ${src.esc()} · <a href="#" data-a="bioedit">Edit</a></div>""")
            }
            else -> append("""<div class="tiny muted">No biography found — MusicBrainz links this artist to no Wikipedia or Wikidata page${if (r.matchState != "matched") " (or the artist isn’t matched yet)" else ""}. ${if (r.path != null) """<a href="#" data-a="bioedit">Write one</a>; it is saved into <span class="mono">artist.nfo</span>.""" else ""}</div>""")
        }
        var any = false
        for ((label, types) in groups) {
            val own = p.albums.filter { it.type in types }
            if (own.isEmpty()) continue
            any = true
            append("""<div class="mu-sec">$label <span class="tiny muted" style="text-transform:none;letter-spacing:0;font-weight:500">${own.size}</span></div><div class="mu-grid">${own.joinToString("") { albumCell(it) }}</div>""")
        }
        if (p.creditedOn.isNotEmpty()) {
            append("""<div class="mu-sec">Credited on <span class="tiny muted" style="text-transform:none;letter-spacing:0;font-weight:500">${p.creditedOn.size}</span></div><div class="mu-grid">${p.creditedOn.joinToString("") { albumCell(it) }}</div>""")
        } else if (!any) append("""<div class="mu-sec">Albums</div><div class="tiny muted">No albums in the library.</div>""")
        if (p.videos.isNotEmpty()) {
            append("""<div class="mu-sec">Videos <span class="tiny muted" style="text-transform:none;letter-spacing:0;font-weight:500">from the Music videos library · matched by the filename’s artist · edited on their own pages</span></div><div class="mu-vgrid">""")
            for (v in p.videos) append("""<a class="mu-vt" href="#/media/${v.id}"><div class="im" style="${muWordmarkStyle(v.title)}">${v.title.esc()}${v.durationSec?.let { """<span class="d">${muLen(it * 1000L)}</span>""" } ?: ""}</div><div class="cap">Music video${v.year?.let { " · $it" } ?: ""}</div></a>""")
            append("</div>")
        }
    }
}

private suspend fun arArtwork(p: MusicArtistPageDto): String {
    val r = p.artist
    if (r.path == null) return """<div class="tiny muted">This artist has no folder of their own in the library — only a credit — so there is nowhere to put a picture.</div>"""
    val art = MusicApi.artistArtwork(r.id)
    fun inUse(kind: String, file: String?, label: String, wide: Boolean): String {
        val url = "/api/music/image/artist/${r.id}/$kind?v=${r.updatedAt}"
        val locked = art?.locked?.contains(kind) == true
        return if (file != null) """<div class="mu-aw use"><div class="im${if (wide) " wide" else ""}${if (kind == "logo") " chk" else ""}" style="${if (kind == "logo") "background-size:contain;background-position:center;background-repeat:no-repeat;background-image:url('$url')" else muImageStyle(url)}${if (kind == "thumb") ";border-radius:50%" else ""}"></div><div class="cap"><b>${file.esc()}</b><span class="d">$label</span><span class="spacer" style="flex:1"></span><span class="chip" style="font-size:.66rem;cursor:pointer" data-lock="$kind" data-locked="$locked">${if (locked) "🔒 locked" else "🔓 lock"}</span><span class="chip" style="font-size:.66rem;cursor:pointer" data-clear="$kind">Clear</span></div></div>"""
            else """<label class="mu-aw" style="cursor:pointer;border-style:dashed;"><div class="im${if (wide) " wide" else ""}" style="display:flex;align-items:center;justify-content:center;flex-direction:column;gap:6px;color:var(--ink-soft);font-size:.8rem;">⬆<span>Upload a ${label.substringBefore(' ')}</span><span class="tiny muted">${if (kind == "thumb") "missing — a task" else "optional"}</span></div><input type="file" accept="image/*" data-upload="$kind" style="display:none"></label>"""
    }
    return buildString {
        append("""<div class="mu-sec">Currently in use</div><div class="mu-art">""")
        if (p.pictureUrl != null && art?.thumb == null) append("""<div class="mu-aw use"><div class="im" style="${muImageStyle(p.pictureUrl)};border-radius:50%"></div><div class="cap"><b>Jellyfin’s picture</b><span class="d">no file in the folder</span></div></div>""")
        else append(inUse("thumb", art?.thumb, "thumb · 1:1", false))
        append(inUse("background", art?.background, "backdrop · 16:9", true))
        append(inUse("logo", art?.logo, "logo · transparent", false))
        append("</div>")
        art?.credit?.let { append("""<div class="tiny muted" style="margin-top:6px;">Picture: ${it.esc()} — the credit travels in artist.nfo</div>""") }
        val fan = art?.candidates.orEmpty().filter { it.source == "fanart.tv" }
        val commons = art?.candidates.orEmpty().filter { it.source == "Wikimedia Commons" }
        append("""<div class="mu-sec">Candidates · fanart.tv</div>""")
        append(when {
            art?.fanartKey == false -> """<div class="tiny muted"><a href="#/settings?tab=connections">Add a fanart.tv key in Settings</a> for artist thumbs, backgrounds and logos.</div>"""
            r.mbid == null -> """<div class="tiny muted">A match (through one of their albums) lets fanart.tv look them up.</div>"""
            fan.isEmpty() -> """<div class="tiny muted">fanart.tv has nothing for this artist.</div>"""
            else -> artGrid(fan, "artist")
        })
        append("""<div class="mu-sec">Candidates · Wikimedia Commons</div>""")
        append(if (commons.isEmpty()) """<div class="tiny muted">No image on Wikimedia Commons for this artist.</div>""" else artGrid(commons, "artist"))
    }
}

private fun arGenres(p: MusicArtistPageDto): String = buildString {
    append("""<div class="mu-sec">MusicBrainz genres · ${if (p.artist.mbGenres.isNotEmpty()) "votes on the artist" else "summed over their albums"}</div>""")
    if (p.genres.isEmpty()) append("""<div class="tiny muted">None yet — they come with a match.</div>""")
    else append("""<div class="mu-gchips">${p.genres.joinToString("") { """<span class="mu-gc${if (it.count >= 3) " on" else ""}"><span class="bx">${if (it.count >= 3) "✓" else ""}</span>${it.name.esc()}<span class="v">${it.count}</span></span>""" }}</div>""")
    append("""<div class="tiny muted" style="margin-top:10px;">An artist’s genres are read, not chosen: an album’s own pick is on its Genres tab.</div>""")
}

private suspend fun arNfo(p: MusicArtistPageDto): String {
    val n = MusicApi.artistNfo(p.artist.id)
    return buildString {
        append("""<div class="row center" style="margin-bottom:10px;gap:8px"><span class="tiny muted mono">${(n?.path ?: "no artist folder").esc()}</span><span class="spacer"></span>""")
        append(if (n?.text == null) """<span class="badge">not written yet</span>""" else """<span class="badge ok">written${p.artist.nfoWrittenAt?.let { " " + dev.jellystructure.formatStoredTs(it.toString()) } ?: ""}</span>""")
        append("</div>")
        append(if (n?.text != null) """<div class="mu-nfo">${n.text.esc()}</div>""" else """<div class="tiny muted">Written by the next run once the artist is matched or has a biography typed here, or now with Save → NFO.</div>""")
    }
}

private fun arClick(t: Element, ev: org.w3c.dom.events.Event, scope: CoroutineScope) {
    val p = arPage ?: return
    val id = p.artist.id
    t.closest("#ar-tabs [data-tab]")?.let { tab ->
        arTab = tab.getAttribute("data-tab") ?: "overview"
        Router.updateQuery(mapOf("tab" to arTab)); arPanel(scope); return
    }
    t.closest("[data-tabgo]")?.let { g -> ev.preventDefault(); arTab = g.getAttribute("data-tabgo") ?: "overview"; Router.updateQuery(mapOf("tab" to arTab)); arPanel(scope); return }
    t.closest("[data-use]")?.let { u ->
        val i = u.getAttribute("data-use")?.toIntOrNull() ?: return
        val src = u.getAttribute("data-src")
        scope.launch {
            val c = MusicApi.artistArtwork(id)?.candidates?.filter { it.source == src }?.getOrNull(i) ?: return@launch muToast("That picture isn’t on offer any more")
            muToast(if (MusicApi.useArtistImage(id, c)) "Written · Jellyfin re-reads it on the next sync" else "That picture couldn’t be used")
            arReload(scope)
        }
        return
    }
    t.closest("[data-clear]")?.let { c ->
        val kind = c.getAttribute("data-clear") ?: return
        scope.launch { muToast(if (MusicApi.clearArtistImage(id, kind)) "Removed" else "Nothing removed"); arReload(scope) }
        return
    }
    t.closest("[data-lock]")?.let { c ->
        val kind = c.getAttribute("data-lock") ?: return
        val locked = c.getAttribute("data-locked") == "true"
        scope.launch { MusicApi.lockArtistImage(id, kind, !locked); muToast(if (!locked) "Locked · a run won’t replace it" else "Unlocked"); arPanel(scope) }
        return
    }
    val a = t.closest("[data-a]") ?: return
    ev.preventDefault()
    when (a.getAttribute("data-a")) {
        "bioedit" -> { arEditing = true; arPanel(scope) }
        "biocancel" -> { arEditing = false; arPanel(scope) }
        "biosave" -> scope.launch {
            val text = (document.getElementById("ar-bio") as? HTMLTextAreaElement)?.value?.trim().orEmpty()
            if (MusicApi.setBiography(id, text.ifEmpty { null }) != null) {
                MusicApi.saveArtist(id, sync = true)
                muToast("artist.nfo written · Jellyfin re-reading"); arEditing = false; arReload(scope)
            } else muToast("That didn’t save")
        }
        "bioreset" -> scope.launch { MusicApi.setBiography(id, null); arEditing = false; arReload(scope) }
        "lock" -> scope.launch { MusicApi.lockArtist(id, !p.artist.matchLocked)?.let { muToast(if (it.matchLocked) "Locked" else "Unlocked"); arReload(scope) } }
        "clear" -> scope.launch { MusicApi.clearArtist(id)?.let { muToast("Match cleared and locked"); arReload(scope) } }
        "savesync", "save" -> scope.launch {
            val sync = a.getAttribute("data-a") == "savesync"
            muToast(when (MusicApi.saveArtist(id, sync)) {
                "written" -> if (sync) "artist.nfo written · Jellyfin re-reading ↻" else "artist.nfo written"
                "unchanged" -> "artist.nfo already says this"
                "no_folder" -> "This artist has no folder to write into"
                null -> "The server didn’t answer"
                else -> "artist.nfo couldn’t be written"
            })
            arReload(scope)
        }
    }
}
