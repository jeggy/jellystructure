@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.api.MusicApi
import dev.jellystructure.model.MusicAlbumPageDto
import dev.jellystructure.model.MusicAlbumRef
import dev.jellystructure.model.MusicCopiesDto
import dev.jellystructure.model.MusicSamePairDto
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLAudioElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/*
 * Phase 305 — an album's official tracklist, its extras, its singles and B-sides, and one copy of every song, on the
 * admin's pages (design/app/editions.js + editions.css, `ed-*`). Every answer comes from the server; this file only
 * renders and sends the owner's decisions back.
 */

/** *Bonus* — 292's chip family, no hue, after the version chips (FR-305-13). */
internal fun edBonus(on: Boolean): String = if (on) """<span class="ed-bonus" title="An extra: not on any official album">Bonus</span>""" else ""

/** *Show every copy* — remembered per admin (dev review 9: `js-music-copies`, like `js-theme`). */
internal var edEveryCopy: Boolean
    get() = runCatching { localStorage.getItem("js-music-copies") == "1" }.getOrDefault(false)
    set(v) { runCatching { localStorage.setItem("js-music-copies", if (v) "1" else "0") } }

private fun howChip(how: String, label: String) = """<span class="ed-how${if (how == "user") " you" else ""}">${label.esc()}</span>"""

/** FR-305-12 — under the release line: *Official album: 11 songs, as on 14 of 20 releases · Change…* and *held: …*. */
internal fun edOfficialLine(p: MusicAlbumPageDto): String {
    val o = p.official ?: return ""
    return buildString {
        append("""<div class="ed-off"><b>Official album: ${muPlural(o.songs, "song")}, as on ${if (o.chosenByYou) (o.release ?: "the pressing you chose").esc() else "${o.k} of ${muPlural(o.m, "release")}"}</b>""")
        if (o.chosenByYou) append("""${howChip("user", "chosen by you")}<a href="#" data-ed="auto">Back to automatic</a>""")
        append("""<a href="#" data-ed="change">Change…</a></div>""")
        o.held?.let { append("""<div class="ed-off" style="margin-top:4px">held: ${it.esc()}</div>""") }
    }
}

/** *11 songs + 1 extra* — the header's count (FR-305-12). */
internal fun edCount(p: MusicAlbumPageDto): String? = p.official?.let { o -> muPlural(o.songs, "song") + if (o.extras > 0) " + " + muPlural(o.extras, "extra") else "" }

/** *Move to…* (FR-305-6 rule 4): any album of the same artist, *No album*, and *Back to automatic* when moved by you. */
internal fun edMoveMenu(singleId: String, targets: List<MusicAlbumRef>, homeId: String?, byYou: Boolean): String = buildString {
    append("""<span class="menu-wrap"><span class="btn sm ghost menu-btn">Move to… <span class="caret">▾</span></span><div class="menu">""")
    for (a in targets) append("""<div class="menu-item" data-edmove="${singleId.esc()}|${a.id.esc()}"><span class="mi-ic">${if (homeId == a.id) "✓" else ""}</span><span>${a.title.esc()}<span class="mi-sub">${a.year ?: ""}</span></span></div>""")
    append("""<div class="menu-item" data-edmove="${singleId.esc()}|"><span class="mi-ic">${if (homeId == null) "✓" else ""}</span><span>No album<span class="mi-sub">Back to the artist’s Singles &amp; EPs</span></span></div>""")
    if (byYou) append("""<div class="menu-item" data-edmove="${singleId.esc()}|auto"><span class="mi-ic">⟲</span><span>Back to automatic<span class="mi-sub">MusicBrainz, a remix or the title decide again</span></span></div>""")
    append("</div></span>")
}

/** A single's own page: *Single from {album}* with its Linked chip and *Move to…*, or *No album*. */
internal fun edSingleLine(p: MusicAlbumPageDto): String {
    val s = p.singleFrom ?: return ""
    return buildString {
        append("""<div class="ed-off">""")
        if (s.albumId != null) append("""<b>Single from <a href="#/album/${s.albumId}">${(s.title ?: "its album").esc()}</a></b>${howChip(s.how, s.howLabel)}""")
        else append("""<b>No album</b><span class="tiny muted">on the artist’s Singles &amp; EPs</span>${if (s.how == "user") howChip("user", "you") else ""}""")
        append(edMoveMenu(p.album.id, p.moveTargets, s.albumId, s.how == "user"))
        append("</div>")
    }
}

