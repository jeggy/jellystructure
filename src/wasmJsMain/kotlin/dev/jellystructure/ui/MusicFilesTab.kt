package dev.jellystructure.ui

import dev.jellystructure.model.MusicFileCell
import dev.jellystructure.model.MusicFilesDto
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * Phase 284 (FR-284-5/6) — the Files tab, one component for the Album page (songs) and the Book page (parts):
 * Picard's pending-changes idiom — one row per file, what the file says, and where this page states something else,
 * the page's value beneath it with the mark for what will happen. The design is `design/app/files-tab.js`; the rules
 * live in `design/app/music.css` (`ft-*`), which the served page loads.
 */
private const val SEED_GLYPH = """<svg viewBox="0 0 11 12" width="10" height="11"><rect x="1" y="5" width="9" height="6.2" rx="1.6" fill="currentColor"/><path d="M3.1 5V3.5a2.4 2.4 0 0 1 4.8 0V5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>"""

private fun ftCell(x: MusicFileCell, seeding: Boolean, removeJunk: Boolean): String {
    val f = x.file?.let { it.esc() } ?: """<span class="ft-none">—</span>"""
    return when {
        seeding && (x.mark == "write" || x.mark == "noreach" || x.mark == "seed") -> """<td class="ft-c seed"><span class="fv">$f</span><span class="pv">$SEED_GLYPH left as it is</span></td>"""
        x.mark == "write" -> """<td class="ft-c w"><span class="fv">$f</span><span class="pv">→ ${(x.page ?: "").esc()}</span></td>"""
        x.mark == "noreach" -> """<td class="ft-c a" title="Written for every other reader; Jellyfin reads no MusicBrainz ids from WMA"><span class="fv">$f</span><span class="pv">→ ${(x.page ?: "").esc()} · not for Jellyfin</span></td>"""
        x.mark == "fileonly" -> """<td class="ft-c fo" title="The file has a value this page doesn’t — kept"><span class="fv">$f</span><span class="pv">file only · kept</span></td>"""
        x.mark == "junk" && removeJunk -> """<td class="ft-c jr"><span class="fv mono">$f</span><span class="pv">→ removed</span></td>"""
        x.mark == "junk" -> """<td class="ft-c j"><span class="fv mono">$f</span><span class="pv">kept</span></td>"""
        x.mark == "hold" -> """<td class="ft-c h"><span class="fv">$f</span><span class="pv">locked · ours says ${(x.page ?: "").esc()}</span></td>"""
        x.mark == "side" -> """<td class="ft-c sd"><span class="fv">$f</span></td>"""
        else -> """<td class="ft-c${if (x.mark == "empty") " e" else ""}"><span class="fv">$f</span></td>"""
    }
}

