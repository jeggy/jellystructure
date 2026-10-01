package dev.jellystructure.ui

import dev.jellystructure.api.MusicApi
import dev.jellystructure.model.MusicVersionPanelDto
import dev.jellystructure.model.MusicVersionTypeDto
import dev.jellystructure.model.MusicVersionTypesDto
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/*
 * Phase 292 — a song's version on the admin's pages: the chips (direction A, words, `vr-*` from
 * design/app/versions.css), the side panel, *Set version…* on a selection, and Metadata → Versions. Every answer
 * comes from the server (`/api/music/track/{id}/versions`, `/api/music/version-types`); this file only renders.
 */

/** The household's types as the last page answer carried them (chip names and colours). */
internal var vrTypes: List<MusicVersionTypeDto> = emptyList()

internal fun vrRemember(types: List<MusicVersionTypeDto>) { if (types.isNotEmpty()) vrTypes = types }

private fun vrType(key: String) = vrTypes.firstOrNull { it.key == key }

/** FR-292-6 — one chip per type after the title, up to [fold]; more fold into a dashed *+N*. A key with no type draws nothing. */
internal fun vrChips(keys: List<String>, fold: Int = 3): String {
    val known = keys.mapNotNull { vrType(it) }
    if (known.isEmpty()) return ""
    val shown = known.take(fold)
    val more = known.drop(fold)
    return shown.joinToString("") { """<span class="vr-b" style="--c:${it.color.esc()}">${it.chip.esc()}</span>""" } +
        if (more.isEmpty()) "" else """<span class="vr-more" title="${more.joinToString(", ") { it.name }.esc()}">+${more.size}</span>"""
}

/** The chips as a target that opens the panel, or *＋ Version* on hover for a song with none (FR-292-7). */
internal fun vrBadges(trackId: String, keys: List<String>, editable: Boolean = true): String {
    val chips = vrChips(keys)
    if (!editable) return if (chips.isEmpty()) "" else """<span class="vr-bs" style="cursor:default" aria-label="Version: ${keys.mapNotNull { vrType(it)?.name }.joinToString(", ").esc()}">$chips</span>"""
    return if (chips.isEmpty()) """<span class="vr-add" data-ver="${trackId.esc()}" title="Version — none yet">＋ Version</span>"""
    else """<span class="vr-bs" data-ver="${trackId.esc()}" title="Version — click to change">$chips</span>"""
}

// ── the side panel (FR-292-7) ──

private var vrOpen: String? = null
private var vrPanelDto: MusicVersionPanelDto? = null
private var vrAfter: (() -> Unit)? = null

internal fun vrOpenPanel(scope: CoroutineScope, trackId: String, after: () -> Unit) {
    vrClosePanel()
    vrOpen = trackId; vrAfter = after
    val scrim = document.createElement("div") as HTMLElement
    scrim.id = "vr-scrim"; scrim.className = "mu-scrim on"
    scrim.addEventListener("click") { vrClosePanel() }
    val panel = document.createElement("aside") as HTMLElement
    panel.id = "vr-panel"; panel.className = "mu-panel vr-panel on"
    panel.setAttribute("aria-label", "Version")
    panel.innerHTML = """<div class="mu-ph"><h3>Version</h3><span class="spacer"></span><span class="btn sm ghost" data-vpx>✕</span></div><div class="mu-pbody"><span class="muted tiny">Loading…</span></div>"""
    panel.addEventListener("click") { ev -> vrPanelClick(ev.target as? Element ?: return@addEventListener, scope) }
    document.body?.appendChild(scrim); document.body?.appendChild(panel)
    scope.launch {
        val dto = MusicApi.versionPanel(trackId) ?: return@launch muToast("Couldn’t read this song’s version")
        vrPanelDto = dto; vrPaint()
    }
}

private fun vrClosePanel() {
    vrOpen = null; vrPanelDto = null
    document.getElementById("vr-scrim")?.remove(); document.getElementById("vr-panel")?.remove()
}