/** FR-305-12 — *Singles & B-sides* below the Tracks table, and the B-sides fold. */
internal fun edSinglesHtml(p: MusicAlbumPageDto, open: Boolean): String {
    if (p.singles.isEmpty()) return ""
    val n = p.bsides.size
    return buildString {
        append("""<div class="mu-sec">Singles &amp; B-sides <span class="tiny muted" style="text-transform:none;letter-spacing:0;font-weight:500">${muPlural(p.singles.size, "single")} · ${muPlural(n, "B-side")}</span></div>""")
        append("""<div class="mu-scroll"><table class="mu-tbl"><thead><tr><th>Single</th><th>Year</th><th>B-sides here</th><th>Linked</th><th></th></tr></thead><tbody>""")
        for (s in p.singles) {
            val bs = p.bsides.filter { it.singleId == s.id }
            val cover = s.coverUrl?.let { muImageStyle(it) } ?: muWordmarkStyle(s.title)
            append("""<tr><td><a class="ed-sn" href="#/album/${s.id}"><span class="ed-sth" style="$cover"></span>${s.title.esc()}</a></td><td class="num">${s.year ?: ""}</td>""")
            append("""<td class="dim">${bs.size}${if (bs.isNotEmpty()) " · " + bs.take(2).joinToString(", ") { it.track.title.esc() } + if (bs.size > 2) " …" else "" else ""}</td>""")
            append("""<td>${howChip(s.how, s.howLabel)}</td><td>${edMoveMenu(s.id, p.moveTargets.ifEmpty { listOf(MusicAlbumRef(p.album.id, p.album.title, p.year)) }, p.album.id, s.how == "user")}</td></tr>""")
        }
        append("</tbody></table></div>")
        if (n > 0) {
            append("""<div class="ed-bfold"><b>${muPlural(n, "B-side")}</b><a href="#" data-ed="bsides">${if (open) "Hide" else "Show"}</a><span class="tiny muted">· in Ravilo they play after the extras with <i>Play album + extras</i></span></div>""")
            if (open) {
                append("""<div class="mu-scroll"><table class="mu-tbl"><tbody>""")
                for (b in p.bsides) append("""<tr><td>${b.track.title.esc()}${vrBadges(b.track.id, b.track.versions)}<div class="ed-first">from ${b.single.esc()} (single${b.year?.let { ", $it" } ?: ""})</div></td><td class="num">${muLen(b.track.lengthMs)}</td><td><span class="mu-fmt">${b.track.format.esc()}</span></td></tr>""")
                append("</tbody></table></div>")
            }
        }
        append("""<div class="tiny muted" style="margin-top:8px;line-height:1.6">The A-sides are not repeated: each is the album’s own song (one copy). <i>Linked</i> says how the single found this album — MusicBrainz’s <i>single from</i>, a remix of an album song, its A-side’s title, or you.</div>""")
    }
}

/** The clicks the album page's editions parts own; true when handled. */
internal fun edAlbumClick(t: Element, scope: CoroutineScope, p: MusicAlbumPageDto, toggleBsides: () -> Unit, reload: () -> Unit): Boolean {
    t.closest("[data-edmove]")?.let { m ->
        val (sid, to) = (m.getAttribute("data-edmove") ?: return true).split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        scope.launch {
            val ok = MusicApi.setHome(sid, to.ifEmpty { null }, automatic = to == "auto")
            muToast(when { !ok -> "That didn’t save"; to == "auto" -> "Back to automatic"; to.isEmpty() -> "Moved to No album · on the artist’s Singles & EPs again"; else -> "Moved · it sits under that album now" })
            reload()
        }
        return true
    }
    val a = t.closest("[data-ed]") ?: return false
    when (a.getAttribute("data-ed")) {
        "bsides" -> toggleBsides()
        "auto" -> scope.launch { muToast(if (MusicApi.setOfficial(p.album.id, null)) "Back to automatic · the tracklist most releases share" else "That didn’t save"); reload() }
        "change" -> edOpenPressings(scope, p, reload)
        else -> return false
    }
    return true
}

