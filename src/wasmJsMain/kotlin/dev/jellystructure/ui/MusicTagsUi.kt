package dev.jellystructure.ui

import dev.jellystructure.api.JsTag
import dev.jellystructure.api.MetadataApi
import dev.jellystructure.api.MusicApi
import dev.jellystructure.model.MusicAlbumPageDto
import dev.jellystructure.model.MusicJsTagBulkRequest
import dev.jellystructure.model.MusicSongFilter
import dev.jellystructure.model.MusicTrackRow
import dev.jellystructure.model.TagScope
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/*
 * DEB-3704 (DEB-3701 FR-6, FR-8, FR-9) — the admin's controls for jellystructure tags on albums and songs: the album page's
 * chip row (Genres tab), the Tracks tab's dots and add menu, the selection bar's Tag… / Untag…, and the tags page's list of
 * gone rows. Every control calls a server route; nothing here derives a tag or keeps one. The page repaints from the
 * answer, like the rest of the admin. Built from controls the admin already has: `chip`, `tag-dot`, `menu-wrap`, `vr-bk`.
 */

/** The defined tags (colour and scope), read when a page that shows tags opens. */
private var mtDefs: List<JsTag> = emptyList()

internal suspend fun mtLoadDefs() { MetadataApi.getAllJsTags()?.let { mtDefs = it } }

private fun mtColor(tag: String): String = mtDefs.firstOrNull { it.name == tag }?.color ?: "#6b7280"

private fun mtDot(tag: String): String = """<span class="tag-dot" style="background:${mtColor(tag).esc()}"></span>"""

/** FR-3 — the tags offered for music: defined, used for Music, and not on the subject yet. */
private fun mtOffer(have: List<String>): List<JsTag> = mtDefs.filter { TagScope.applies(it.scopes, TagScope.MUSIC) && it.name !in have }

private fun mtOutOfScope(tag: String): Boolean = mtDefs.firstOrNull { it.name == tag }?.let { !TagScope.applies(it.scopes, TagScope.MUSIC) } == true

private fun mtItem(action: String, kind: String, id: String, tag: String, label: String, dot: Boolean): String =
    """<div class="menu-item" data-jt="$action" data-kind="$kind" data-id="${id.esc()}" data-tag="${tag.esc()}"><span class="mi-ic">${if (dot) mtDot(tag) else "✕"}</span><span>${label.esc()}</span></div>"""

private fun mtAddMenu(kind: String, id: String, have: List<String>, trigger: String, cls: String, css: String): String {
    val offer = mtOffer(have)
    return """<span class="menu-wrap" style="display:inline-block;vertical-align:middle"><span class="$cls menu-btn" style="$css">$trigger</span><div class="menu">""" +
        (if (offer.isEmpty()) """<div class="menu-item" style="opacity:.55;cursor:default"><span>${if (mtDefs.none { TagScope.applies(it.scopes, TagScope.MUSIC) }) "No tag is used for Music yet — make one in Metadata → Tags" else "Every tag is on already"}</span></div>"""
        else offer.joinToString("") { mtItem("add", kind, id, it.name, it.name, dot = true) }) + "</div></span>"
}

/** FR-9 — the album page's *Jellystructure tags* section, Genres tab: a chip per tag with a menu, then *＋ Add tag*. */
internal fun mtAlbumSection(p: MusicAlbumPageDto): String = buildString {
    val id = p.album.id
    append("""<div class="mu-sec">Jellystructure tags</div><div class="mu-gchips">""")
    for (t in p.jsTags) {
        // FR-3: a tag the album already carries outside its tag's scope keeps its chip and its menu.
        val oos = mtOutOfScope(t)
        append("""<span class="menu-wrap" style="display:inline-block"><span class="chip menu-btn" style="cursor:pointer${if (oos) ";border-style:dashed" else ""}"${if (oos) """ title="outside this tag's scope"""" else ""}>${mtDot(t)} ${t.esc()} <span class="caret">▾</span></span><div class="menu">""")
        append(mtItem("apply", "album", id, t, "Apply to all songs in album", dot = false).replace("✕", "⧉"))
        append(mtItem("rm", "album", id, t, "Remove", dot = false))
        append("</div></span>")
    }
    append(mtAddMenu("album", id, p.jsTags, "＋ Add tag", "chip", "border-style:dashed;cursor:pointer"))
    append("</div>")
    append("""<div class="tiny muted" style="margin-top:8px;line-height:1.6;">Kept in jellystructure only — never written into the files or sent to Jellyfin. An album’s tags and its songs’ tags are separate; <i>Apply to all songs in album</i> copies one once.</div>""")
}