private fun vrSourceText(src: List<String>, removed: Boolean): String = when {
    removed -> "removed by you"
    else -> src.joinToString(" · ") {
        when (it) { "musicbrainz" -> "from MusicBrainz"; "title" -> "from the title"; "user" -> "set by you"; "session" -> "with Session"; else -> it }
    }
}

private fun vrPaint() {
    val panel = document.getElementById("vr-panel") as? HTMLElement ?: return
    val d = vrPanelDto ?: return
    val shown = d.rows.filter { it.on }.map { it.key }
    panel.innerHTML = buildString {
        append("""<div class="mu-ph"><h3>Version</h3><span class="spacer"></span><span class="btn sm ghost" data-vpx>✕</span></div><div class="mu-pbody">""")
        append("""<div class="vr-ph"><div class="ttl">${d.title.esc()}</div><div class="tiny muted">${listOfNotNull(d.artist.takeIf { it.isNotBlank() }, d.album, d.position?.let { "track $it" }).joinToString(" · ").esc()}</div>""")
        append("""<div class="vr-pv">${if (shown.isEmpty()) """<span class="tiny muted">No version — an ordinary recording shows nothing.</span>""" else vrChips(shown, 9)}</div></div>""")
        if (!d.matched) append("""<div class="mu-note"><div class="t"><b>Not matched yet.</b> Only the title can say anything. A match adds MusicBrainz’s answer, and what you tick here goes with the song.</div></div>""")
        if (d.noWords) append("""<div class="mu-note"><div class="t"><b>No words — MusicBrainz.</b> A piece that was never sung has no version, and it is never given lyrics.</div></div>""")
        if (d.otherAlbums.isNotEmpty()) append("""<div class="mu-note"><div class="t"><b>The same recording is also on ${d.otherAlbums.size} other album${if (d.otherAlbums.size == 1) "" else "s"} — the change applies there too:</b> ${d.otherAlbums.joinToString(" · ") { """<a href="#/album/${it.albumId}">${it.title.esc()}</a>""" }}</div></div>""")
        if (d.lyricsBesideNoSinging) append("""<div class="mu-note w"><div class="t"><b>Lyrics beside a song with no singing.</b> They are listed on <a href="#/">the Dashboard</a>; only you remove them there.</div></div>""")
        append("""<div class="vr-rows">""")
        for (r in d.rows) {
            val src = vrSourceText(r.sources, r.removed)
            val cls = if (r.removed) " rmv" else if (r.sources == listOf("user")) " you" else ""
            append("""<div class="vr-row${if (r.on) " on" else ""}${if (r.removed) " rm" else ""}" data-vt="${r.key}" data-on="${r.on}" style="--c:${r.color.esc()}"><span class="bx">${if (r.on) "✓" else ""}</span><span class="nm">${r.name.esc()}</span><span class="src$cls">${src.esc()}</span><span class="mn">${r.meaning.esc()}</span></div>""")
        }
        append("</div>")
        d.instrumentalOf?.let { o ->
            append("""<div class="mu-sec">Instrumental version of</div><div class="vr-ofp">""")
            if (o.trackId != null && o.albumId != null) append("""<a href="#/album/${o.albumId}">${o.title.esc()}</a><span class="tiny muted">from MusicBrainz’s link</span>""")
            else append("""<i>${o.title.esc()}</i><span class="tiny muted">${o.artist?.let { "${it.esc()} · " } ?: ""}not in the library</span>""")
            append("</div>")
        }
        append("</div>")
        append("""<div class="mu-pfoot"><span class="tiny muted">Saved as you tick · kept across runs</span><span class="spacer" style="flex:1"></span>""")
        if (d.hasChoices) append("""<span class="btn sm ghost" data-vauto>Back to automatic</span>""")
        append("""<span class="btn sm primary" data-vpx>Done</span></div>""")
    }
}