// ── *Change…*: the pressings (FR-305-4) ──

internal fun edOpenPressings(scope: CoroutineScope, p: MusicAlbumPageDto, after: () -> Unit) {
    edModal("""<h3>Change the official album · ${p.album.title.esc()}</h3><p class="tiny muted">Asking MusicBrainz for every pressing…</p>""")
    scope.launch {
        val list = MusicApi.pressings(p.album.id) ?: return@launch edModal("""<h3>MusicBrainz didn’t answer</h3><p>Try again in a moment.</p><div class="row" style="justify-content:flex-end"><span class="btn ghost" data-edmx>Close</span></div>""")
        val o = p.official
        edModal(buildString {
            append("""<h3>Change the official album · ${p.album.title.esc()}</h3>""")
            append("""<p>The pressings <i>Find match…</i> lists. Automatic is the tracklist most official releases share${o?.let { ": <b>${it.k} of ${it.m}</b> have these ${it.songs} songs" } ?: ""}.</p>""")
            append("""<div class="mu-scroll"><table class="mu-tbl ed-ptbl"><thead><tr><th>Country</th><th>Date</th><th>Label</th><th>Format</th><th>Tracks</th><th>Against the official</th><th></th></tr></thead><tbody>""")
            for (r in list) {
                val op = r.option
                val action = when {
                    r.chosen -> """<span class="tiny muted">official now · chosen by you</span>"""
                    r.automatic && o?.chosenByYou == true -> """<span class="btn sm ghost" data-eduse="">Use automatic</span>"""
                    r.automatic -> """<span class="tiny muted">official now</span>"""
                    r.pickable -> """<span class="btn sm ghost" data-eduse="${op.mbid.esc()}">Use as the official album</span>"""
                    else -> """<span class="tiny muted">+ ${r.notInLibrary} not in the library</span>"""
                }
                append("""<tr><td>${(op.country ?: "—").esc()}</td><td class="mu-mono" style="font-size:.74rem">${(op.date ?: "").esc()}</td><td>${(op.label ?: "").esc()}</td><td>${(op.format ?: "").esc()}${op.disambiguation?.let { " · " + it.esc() } ?: ""}</td><td class="num">${op.trackCount}</td>""")
                append("""<td class="${if (r.against == "same as the official") "dim" else ""}">${r.against.esc()}</td><td>$action</td></tr>""")
            }
            append("</tbody></table></div>")
            append("""<p class="tiny" style="margin-top:10px">A pick reads <i>chosen by you · Back to automatic</i> and is kept across runs. Songs the held files have that the pick lacks become extras; songs the pick has that the files lack become gap rows. A pressing whose extra songs aren’t in the library can’t be picked.</p>""")
            append("""<div class="row" style="justify-content:flex-end;gap:8px;margin-top:10px"><span class="btn ghost" data-edmx>Close</span></div>""")
        }) { t ->
            t.closest("[data-eduse]")?.let { u ->
                val rel = u.getAttribute("data-eduse").orEmpty()
                scope.launch {
                    val ok = MusicApi.setOfficial(p.album.id, rel.ifEmpty { null })
                    edCloseModal(); muToast(if (!ok) "That didn’t save" else if (rel.isEmpty()) "Back to automatic" else "Official album chosen · kept across runs"); after()
                }
            }
        }
    }
}

// ── the copies panel (FR-305-13) ──

private var edPanelTrack: String? = null
private var edPanelDto: MusicCopiesDto? = null
private var edPicking = false
private var edPickQ = ""
private var edPickHits: List<dev.jellystructure.model.MusicSongRow> = emptyList()
private var edAfter: (() -> Unit)? = null