/** FR-9 — the Tracks tab: the song's own tags as dots after the title, and the add menu (a click on the dots, or on *＋*). */
internal fun mtTrackCell(t: MusicTrackRow): String {
    val dots = t.jsTags.joinToString("") { """<span title="${it.esc()}" style="margin-right:3px">${mtDot(it)}</span>""" }
    val remove = t.jsTags.joinToString("") { mtItem("rm", "track", t.id, it, "Remove ${it}", dot = false) }
    val offer = mtOffer(t.jsTags)
    val trigger = if (t.jsTags.isEmpty()) """<span class="tiny muted" style="cursor:pointer" title="Add a tag">＋</span>""" else dots
    return """<span class="menu-wrap" style="display:inline-block;vertical-align:middle;margin-left:6px"><span class="menu-btn" style="cursor:pointer">$trigger</span><div class="menu">""" +
        offer.joinToString("") { mtItem("add", "track", t.id, it.name, it.name, dot = true) } + remove +
        (if (offer.isEmpty() && remove.isEmpty()) """<div class="menu-item" style="opacity:.55;cursor:default"><span>No tag is used for Music yet</span></div>""" else "") + "</div></span>"
}

/**
 * The album page's tag clicks. True when [t] was one: *add*, *remove*, and FR-6's *Apply to all songs in album*, whose
 * confirm names the count. [reload] repaints the page from the server's answer.
 */
internal fun mtAlbumClick(t: Element, ev: org.w3c.dom.events.Event, scope: CoroutineScope, reload: () -> Unit): Boolean {
    val el = t.closest("[data-jt]") ?: return false
    ev.preventDefault()
    val kind = el.getAttribute("data-kind") ?: return true
    val id = el.getAttribute("data-id") ?: return true
    val tag = el.getAttribute("data-tag") ?: return true
    when (el.getAttribute("data-jt")) {
        "add" -> scope.launch { val r = MusicApi.jsTagAdd(kind, id, tag); if (r.ok) reload() else muToast(r.message) }
        "rm" -> scope.launch { val r = MusicApi.jsTagRemove(kind, id, tag); if (r.ok) reload() else muToast(r.message) }
        "apply" -> scope.launch {
            val pv = MusicApi.jsTagApplyPreview(id, tag) ?: return@launch muToast("The server didn’t answer")
            if (pv.songs == 0) return@launch muToast("This album has no songs in the library")
            muModal("""<h3>Add ${tag.esc()} to ${pv.songs} song${if (pv.songs == 1) "" else "s"}?</h3>
                <p class="tiny muted" style="line-height:1.6">Each song gets the tag as its own${if (pv.already > 0) " · ${pv.already} already ${if (pv.already == 1) "has" else "have"} it and ${if (pv.already == 1) "is" else "are"} left as it is" else ""}. Removing it from the album later leaves the songs’ copies, and a song added to the album later does not get it until you press this again.</p>
                <div class="row" style="justify-content:flex-end;gap:8px;margin-top:12px;"><span class="btn ghost" data-m="no">Cancel</span><span class="btn primary" data-m="go">Add to ${pv.songs}</span></div>""") {
                scope.launch { val r = MusicApi.jsTagApply(id, tag); muToast(r.message); if (r.ok) reload() }
            }
        }
    }
    return true
}

/**
 * FR-9 — *Tag…* and *Untag…* on a selection, in the version ticks' bulk shape: a list of the tags with *N of M have it*
 * and a Leave / Add (or Remove) pick each, over the ids of the view or, for Songs, the filter itself (FR-292-16).
 */