private fun vrPanelClick(t: Element, scope: CoroutineScope) {
    if (t.closest("[data-vpx]") != null) { vrClosePanel(); return }
    val id = vrOpen ?: return
    if (t.closest("[data-vauto]") != null) {
        scope.launch {
            vrPanelDto = MusicApi.resetVersions(id) ?: return@launch muToast("That didn’t save")
            vrPaint(); muToast("Back to automatic · MusicBrainz and the title decide again"); vrAfter?.invoke()
        }
        return
    }
    t.closest("[data-vt]")?.let { row ->
        val key = row.getAttribute("data-vt") ?: return
        val on = row.getAttribute("data-on") != "true"
        scope.launch {
            vrPanelDto = MusicApi.setVersion(id, key, on) ?: return@launch muToast("That didn’t save")
            vrPaint(); vrAfter?.invoke()
        }
    }
}

// ── *Set version…* (FR-292-8, FR-292-12) ──

internal fun vrOpenBulk(scope: CoroutineScope, trackIds: List<String>, done: () -> Unit) {
    if (trackIds.isEmpty()) return
    scope.launch {
        val pv = MusicApi.versionPreview(trackIds) ?: return@launch muToast("The server didn’t answer")
        val pick = LinkedHashMap<String, String>()   // key → leave · add · remove
        document.getElementById("vr-modal")?.remove()
        val m = document.createElement("div") as HTMLElement
        m.id = "vr-modal"; m.className = "mu-modal on"
        fun paint() {
            m.innerHTML = buildString {
                append("""<div class="card" style="max-width:600px"><h3>Set version on ${pv.songs} song${if (pv.songs == 1) "" else "s"}</h3><p>Add or remove a type on all of them. What you leave alone stays as it is on each song.</p><div class="vr-bk">""")
                for (ty in vrTypes) {
                    val c = pv.counts[ty.key] ?: 0
                    val p = pick[ty.key] ?: "leave"
                    append("""<div class="vr-bkr"><span class="vr-dot" style="--c:${ty.color.esc()}"></span><span class="nm">${ty.name.esc()}</span><span class="tiny muted">${if (c > 0) "$c of ${pv.songs} have it" else "none have it"}</span><span class="seg">""")
                    for ((v, l) in listOf("leave" to "Leave", "add" to "Add", "remove" to "Remove")) append("""<span data-vbp="${ty.key}:$v" class="${if (p == v) "on" else ""}">$l</span>""")
                    append("</span></div>")
                }
                append("</div>")
                if (pick["session"] == "add") append("""<div class="tiny muted" style="margin-top:8px">Adding Session adds Live with it.</div>""")
                if (pv.otherCopies > 0) append("""<div class="mu-note" style="margin:12px 0 0"><div class="t">${pv.otherCopies} other cop${if (pv.otherCopies == 1) "y" else "ies"} of these recordings${if (pv.otherAlbums > 0) ", on ${pv.otherAlbums} other album${if (pv.otherAlbums == 1) "" else "s"}," else ""} change with them — the same MusicBrainz recording carries one answer.</div></div>""")
                append("""<div class="row" style="justify-content:flex-end;gap:8px;margin-top:14px"><span class="btn ghost" data-vbx>Cancel</span><span class="btn primary" data-vbgo>Apply to ${pv.songs}</span></div></div>""")
            }
        }
        paint()
        m.addEventListener("click") { ev ->
            val t = ev.target as? Element ?: return@addEventListener
            when {
                t == m || t.closest("[data-vbx]") != null -> m.remove()
                t.closest("[data-vbp]") != null -> {
                    val (k, v) = t.closest("[data-vbp]")!!.getAttribute("data-vbp")!!.split(':').let { it[0] to it[1] }
                    pick[k] = v
                    // Dev review 7 — Add Session adds Live; Remove Live never removes Session.
                    if (k == "session" && v == "add" && (pick["live"] ?: "leave") == "leave") pick["live"] = "add"
                    paint()
                }
                t.closest("[data-vbgo]") != null -> {
                    val add = pick.filterValues { it == "add" }.keys.toList()
                    val remove = pick.filterValues { it == "remove" }.keys.toList()
                    m.remove()
                    if (add.isEmpty() && remove.isEmpty()) { muToast("Nothing to change"); return@addEventListener }
                    scope.launch { muToast(MusicApi.versionBulk(trackIds, add, remove) ?: "That didn’t save"); done() }
                }
            }
        }
        document.body?.appendChild(m)
    }
}