internal fun edOpenCopies(scope: CoroutineScope, trackId: String, after: () -> Unit) {
    edCloseCopies()
    edPanelTrack = trackId; edAfter = after; edPicking = false; edPickQ = ""; edPickHits = emptyList()
    val scrim = document.createElement("div") as HTMLElement
    scrim.id = "ed-scrim"; scrim.className = "mu-scrim on"
    scrim.addEventListener("click") { edCloseCopies() }
    val panel = document.createElement("aside") as HTMLElement
    panel.id = "ed-panel"; panel.className = "mu-panel on"
    panel.setAttribute("aria-label", "Copies")
    panel.innerHTML = """<div class="mu-ph"><h3>Copies</h3><span class="spacer"></span><span class="btn sm ghost" data-edx>✕</span></div><div class="mu-pbody"><span class="muted tiny">Loading…</span></div>"""
    panel.addEventListener("click") { ev -> edPanelClick(ev.target as? Element ?: return@addEventListener, scope) }
    panel.addEventListener("input") { ev ->
        val inp = ev.target as? HTMLInputElement ?: return@addEventListener
        if (inp.id != "ed-pq") return@addEventListener
        edPickQ = inp.value
        edSearch(scope)
    }
    document.body?.appendChild(scrim); document.body?.appendChild(panel)
    edLoadCopies(scope)
}

private fun edLoadCopies(scope: CoroutineScope) {
    val id = edPanelTrack ?: return
    scope.launch { edPanelDto = MusicApi.copies(id) ?: return@launch muToast("Couldn’t read this song’s copies"); edPaint() }
}

private fun edCloseCopies() {
    edPanelTrack = null; edPanelDto = null
    document.getElementById("ed-scrim")?.remove(); document.getElementById("ed-panel")?.remove()
}

private val KIND_LABEL = mapOf("album" to "Album", "extra" to "Album’s extra", "single" to "Single", "compilation" to "Compilation · box set", "live" to "Live album")

private fun edPaint() {
    val panel = document.getElementById("ed-panel") as? HTMLElement ?: return
    val d = edPanelDto ?: return
    panel.innerHTML = buildString {
        append("""<div class="mu-ph"><h3>${d.title.esc()}</h3><span class="tiny muted">${d.artist.esc()}</span><span class="spacer"></span><span class="btn sm ghost" data-edx>✕</span></div><div class="mu-pbody">""")
        append("""<div class="mu-sec" style="margin-top:0">${if (d.copies.size > 1) "One song · ${d.copies.size} copies" else "One copy"}</div>""")
        for (c in d.copies) {
            val cover = c.albumId?.let { muImageStyle("/api/music/image/album/$it") } ?: muWordmarkStyle(c.album ?: d.title)
            val why = c.reasonText ?: if (c.kind == "album") "the album’s own · the better file" else "the better file"
            append("""<div class="ed-copy"><span class="c" style="$cover"></span><div><div class="t">${c.albumId?.let { """<a href="#/album/$it">${(c.album ?: "").esc()}</a>""" } ?: (c.album ?: "").esc()}${if (c.shown) """<span class="ed-kept">shown in lists</span>""" else ""}${vrChips(c.versions)}${edBonus(c.extra)}</div>""")
            append("""<div class="s">${listOfNotNull(KIND_LABEL[c.kind] ?: c.kind, c.year?.toString(), c.position?.let { "track $it" }, c.format.ifBlank { null }, if (c.lossless) "lossless" else null, c.lengthMs?.let { muLen(it) }).joinToString(" · ").esc()}</div></div>""")
            append("""<div class="a"><span class="ed-why">${why.esc()}</span>${if (!c.shown) """<span class="btn sm ghost" data-edapart="${c.id.esc()}">Not the same song</span>""" else ""}</div></div>""")
        }
        append("""<div class="tiny muted" style="margin-top:12px;line-height:1.6">The copy shown in lists is <b>the better file, wherever it is from</b>: the higher bitrate, then bit depth; on a tie the album’s copy, then its extra, a single’s, a compilation’s or box set’s, a live album’s. Versions and lyrics are shared only between copies of the <b>same MusicBrainz recording</b> — never with a copy joined by sound or by you.</div>""")
        if (edPicking) {
            append("""<div class="ed-pick"><input id="ed-pq" class="input" placeholder="Search this artist’s songs" value="${edPickQ.esc()}">""")
            val here = d.copies.map { it.id }.toSet()
            val hits = edPickHits.filter { it.id !in here }.take(8)
            if (hits.isEmpty()) append("""<div class="tiny muted" style="padding:8px 4px">${if (edPickQ.isBlank()) "Type a title." else "Nothing by this artist matches."}</div>""")
            for (h in hits) append("""<div class="r" data-edjoin="${h.id.esc()}">${h.title.esc()}<span>${(h.album ?: "").esc()} · ${muLen(h.lengthMs)}</span></div>""")
            append("</div>")
        }
        append("""</div><div class="mu-pfoot"><span class="btn sm" data-edpick>${if (edPicking) "Cancel" else "Same song as…"}</span><span class="spacer" style="flex:1"></span><span class="btn sm primary" data-edx>Done</span></div>""")
    }
    (document.getElementById("ed-pq") as? HTMLInputElement)?.let { it.focus(); it.setSelectionRange(it.value.length, it.value.length) }
}