/** The tab's HTML. [after] is the line a just-finished write left (or null). */
internal fun muFilesHtml(d: MusicFilesDto, embed: Boolean, removeJunk: Boolean, after: String?): String = buildString {
    val book = d.kind == "book"
    val noun = if (book) "part" else "file"
    val n = d.rows.size
    val nd = d.differCount
    val written = d.writtenAt?.let { "written by jellystructure ${dev.jellystructure.formatWeekdayClock(it.toString())}" } ?: "never written"
    append("""<div class="ft-head"><b>Tags in $n $noun${if (n == 1) "" else "s"}</b><span>·</span><span>${if (nd > 0) "$nd say${if (nd == 1) "s" else ""} something different from this page" else "all say what this page says"}</span><span>·</span><span>$written</span>""")
    if (d.seedingCount > 0) append("""<span class="ft-seedl">$SEED_GLYPH ${if (d.seedingCount == n) "every file is seeding and will be left alone" else "${d.seedingCount} file${if (d.seedingCount > 1) "s are" else " is"} seeding and will be left alone"}</span>""")
    if (!d.taggerAvailable) append("""<span class="ft-seedl">This server has no tagger — the image is missing python3-mutagen</span>""")
    d.lidarr?.let { append("""<span class="ft-seedl" title="Read from Lidarr; a match changed here shows up in Lidarr as unmatched files">${it.esc()}</span>""") }
    append("</div>")
    if (d.locked && d.rows.any { r -> r.cells.values.any { it.mark == "hold" } }) {
        append("""<div class="ft-info w"><span class="badge warn">Locked</span><div><b>The files disagree with this locked album.</b> A lock is the one thing that holds our version against the file.</div><span class="btn sm ghost" data-ft="takefile">Take the file’s</span><span class="btn sm" data-ft="writeours">Write ours</span></div>""")
    }
    if (!book && d.wmaCount > 0) append("""<div class="ft-info a"><span class="badge warn">WMA</span><div><b>Every other reader sees these tags; Jellyfin won’t see the ids.</b> Its tag library has no mapping for MusicBrainz ids in WMA. Convert makes new files with the full set.</div></div>""")
    append("""<div class="mu-scroll"><table class="ft-grid"><thead><tr class="g"><th rowspan="2" class="ft-file">File</th>""")
    d.columns.forEach { g -> append("""<th colspan="${g.columns.size}">${g.label.esc()}</th>""") }
    append("</tr><tr>")
    d.columns.forEach { g -> g.columns.forEach { c -> append("<th>${c.label.esc()}</th>") } }
    append("</tr></thead><tbody>")
    for (r in d.rows) {
        append("""<tr id="ft-${r.id.esc()}" class="${if (r.error != null) "failed" else ""}${if (r.differs) " d" else ""}"><td class="ft-file"><span class="mono">${r.file.esc()}</span><span class="ft-fmt${if (r.wma) " w" else ""}">${r.format.esc()}</span>${if (r.seeding) """<span class="ft-seed">$SEED_GLYPH seeding</span>""" else ""}${r.error?.let { """<span class="tiny" style="color:var(--warn)">${it.esc()}</span>""" } ?: ""}</td>""")
        d.columns.forEach { g -> g.columns.forEach { c -> append(ftCell(r.cells[c.key] ?: MusicFileCell(null, null, "empty"), r.seeding, removeJunk)) } }
        append("</tr>")
    }
    append("</tbody></table></div>")
    append("""<div class="ft-legend"><span><i class="w"></i>will write</span><span><i class="fo"></i>file only · kept</span>${if (!book) """<span><i class="a"></i>written · Jellyfin won’t read it from WMA</span>""" else ""}<span>$SEED_GLYPH seeding · left as it is</span>${if (!book) """<span class="muted">Recording = the MusicBrainz recording · Release = the release this track is on. Four more ids are written with them.</span>""" else ""}</div>""")
    val count = if (nd > 0) nd else n - d.seedingCount
    append("""<div class="ft-acts"><span class="btn primary" data-ft="write"${d.reason?.let { """ aria-disabled="true" title="${it.esc()}"""" } ?: ""}>Write tags to $count $noun${if (count == 1) "" else "s"}</span>""")
    d.reason?.let { append("""<span class="tiny muted">${it.esc()}</span>""") }
    if (d.hasCover && !book) append("""<label class="ft-chk"><input type="checkbox" data-ft="embed"${if (embed) " checked" else ""}> Also embed the cover</label>""")
    if (d.junk.isNotEmpty()) append("""<label class="ft-chk"><input type="checkbox" data-ft="rmjunk"${if (removeJunk) " checked" else ""}> Also remove junk frames (${d.junk.joinToString(", ").esc()})</label>""")
    if (!book && d.wmaCount > 0) append("""<span class="btn" data-ft="convert">Convert and tag ${d.wmaCount}</span>""")
    append("</div>")
    after?.let { append("""<div class="ft-after"><span class="dot ok"></span> ${it.esc()}</div>""") }
    append("""<div class="tiny muted" style="margin-top:10px;line-height:1.55">${if (book)
        "The narrator goes into the composer field and the description into the comment — where Jellyfin, Audiobookshelf and a phone’s player look. Nothing is written as a side effect of a scan."
        else "What goes where: a fact about the song is a tag; the biography is <span class=\"mono\">artist.nfo</span>; our match reasoning, locks and history stay in jellystructure’s tables. A scan writes only on a new match; a file seeding in qBittorrent is never touched."}</div>""")
}

/** Wires the tab's actions inside [root]: [onWrite] gets (embed, removeJunk, take) and returns the sentence to show. */
internal fun muFilesWire(root: HTMLElement, scope: CoroutineScope, state: MusicFilesState, render: () -> Unit, onWrite: suspend (Boolean, Boolean, String?) -> String?, onConvert: (() -> Unit)? = null) {
    root.onclick = { ev ->
        val t = ev.target as? Element
        val a = t?.closest("[data-ft]")
        if (a != null && a.tagName != "INPUT") {
            when (a.getAttribute("data-ft")) {
                "write", "writeours", "takefile" -> if (a.getAttribute("aria-disabled") == null) {
                    val take = when (a.getAttribute("data-ft")) { "takefile" -> "file"; "writeours" -> "ours"; else -> null }
                    (a as? HTMLElement)?.textContent = "Writing…"
                    scope.launch { state.after = onWrite(state.embed, state.removeJunk, take); render() }
                }
                "convert" -> onConvert?.invoke()
            }
        }
    }
    root.onchange = { ev ->
        val i = ev.target as? HTMLInputElement
        when (i?.getAttribute("data-ft")) {
            "embed" -> { state.embed = i.checked; render() }
            "rmjunk" -> { state.removeJunk = i.checked; render() }
        }
    }
}

internal class MusicFilesState { var embed = false; var removeJunk = false; var after: String? = null }

/** FR-284-9 — the Tracks tab's glyph per song: file agrees · file differs · file has no ids; a link to the row in Files. */
internal fun muFileGlyph(d: MusicFilesDto?, trackId: String): String {
    val r = d?.rows?.firstOrNull { it.id == trackId } ?: return ""
    val noIds = (r.cells["rec"]?.file.isNullOrBlank())
    val (cls, g, title) = when {
        r.differs && noIds -> Triple("n", "∅", "file has no ids")
        r.differs -> Triple("d", "≠", "file differs from this page")
        else -> Triple("ok", "✓", "file agrees")
    }
    return """<a class="ft-g $cls" href="#" data-ftrow="${trackId.esc()}" title="$title — open in Files">$g</a>"""
}

internal fun muFilesHighlight(id: String) {
    val r = document.getElementById("ft-$id") as? HTMLElement ?: return
    r.classList.add("hl"); r.scrollIntoView()
    kotlinx.browser.window.setTimeout({ r.classList.remove("hl"); null }, 1600)
}