// ── Metadata → Versions (FR-292-13) ──

internal fun vrMetadataHtml(d: MusicVersionTypesDto): String = buildString {
    vrRemember(d.types)
    append("""<div class="note blue" style="margin-bottom:16px;">A <b>version</b> belongs to a song — one set of types per MusicBrainz recording, so the same recording on an album, a best-of and a box set carries one answer. Found automatically (MusicBrainz, then the title) and yours to change on any song; what you change is kept across runs. The nine are fixed for now — the colour and the meaning are yours, and they reach Ravilo on its next fetch.</div>""")
    append("""<div class="mu-scroll"><table class="mu-tbl vr-mt"><thead><tr><th>Colour</th><th>Type</th><th>Means</th><th>Found from</th><th>Songs</th><th>By you</th></tr></thead><tbody>""")
    for (t in d.types) {
        val byYou = listOfNotNull(t.setByYou.takeIf { it > 0 }?.let { "$it set" }, t.removedByYou.takeIf { it > 0 }?.let { "$it removed" }).joinToString(" · ").ifEmpty { "—" }
        append("""<tr data-filter-name="${t.name.lowercase().esc()}"><td><span class="vr-sw" style="--c:${t.color.esc()}" data-vsw="${t.key}" title="Change the colour"></span></td><td>${vrChips(listOf(t.key))}</td>""")
        append("""<td><input class="vr-mi" data-vmi="${t.key}" value="${t.meaning.esc()}"></td><td class="tiny muted">${t.foundFrom.esc()}</td>""")
        append("""<td class="num"><a href="#/library?kind=music&mview=songs&f.version=${t.key}">${t.songs} song${if (t.songs == 1) "" else "s"} →</a></td><td class="tiny muted">${byYou.esc()}</td></tr>""")
    }
    append("""<tr><td></td><td><span class="tiny" style="font-weight:600">No version</span></td><td class="tiny muted">An ordinary recording — and a piece that was never sung</td><td></td><td class="num"><a href="#/library?kind=music&mview=songs&f.version=none">${d.noVersion} song${if (d.noVersion == 1) "" else "s"} →</a></td><td></td></tr>""")
    append("</tbody></table></div>")
    append("""<div class="tiny muted" style="margin-top:12px;line-height:1.6;max-width:760px">A Session is also Live: Live’s count and every filter include the sessions, even one whose Live you took away.</div>""")
}

internal fun vrWireMetadata(content: HTMLElement, scope: CoroutineScope, palette: () -> List<String>, repaint: (MusicVersionTypesDto) -> Unit) {
    content.onclick = { ev ->
        (ev.target as? Element)?.closest("[data-vsw]")?.let { sw ->
            val key = sw.getAttribute("data-vsw")!!
            val now = vrTypes.firstOrNull { it.key == key }?.color?.lowercase()
            val p = palette()
            val next = p[(p.indexOfFirst { it.lowercase() == now } + 1).mod(p.size)]
            scope.launch { MusicApi.patchVersionType(key, color = next)?.let(repaint) ?: muToast("That didn’t save") }
        }
        null
    }
    // A property, not a listener: the Metadata tabs share one content element, so a listener would pile up.
    content.onchange = { ev ->
        ((ev.target as? Element)?.closest("[data-vmi]") as? HTMLInputElement)?.let { inp ->
            val key = inp.getAttribute("data-vmi")!!
            val text = inp.value.trim()
            if (text.isNotEmpty()) scope.launch { if (MusicApi.patchVersionType(key, meaning = text) != null) muToast("Meaning saved · ${vrType(key)?.name ?: key}") else muToast("That didn’t save") }
        }
        null
    }
}