private fun edSearch(scope: CoroutineScope) {
    val d = edPanelDto ?: return
    val q = edPickQ.trim()
    scope.launch {
        edPickHits = if (q.isEmpty()) emptyList() else MusicApi.browse("songs", q, emptyMap(), artist = d.artistId, everyCopy = true)?.songs.orEmpty()
        edPaint()
    }
}

private fun edPanelClick(t: Element, scope: CoroutineScope) {
    if (t.closest("[data-edx]") != null) { edCloseCopies(); return }
    val d = edPanelDto ?: return
    val shown = d.copies.firstOrNull { it.shown }?.id ?: d.trackId
    if (t.closest("[data-edpick]") != null) {
        edPicking = !edPicking
        edPickQ = if (edPicking) d.title.substringBefore(" (").trim() else ""
        if (edPicking) edSearch(scope) else edPaint()
        return
    }
    t.closest("[data-edapart]")?.let { b ->
        val id = b.getAttribute("data-edapart") ?: return
        scope.launch {
            if (MusicApi.sameSong(shown, id, same = false)) { muToast("Kept apart · that copy is its own song now"); edLoadCopies(scope); edAfter?.invoke() } else muToast("That didn’t save")
        }
        return
    }
    t.closest("[data-edjoin]")?.let { b ->
        val id = b.getAttribute("data-edjoin") ?: return
        scope.launch {
            if (MusicApi.sameSong(shown, id, same = true)) { edPicking = false; muToast("Joined · one song in lists from now on (you said so)"); edLoadCopies(scope); edAfter?.invoke() } else muToast("That didn’t save")
        }
    }
}

// ── the Dashboard's *Listen and decide* (FR-305-14) ──

private var edPairs: List<MusicSamePairDto> = emptyList()
private var edPairAt = 0
private var edPairTotal = 0
private var edPlayingSide: String? = null
private var edAudio: HTMLAudioElement? = null

internal fun edOpenSameSongs(scope: CoroutineScope, after: () -> Unit) {
    edModal("""<h3>Songs that may be the same</h3><p class="tiny muted">Loading…</p>""")
    scope.launch {
        edPairs = MusicApi.sameSongSuggestions() ?: return@launch edModal("""<h3>Couldn’t read the pairs</h3><div class="row" style="justify-content:flex-end"><span class="btn ghost" data-edmx>Close</span></div>""")
        edPairAt = 0; edPairTotal = edPairs.size; edPlayingSide = null
        edPaintPair(scope, after)
    }
}