internal fun mtOpenBulk(scope: CoroutineScope, add: Boolean, view: String, albumIds: List<String>, trackIds: List<String>, filter: MusicSongFilter?, done: () -> Unit) {
    scope.launch {
        mtLoadDefs()
        val req = MusicJsTagBulkRequest(view = view, albumIds = albumIds, trackIds = trackIds, filter = filter)
        val pv = MusicApi.jsTagPreview(req) ?: return@launch muToast("The server didn’t answer")
        val noun = if (view == "songs") "song" else "album"
        val options = if (add) mtDefs.filter { TagScope.applies(it.scopes, TagScope.MUSIC) } else mtDefs.filter { (pv.counts[it.name] ?: 0) > 0 }
        if (pv.subjects == 0) return@launch muToast("Nothing selected is in the library")
        if (options.isEmpty()) return@launch muToast(if (add) "No tag is used for Music yet — make one in Metadata → Tags" else "None of these carries a tag")
        val picked = LinkedHashSet<String>()
        document.getElementById("jt-modal")?.remove()
        val m = document.createElement("div") as HTMLElement
        m.id = "jt-modal"; m.className = "mu-modal on"
        val verb = if (add) "Add" else "Remove"
        fun paint() {
            m.innerHTML = buildString {
                append("""<div class="card" style="max-width:560px"><h3>${if (add) "Tag" else "Untag"} ${pv.subjects} $noun${if (pv.subjects == 1) "" else "s"}</h3>""")
                append("""<p>${if (add) "Add a tag to all of them." else "Take a tag off all of them."} What you leave alone stays as it is on each $noun.</p><div class="vr-bk">""")
                for (d in options) {
                    val c = pv.counts[d.name] ?: 0
                    val on = d.name in picked
                    append("""<div class="vr-bkr"><span class="tag-dot" style="background:${d.color.esc()}"></span><span class="nm">${d.name.esc()}</span><span class="tiny muted">${if (c > 0) "$c of ${pv.subjects} have it" else "none have it"}</span><span class="seg">""")
                    append("""<span data-jtp="${d.name.esc()}:leave" class="${if (!on) "on" else ""}">Leave</span><span data-jtp="${d.name.esc()}:go" class="${if (on) "on" else ""}">$verb</span>""")
                    append("</span></div>")
                }
                append("""</div><div class="row" style="justify-content:flex-end;gap:8px;margin-top:14px;"><span class="btn ghost" data-m="no">Cancel</span><span class="btn primary" data-m="go">${if (picked.isEmpty()) "Nothing picked" else "$verb ${picked.size} tag${if (picked.size == 1) "" else "s"} on ${pv.subjects}"}</span></div></div>""")
            }
        }
        paint()
        document.body?.appendChild(m)
        m.addEventListener("click") { ev ->
            val t = ev.target as? Element ?: return@addEventListener
            when {
                t == m || t.closest("[data-m=no]") != null -> m.remove()
                t.closest("[data-jtp]") != null -> {
                    val (name, v) = (t.closest("[data-jtp]")!!.getAttribute("data-jtp") ?: return@addEventListener).let { it.substringBeforeLast(':') to it.substringAfterLast(':') }
                    if (v == "go") picked += name else picked -= name
                    paint()
                }
                t.closest("[data-m=go]") != null -> {
                    if (picked.isEmpty()) return@addEventListener muToast("Pick a tag first")
                    m.remove()
                    scope.launch {
                        val r = MusicApi.jsTagBulk(req.copy(add = if (add) picked.toList() else emptyList(), remove = if (add) emptyList() else picked.toList()))
                        muToast(r.message); if (r.ok) done()
                    }
                }
            }
        }
    }
}

/** FR-8 — a tag's songs and albums that are no longer in the library, each with a manual *Remove*. */
internal suspend fun mtOpenGone(scope: CoroutineScope, tag: String, changed: () -> Unit) {
    val rows = MusicApi.jsTagGone(tag)
    document.getElementById("jt-modal")?.remove()
    val m = document.createElement("div") as HTMLElement
    m.id = "jt-modal"; m.className = "mu-modal on"
    m.innerHTML = buildString {
        append("""<div class="card" style="max-width:620px"><h3>${tag.esc()} · ${tagGoneText(rows.size).esc()}</h3>""")
        append("""<p class="tiny muted" style="line-height:1.6">They keep the tag in case the file comes back. Nothing removes it except you.</p>""")
        if (rows.isEmpty()) append("""<div class="muted tiny">Nothing left to list.</div>""")
        for (r in rows) {
            append("""<div class="row center" style="gap:8px;padding:6px 0;border-top:1px solid var(--line)"><span style="flex:1;min-width:0"><b>${r.title.esc()}</b><div class="tiny muted">${listOfNotNull(r.artist.takeIf { it.isNotBlank() }, r.album, if (r.kind == "album") "album" else null).joinToString(" · ").esc()}</div></span>""")
            append("""<span class="btn sm ghost" data-gone-rm="${r.id.esc()}" data-kind="${r.kind.esc()}">Remove</span></div>""")
        }
        append("""<div class="row" style="justify-content:flex-end;margin-top:12px"><span class="btn ghost" data-m="no">Close</span></div></div>""")
    }
    document.body?.appendChild(m)
    m.addEventListener("click") { ev ->
        val t = ev.target as? Element ?: return@addEventListener
        if (t == m || t.closest("[data-m=no]") != null) { m.remove(); return@addEventListener }
        val rm = t.closest("[data-gone-rm]") ?: return@addEventListener
        scope.launch {
            val r = MusicApi.jsTagRemove(rm.getAttribute("data-kind") ?: "track", rm.getAttribute("data-gone-rm") ?: return@launch, tag)
            muToast(if (r.ok) "Removed" else r.message)
            m.remove(); changed()
        }
    }
}