private fun edPaintPair(scope: CoroutineScope, after: () -> Unit) {
    val pair = edPairs.getOrNull(edPairAt)
    if (pair == null) {
        edStopAudio()
        edModal("""<h3>Songs that may be the same</h3><p>All answered. <b>Yes</b> joins two copies into one song (<i>you said so</i>); <b>No</b> keeps them two and never asks again.</p><div class="row" style="justify-content:flex-end;margin-top:12px"><span class="btn primary" data-edmx>Done</span></div>""")
        return
    }
    fun side(s: dev.jellystructure.model.MusicPairSide, k: String) = buildString {
        val on = edPlayingSide == k
        append("""<div class="ed-pl${if (on) " on" else ""}"><div class="h">""")
        append(if (s.playable) """<span class="mu-play${if (on) " on" else ""}" data-edpl="$k">${if (on) "❚❚" else "▶"}</span>""" else """<span class="mu-play" style="opacity:.4;cursor:not-allowed" title="${(s.why ?: "").esc()}">▶</span>""")
        append("""<div><div class="t">${s.title.esc()}</div><div class="s">${listOfNotNull(s.album, KIND_LABEL[s.kind] ?: s.kind, s.lengthMs?.let { muLen(it) }).joinToString(" · ").esc()}</div></div></div><div class="ed-wave"></div>""")
        append("""<div class="s" style="margin-top:8px">${s.recording?.let { "recording “${it.esc()}” · MusicBrainz" } ?: "no MusicBrainz recording"}</div>""")
        if (!s.playable) append("""<div class="s" style="margin-top:4px;color:var(--warn)">${(s.why ?: "").esc()}</div>""")
        append("</div>")
    }
    edModal(buildString {
        append("""<div class="row center" style="gap:10px"><h3 style="margin:0">These sound the same: one song?</h3><span class="spacer"></span><span class="tiny muted">${edPairAt + 1} of $edPairTotal</span></div>""")
        append("""<div class="ed-cmp">${side(pair.a, "a")}${side(pair.b, "b")}</div>""")
        append("""<p class="tiny" style="margin-top:10px">Both start from the same second, so you hear the same bar. MusicBrainz lists two recordings; the audio says they may be one. Nothing is joined until you say so.</p>""")
        append("""<div class="ed-acts"><span class="btn primary" data-edans="yes">Yes, one song</span><span class="btn" data-edans="no">No, two songs</span><span class="spacer" style="flex:1"></span><span class="btn ghost" data-edmx>Later</span></div>""")
    }) { t ->
        t.closest("[data-edpl]")?.let { b ->
            val k = b.getAttribute("data-edpl") ?: return@let
            if (edPlayingSide == k) { edStopAudio(); edPaintPair(scope, after); return@let }
            val s = if (k == "a") pair.a else pair.b
            // The offset makes both start from the same second (b starts that much later than a).
            val startSec = (if (k == "b") pair.offsetMs.coerceAtLeast(0) else (-pair.offsetMs).coerceAtLeast(0)) / 1000.0 + 30.0
            scope.launch {
                val url = MusicApi.streamUrl(s.trackId) ?: return@launch muToast("Jellyfin can’t direct-play that here")
                edStopAudio()
                val audio = (document.createElement("audio") as HTMLAudioElement).also { edAudio = it }
                audio.src = url
                audio.addEventListener("loadedmetadata") { audio.currentTime = startSec }
                audio.play()
                edPlayingSide = k; edPaintPair(scope, after)
            }
        }
        t.closest("[data-edans]")?.let { b ->
            val yes = b.getAttribute("data-edans") == "yes"
            scope.launch {
                if (!MusicApi.sameSong(pair.a.trackId, pair.b.trackId, same = yes)) return@launch muToast("That didn’t save")
                edStopAudio(); edPlayingSide = null; edPairAt++
                muToast(if (yes) "One song · joined (you said so)" else "Two songs · never asked again")
                edPaintPair(scope, after); after()
            }
        }
    }
}

private fun edStopAudio() { edAudio?.pause(); edAudio = null; edPlayingSide = null }

// ── one modal for both ──

private fun edModal(html: String, onClick: ((Element) -> Unit)? = null) {
    document.getElementById("ed-modal")?.remove()
    val m = document.createElement("div") as HTMLElement
    m.id = "ed-modal"; m.className = "mu-modal ed-modal on"
    m.innerHTML = """<div class="card">$html</div>"""
    document.body?.appendChild(m)
    m.addEventListener("click") { ev ->
        val t = ev.target as? Element ?: return@addEventListener
        if (t == m || t.closest("[data-edmx]") != null) { edStopAudio(); edCloseModal(); return@addEventListener }
        onClick?.invoke(t)
    }
}

internal fun edCloseModal() { document.getElementById("ed-modal")?.remove() }
