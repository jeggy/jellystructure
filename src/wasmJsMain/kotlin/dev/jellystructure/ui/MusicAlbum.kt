@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.Router
import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.MusicApi
import dev.jellystructure.model.MusicAlbumPageDto
import dev.jellystructure.model.MusicCandidate
import dev.jellystructure.model.MusicConvertRequest
import dev.jellystructure.model.MusicFlagDto
import dev.jellystructure.model.MusicReleaseOption
import dev.jellystructure.model.MusicTrackRow
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLAudioElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/*
 * Phase 278 (FR-278-5..7) — the Album page: pagebar (External links · Re-pull · ⋯ · Save split), the head with the
 * match chip and the state's primary action, the banners (unmatched · needs you · drift · locked in Jellyfin), five
 * tabs, and *Find match…* as a side panel. Everything shown is what `GET /api/music/album/{id}/page` says; each
 * action calls the server and re-reads the page.
 */

private var alId = ""
private var alPage: MusicAlbumPageDto? = null
private var alTab = "tracks"
private var alPlaying: String? = null
private var alRecOpen: String? = null
private var alAudio: HTMLAudioElement? = null

// Find match… panel state
private var fmCands: List<Pair<MusicCandidate, String?>> = emptyList()   // candidate, fit sentence
private var fmChosen: Int? = null
private var fmReleases: List<MusicReleaseOption>? = null
private var fmRelease: Int = 0
private var fmBusy: String? = null
private var fmSound: String? = null

private val TABS = listOf("tracks" to "Tracks", "artwork" to "Artwork", "genres" to "Genres &amp; tags", "nfo" to "NFO (raw)", "history" to "History")

fun renderMusicAlbum(container: Element, scope: CoroutineScope, id: String, query: Map<String, String>) {
    alAudio?.pause(); alAudio = null
    alId = id; alPage = null; alPlaying = null; alRecOpen = null
    alTab = query["tab"]?.takeIf { t -> TABS.any { it.first == t } } ?: "tracks"
    container.innerHTML = """
        <div id="al-root">
        <div class="backrow"><a class="btn sm ghost" href="#/library?kind=music">‹ Library</a><span class="crumb" id="al-crumb"></span></div>
        <div class="pagebar" id="al-bar"><h1>…</h1></div>
        <div id="al-head"><span class="muted tiny">Loading…</span></div>
        <div id="al-banners"></div>
        <div class="tabs2" id="al-tabs">${TABS.joinToString("") { (k, l) -> """<span data-tab="$k">$l</span>""" }}</div>
        <div id="al-panel"></div>
        <audio id="mu-audio" preload="none" style="display:none"></audio>
        </div>
    """.trimIndent()
    // Listeners live on this page's own root, which the next route's render throws away with it.
    val root = document.getElementById("al-root") as? HTMLElement ?: return
    alAudio = document.getElementById("mu-audio") as? HTMLAudioElement
    alAudio?.addEventListener("ended") { alPlaying = null; alPanel(scope) }
    root.addEventListener("click") { ev -> alClick(ev.target as? Element ?: return@addEventListener, ev, scope) }
    root.addEventListener("change") { ev ->
        val inp = ev.target as? HTMLInputElement ?: return@addEventListener
        if (inp.id == "al-upload") {
            val file = muPickedFile(inp) ?: return@addEventListener
            muToast("Uploading…")
            muUpload("/api/music/album/${alId}/artwork/upload", file) { ok -> muToast(if (ok) "cover.jpg written · Jellyfin re-reads it on the next sync" else "That image couldn't be used"); alReload(scope) }
        }
    }
    alReload(scope, openFind = query["find"] == "1")
}

private fun alReload(scope: CoroutineScope, openFind: Boolean = false) {
    scope.launch {
        val p = MusicApi.albumPage(alId)
        if (p == null) {
            (document.getElementById("al-head") as? HTMLElement)?.innerHTML = """<div class="note red">This album isn't in the music library (any more).</div>"""
            return@launch
        }
        alPage = p
        alRender(scope)
        if (openFind) fmOpen(scope)
    }
}

private fun alMatched(p: MusicAlbumPageDto) = p.album.matchState == "matched"

private fun alRender(scope: CoroutineScope) {
    val p = alPage ?: return
    val a = p.album
    document.title = "Jellystructure — ${a.title}"
    (document.getElementById("al-crumb") as? HTMLElement)?.innerHTML = """<a href="#/library?kind=music">Music</a> / <a href="#/library?kind=music">Albums</a> / ${a.title.esc()}"""
    (document.getElementById("al-bar") as? HTMLElement)?.innerHTML = alBar(p)
    (document.getElementById("al-head") as? HTMLElement)?.innerHTML = alHead(p)
    (document.getElementById("al-banners") as? HTMLElement)?.innerHTML = alBanners(p)
    document.getElementById("al-bar")?.let { muWireMenus(it) }
    alPanel(scope)
}

private fun extLink(label: String, url: String, sub: String) =
    """<a class="menu-item" href="${url.esc()}" target="_blank" rel="noopener"><span class="mi-ic">↗</span><span>$label<span class="mi-sub">${sub.esc()}</span></span></a>"""

private val URL_LABELS = mapOf("discogs" to "Discogs", "wikipedia" to "Wikipedia", "wikidata" to "Wikidata", "official homepage" to "Official site", "allmusic" to "AllMusic", "bandcamp" to "Bandcamp", "streaming" to "Streaming", "purchase for download" to "Buy")

private fun alBar(p: MusicAlbumPageDto): String {
    val a = p.album
    val rg = a.releaseGroupMbid
    val locked = a.matchLocked
    val matched = alMatched(p)
    return buildString {
        append("""<h1>${a.title.esc()}${a.year?.let { """ <span class="muted">($it)</span>""" } ?: ""}</h1>""")
        if (p.type != "album") append("""<span class="mu-type">${MU_TYPE_LABEL[p.type] ?: p.type}</span>""")
        append("""<span class="spacer"></span>""")
        append("""<span class="menu-wrap"><span class="btn sm ghost menu-btn">External links <span class="caret">▾</span></span><div class="menu">""")
        p.jellyfinUrl?.let { append(extLink("Open in Jellyfin", it, "Album in the Jellyfin web UI")) }
        if (rg != null) {
            append(extLink("MusicBrainz · release-group", "https://musicbrainz.org/release-group/$rg", "musicbrainz.org/release-group/${rg.take(8)}…"))
            a.releaseMbid?.let { append(extLink("MusicBrainz · release", "https://musicbrainz.org/release/$it", "the pressing this page uses")) }
            append(extLink("Cover Art Archive", "https://coverartarchive.org/release-group/$rg", "coverartarchive.org/release-group/${rg.take(8)}…"))
        }
        for ((k, u) in a.urls) URL_LABELS[k]?.let { append(extLink(it, u, "from MusicBrainz’s URL relationships")) }
        append("</div></span>")
        append("""<span class="menu-wrap"><span class="btn sm ghost menu-btn">Re-pull <span class="caret">▾</span></span><div class="menu">""")
        append("""<div class="menu-item" data-a="repull-jf"><span class="mi-ic">⟲</span><span>From Jellyfin…<span class="mi-sub">Ask Jellyfin to re-read the album; the next scan brings the changes</span></span></div>""")
        if (matched && locked) append("""<div class="menu-item mu-tip" style="opacity:.45;cursor:not-allowed"><span class="mi-ic">⟲</span><span>From MusicBrainz<span class="mi-sub">Locked — unlock to re-pull</span></span><span class="tp">This match is locked, so a re-pull would change nothing it filled. Unlock it first if MusicBrainz has something newer you want.</span></div>""")
        else if (matched) append("""<div class="menu-item" data-a="repull-mb"><span class="mi-ic">⟲</span><span>From MusicBrainz<span class="mi-sub">Re-read genres, credits and the release from its own ids</span></span></div>""")
        append("</div></span>")
        append("""<span class="menu-wrap"><span class="btn sm ghost menu-btn">⋯</span><div class="menu">""")
        if (matched || locked) append("""<div class="menu-item" data-a="lock"><span class="mi-ic">${if (locked) "🔓" else "🔒"}</span><span>${if (locked) "Unlock match" else "Lock match"}<span class="mi-sub">${if (locked) "Let the next run match it again" else "The next run leaves this match alone"}</span></span></div>""")
        if (matched) append("""<div class="menu-item" data-a="clear"><span class="mi-ic">✕</span><span>Clear match<span class="mi-sub">Forget the MusicBrainz ids and lock it; the fields it filled stay</span></span></div>""")
        append("""<div class="menu-item" data-a="find"><span class="mi-ic">⌕</span><span>${if (matched) "Change match…" else "Find match…"}<span class="mi-sub">Search MusicBrainz for this album</span></span></div>""")
        append("</div></span>")
        append("""<span class="split"><span class="btn primary" data-a="savesync">Save &amp; Sync ↻</span><span class="btn primary split-caret menu-btn"><span class="caret">▾</span></span><div class="menu">""")
        append("""<div class="menu-item" data-a="savesync"><span class="mi-ic">↻</span><span>Save &amp; Sync<span class="mi-sub">Write album.nfo, then ask Jellyfin to re-read it</span></span></div>""")
        append("""<div class="menu-item" data-a="save"><span class="mi-ic">↓</span><span>Save → NFO<span class="mi-sub">Write album.nfo in the album folder</span></span></div>""")
        append("""<div class="menu-item" data-a="sync"><span class="mi-ic">↻</span><span>Sync Jellyfin<span class="mi-sub">Ask Jellyfin to re-read (no rewrite)</span></span></div>""")
        append("</div></span>")
    }
}

private fun alMatchChip(p: MusicAlbumPageDto): String {
    val a = p.album
    return when {
        a.matchState == "needs_you" -> """<span class="mu-match w"><span class="l1"><span class="dot"></span>${muPlural(a.candidates.size, "candidate")} · needs you</span><span class="l2">${(a.matchNote ?: "No clear winner").esc()}</span></span>"""
        a.matchState != "matched" && a.matchLocked -> """<span class="mu-match lk"><span class="l1">$MU_LOCK Unmatched · locked</span><span class="l2">No run will match it — Find match… or unlock</span></span>"""
        a.matchState != "matched" -> """<span class="mu-match w"><span class="l1"><span class="dot"></span>No MusicBrainz match</span><span class="l2">${(a.matchNote ?: "Not tried yet — the next run tries").esc()}</span></span>"""
        else -> {
            val r = a.release
            val l2 = listOfNotNull(r?.country, r?.date?.take(4), r?.label, r?.format).joinToString(" · ")
            val rg = a.releaseGroupMbid.orEmpty()
            """<span class="mu-match${if (a.matchLocked) " lk" else ""}"><span class="l1">${if (a.matchLocked) "$MU_LOCK Locked · won’t be re-matched" else """<span class="dot"></span>MusicBrainz · release-group"""} <a class="mono tiny" href="https://musicbrainz.org/release-group/$rg" target="_blank" rel="noopener" style="font-weight:500">${rg.take(8)} ▸</a></span>${if (l2.isNotEmpty()) """<span class="l2">release: ${l2.esc()}</span>""" else ""}</span>"""
        }
    }
}

private fun alHead(p: MusicAlbumPageDto): String {
    val a = p.album
    val have = p.tracks.size
    val total = a.release?.trackCount?.takeIf { it > have }
    val lenMs = p.tracks.sumOf { it.lengthMs ?: 0L }
    val formats = p.tracks.map { it.format }.distinct()
    val re = p.tracks.count { it.reencodes }
    val primary = when {
        a.matchState == "needs_you" -> """<span class="btn primary" data-a="find">Choose…</span>"""
        a.matchState != "matched" -> """<span class="btn primary" data-a="find">Find match…</span>"""
        else -> """<span class="btn sm ghost" data-a="find">Change match…</span>"""
    }
    val by = a.albumArtists.joinToString(" &amp; ") { """<a href="#/artist/${it.artistId}">${it.name.esc()}</a>""" }.ifEmpty { "—" }
    return buildString {
        append("""<div class="mu-pb">""")
        append(muCoverHtml(a.title, p.coverUrl, extraClass = "al-cover"))
        append("""<div><div class="mu-by">by $by</div><div class="mu-pbm">""")
        a.year?.let { append("<span>$it</span><span class=\"sep\">·</span>") }
        append("<span>${if (total != null) "$have of $total songs" else muPlural(have, "song")}</span><span class=\"sep\">·</span><span>${muTotal(lenMs)}</span>")
        append("""<span class="mu-type">${MU_TYPE_LABEL[p.type] ?: p.type}</span><span class="sep">·</span>""")
        if (re > 0) {
            append("""<span class="mu-fmt w">${formats.joinToString(" + ").esc()}</span><span class="tiny" style="color:var(--warn)">${if (re == have) "re-encodes on a phone" else "$re of $have re-encode on a phone"}</span><span class="btn sm" data-a="convert">Convert…</span>""")
        } else append("""<span class="mu-fmt">${formats.joinToString(" + ").esc()}</span>""")
        append("</div>")
        append("""<div class="mu-acts">${alMatchChip(p)}$primary""")
        if (alMatched(p)) append("""<span class="chip" style="cursor:pointer" data-a="lock">${if (a.matchLocked) "$MU_LOCK Locked" else "Lock"}</span>""")
        append("</div>")
        if (total != null) append("""<div class="tiny muted" style="margin-top:10px;">Partial album — the release has $total tracks, the library holds $have. Tracks shows the gaps.</div>""")
        p.library?.let { append("""<div class="tiny muted" style="margin-top:6px;">${it.esc()}${a.path?.let { path -> " · <span class=\"mono\">${path.esc()}</span>" } ?: ""}</div>""") }
        append("</div></div>")
    }
}

private fun alBanners(p: MusicAlbumPageDto): String = buildString {
    val a = p.album
    // Phase 283 (FR-283-3/4) — what the folder and the songs disagree on, first: it says why the rest went as it did.
    for (f in p.flags) append(alFlagCard(f))
    if (p.dismissedFlags.isNotEmpty()) {
        append("""<div class="tiny muted" style="margin:-4px 0 14px;">""")
        append(p.dismissedFlags.joinToString(" · ") { k -> "You marked “${alFlagName(k)}” as right · <a href=\"#\" data-a=\"flagshow\" data-kind=\"${k.esc()}\">Show it again</a>" })
        append("</div>")
    }
    if (a.matchState == "unmatched" && !a.matchLocked)
        append("""<div class="mu-note w"><span class="badge warn">Unmatched</span><div class="t"><b>No MusicBrainz match yet.</b> ${(a.matchNote ?: "").esc()} Nothing below fills in until this is matched: the cover, genres, the release’s track count and every recording id.</div><span class="btn sm primary" data-a="find">Find match…</span></div>""")
    if (a.matchState == "needs_you")
        append("""<div class="mu-note w"><span class="badge warn">Needs you</span><div class="t"><b>${muPlural(a.candidates.size, "candidate")}, no clear winner.</b> ${(a.matchNote ?: "").esc()}</div><span class="btn sm primary" data-a="find">Choose…</span></div>""")
    p.drift?.let { n ->
        append("""<div class="note" style="margin-bottom:14px;background:var(--warn-soft);border-color:rgba(245,181,66,.4);display:flex;gap:13px;align-items:flex-start;"><span class="badge warn" style="flex:none;margin-top:1px;">⇄ Drift detected</span><div style="flex:1;min-width:0;"><b>album.nfo was rewritten since we wrote it${a.nfoDriftAt?.let { " — found " + dev.jellystructure.formatStoredTs(it.toString()) } ?: ""}.</b><div class="tiny" style="margin-top:5px;line-height:1.6;"><b>${muPlural(n, "field")}</b> differ from our last write. When Jellyfin’s own NFO saver is on for this library it will keep happening — see <a href="#/settings?tab=libraries">the advisor finding on the library card</a>.</div><div class="pill-row" style="margin-top:10px;"><span class="btn sm" data-a="reassert">Re-assert NFO → Jellyfin</span></div></div></div>""")
    }
    if (a.jellyfinLocked.isNotEmpty())
        append("""<div class="note red" style="margin-bottom:14px;display:flex;gap:13px;align-items:flex-start;"><span class="badge bad" style="flex:none;margin-top:1px;">⚠ Locked in Jellyfin</span><div style="flex:1;min-width:0;"><b>This album has locked metadata in Jellyfin, so what jellystructure writes may be ignored.</b><div class="tiny" style="margin-top:5px;line-height:1.6;">Locked: ${a.jellyfinLocked.joinToString(" ") { """<span class="chip" style="font-size:.66rem;">${if (it == "All") "everything" else it.esc()}</span>""" }}. To fix it: open the album in Jellyfin → <b>Edit metadata</b> → uncheck the locks → save.</div></div></div>""")
}

private fun alFlagName(kind: String) = if (kind == "shared_album") "Several folders" else "Folder and songs disagree"

private fun alFlagCard(f: MusicFlagDto): String = buildString {
    val mono = { s: String? -> s?.let { """<span class="mono">${it.esc()}</span>""" } ?: "" }
    append("""<div class="mu-note w"><span class="badge warn" style="flex:none">${alFlagName(f.kind)}</span><div class="t">""")
    if (f.kind == "shared_album") {
        append("""<b>${f.sentence.esc()}: <i>${(f.filesTitle ?: "").esc()}</i>.</b> """)
        append("""This folder (${mono(f.folder)}) and ${if (f.others.size == 1) "this one" else "these"} name the same album: """)
        val shown = f.others.take(6)
        append(shown.joinToString(", ") { o -> """<a href="#/album/${o.id.esc()}">${(o.folder ?: o.title).esc()}</a>""" })
        if (f.others.size > shown.size) append(" and ${f.others.size - shown.size} more")
        append(""". If they are one album, put the songs in one folder. If each folder is its own release — a single, an EP — give each its own match with Find match….""")
    } else {
        append("""<b>${f.sentence.esc()}.</b>""")
        append("""<div style="margin-top:4px">Folder: ${mono(listOfNotNull(f.folderArtist, f.folder).joinToString("/"))}</div>""")
        append("""<div>Songs: ${listOfNotNull(f.filesArtist?.esc(), f.filesTitle?.let { "<i>${it.esc()}</i>" }).joinToString(" — ")}</div>""")
        append("""<div style="margin-top:4px">A search by the songs’ tags can find the wrong album, or none. Fix the tags or the folder, or search by the folder’s name.</div>""")
    }
    if (f.writtenFromMatch) append("""<div class="tiny" style="margin-top:6px">The cover and <span class="mono">album.nfo</span> in this folder came from the match this puts in doubt.</div>""")
    append("""<div class="pill-row" style="margin-top:10px;">""")
    f.search?.let { q -> append("""<span class="btn sm primary" data-a="flagfind" data-q="${q.esc()}">Find match… by the folder’s name</span>""") }
    append("""<span class="btn sm ghost" data-a="flagok" data-kind="${f.kind.esc()}">This is right</span>""")
    append("</div></div></div>")
}

private fun alPanel(scope: CoroutineScope) {
    val tabs = document.querySelectorAll("#al-tabs [data-tab]")
    for (i in 0 until tabs.length) (tabs.item(i) as? HTMLElement)?.let { it.className = if (it.getAttribute("data-tab") == alTab) "on" else "" }
    val el = document.getElementById("al-panel") as? HTMLElement ?: return
    val p = alPage ?: return
    when (alTab) {
        "artwork" -> { el.innerHTML = """<span class="muted tiny">Asking the Cover Art Archive…</span>"""; scope.launch { el.innerHTML = alArtwork(p) } }
        "genres" -> el.innerHTML = alGenres(p)
        "nfo" -> { el.innerHTML = """<span class="muted tiny">Loading…</span>"""; scope.launch { el.innerHTML = alNfo(p) } }
        "history" -> { el.innerHTML = """<span class="muted tiny">Loading…</span>"""; scope.launch { el.innerHTML = muHistory(p.album.id) } }
        else -> el.innerHTML = alTracks(p)
    }
}

// ── Tracks ──

private fun alTrackRow(p: MusicAlbumPageDto, t: MusicTrackRow, cols: Int): String = buildString {
    val a = p.album
    val matched = alMatched(p)
    val others = t.artists.filter { c -> a.albumArtists.none { it.artistId == c.artistId } }
    append("""<tr class="${if (matched && t.recording == "disagrees") "off" else ""}"><td class="n">${t.position ?: ""}</td><td>${t.title.esc()}""")
    if (others.isNotEmpty()) append("""<div class="tiny dim">${others.joinToString(" &amp; ") { """<a class="dim" href="#/artist/${it.artistId}">${it.name.esc()}</a>""" }}</div>""")
    if (matched && t.mbTitle != null && !t.mbTitle.equals(t.title, ignoreCase = true)) append("""<div class="tiny dim">MusicBrainz: ${t.mbTitle.esc()}</div>""")
    append("</td>")
    append("""<td class="num">${muLen(t.lengthMs)}</td>""")
    append("""<td><span class="mu-fmt${if (t.reencodes) " w" else ""}">${t.format.esc()}${t.sampleRate?.let { " · ${(it / 100) / 10.0} kHz".replace(".0 kHz", " kHz") } ?: ""}</span></td>""")
    append("<td>")
    when {
        !matched -> append("""<span class="mu-mt d">— after a match</span>""")
        t.recording == "disagrees" -> append("""<span class="mu-mt w">from a different release</span> <span class="btn sm ghost" data-rec="${t.id}" style="margin-left:4px;">Match this track…</span>""")
        t.recording == "manual" -> append("""<span class="mu-mt ok">✓ chosen by hand</span>""")
        t.recording == "agrees" -> append("""<span class="mu-mt ok">✓ recording</span>""")
        else -> append("""<span class="mu-mt w">not on this release</span> <span class="btn sm ghost" data-rec="${t.id}" style="margin-left:4px;">Match this track…</span>""")
    }
    append("</td><td>")
    when (t.lyrics) {
        "synced" -> append("""<span class="mu-ly">$MU_LYR synced ✓</span>""")
        "plain" -> append("""<span class="mu-ly">$MU_LYR plain ✓</span>""")
        "instrumental" -> append("""<span class="mu-ly no">instrumental</span>""")
        else -> append(if (p.lyricsEnabled) """<span class="mu-ly no">none · <a href="#" data-a="lyrics">Fetch</a></span>""" else """<span class="mu-ly no" title="Lyrics fetching is off in Settings">none</span>""")
    }
    append("</td>")
    append("""<td class="num mu-mono" style="font-size:.74rem">${t.gainDb?.let { d -> "${if (d >= 0) "+" else ""}${(kotlin.math.round(d * 10) / 10)} dB" } ?: "—"}</td>""")
    append("<td>")
    if (t.browser) append("""<span class="mu-play${if (alPlaying == t.id) " on" else ""}" data-play="${t.id}" title="Play in this browser — your own Jellyfin session, direct play">${if (alPlaying == t.id) "❚❚" else "▶"}</span>""")
    else append("""<span class="tiny muted mu-tip" style="cursor:help">no direct play<span class="tp">A browser can’t direct-play this format, and this page never asks Jellyfin to convert — direct play or nothing, the segment editor’s rule.</span></span>""")
    append("</td></tr>")
    if (alRecOpen == t.id) append("""<tr><td></td><td colspan="${cols - 1}"><div class="mu-cand on" style="cursor:default;margin:2px 0 6px;" id="al-rec"><span class="mu-busy" style="padding:0"><span class="sp"></span>Searching MusicBrainz for recordings of “${t.title.esc()}”…</span></div></td></tr>""")
}

private fun alTracks(p: MusicAlbumPageDto): String {
    val a = p.album
    val cols = 8
    val oneDisc = p.tracks.all { (it.disc ?: 1) == 1 }
    val total = a.release?.trackCount ?: 0
    val rows = StringBuilder()
    if (oneDisc && total > 0 && alMatched(p)) {
        val byPos = p.tracks.filter { it.position != null }.associateBy { it.position!! }
        val max = maxOf(total, byPos.keys.maxOrNull() ?: 0)
        var gapFrom: Int? = null
        fun flush(to: Int) {
            val from = gapFrom ?: return
            val n = to - from + 1
            rows.append("""<tr class="gap"><td class="n">${if (from == to) "$from" else "$from–$to"}</td><td colspan="${cols - 1}">not in library${if (n > 1) " · $n tracks" else ""}</td></tr>""")
            gapFrom = null
        }
        for (n in 1..max) {
            val t = byPos[n]
            if (t != null) { flush(n - 1); rows.append(alTrackRow(p, t, cols)) } else if (gapFrom == null) gapFrom = n
        }
        flush(max)
        p.tracks.filter { it.position == null }.forEach { rows.append(alTrackRow(p, it, cols)) }
    } else {
        var disc: Int? = null
        for (t in p.tracks) {
            if (!oneDisc && t.disc != disc) { disc = t.disc; rows.append("""<tr class="gap"><td colspan="$cols" style="font-style:normal;font-weight:600">Disc ${disc ?: 1}</td></tr>""") }
            rows.append(alTrackRow(p, t, cols))
        }
    }
    val missingLy = p.tracks.count { it.lyrics == null || it.lyrics == "none" }
    return buildString {
        append("""<div class="row center" style="gap:10px;flex-wrap:wrap;margin-bottom:10px;"><span class="tiny muted">""")
        append(a.albumGainDb?.let { "Album gain <b class=\"mono\" style=\"color:var(--ink)\">${(kotlin.math.round(it * 10) / 10)} dB</b> · from Jellyfin’s loudness scan · the phone applies album gain on an album, track gain on a mix" } ?: "No loudness scan from Jellyfin yet — the phone plays these at their own level")
        append("""</span><span class="spacer"></span>""")
        if (p.lyricsEnabled && missingLy > 0) append("""<span class="btn sm ghost" data-a="lyrics">Fetch missing lyrics ($missingLy)</span>""")
        append("</div>")
        if (!alMatched(p)) append("""<div class="note blue" style="margin-bottom:12px;">Positions come from the files’ tags. A match tells us how many tracks the release has — then the gaps show here.</div>""")
        append("""<div class="mu-scroll"><table class="mu-tbl"><thead><tr><th>#</th><th>Title</th><th>Length</th><th>Format</th><th>Recording</th><th>Lyrics</th><th>Gain</th><th></th></tr></thead><tbody>$rows</tbody></table></div>""")
        if (oneDisc) append("""<div class="tiny muted" style="margin-top:10px;">No disc numbers in these files — every track is disc 1. Positions are the files’ own.</div>""")
    }
}

// ── Artwork ──

private suspend fun alArtwork(p: MusicAlbumPageDto): String {
    val a = p.album
    val art = MusicApi.albumArtwork(a.id)
    return buildString {
        append("""<div class="mu-sec">Currently in use</div><div class="mu-art">""")
        if (p.coverUrl != null) {
            val name = art?.inUse ?: if (a.coverState == "jellyfin") "embedded in the files" else "cover"
            val size = art?.inUseBytes?.let { " · ${(it / 1024)} KB" } ?: ""
            append("""<div class="mu-aw use"><div class="im chk"><div style="position:absolute;inset:0;${muImageStyle(p.coverUrl)}"></div></div><div class="cap"><b>${name.esc()}</b><span class="d">${if (art?.inUse != null) "on disk$size" else "Jellyfin’s image — no file in the folder"}</span><span class="spacer" style="flex:1"></span>""")
            if (art?.inUse != null) append("""<span class="chip" style="font-size:.66rem;cursor:pointer" data-a="coverlock">${if (art.locked) "🔒 locked" else "🔓 lock"}</span><span class="chip" style="font-size:.66rem;cursor:pointer" data-a="coverclear">Clear</span>""")
            append("</div></div>")
        } else append("""<div class="mu-aw"><div class="im chk" style="display:flex;align-items:center;justify-content:center;"><span class="tiny" style="color:var(--warn);font-weight:600">no cover on disk</span></div><div class="cap"><b>cover.jpg</b><span class="d">missing — a task</span></div></div>""")
        append("""<label class="mu-aw" style="cursor:pointer;border-style:dashed;"><div class="im" style="display:flex;align-items:center;justify-content:center;flex-direction:column;gap:6px;color:var(--ink-soft);font-size:.8rem;">⬆<span>Upload a cover</span><span class="tiny muted">JPG or PNG, square</span></div><input type="file" id="al-upload" accept="image/*" style="display:none"></label></div>""")
        val caa = art?.candidates.orEmpty().filter { it.source == "Cover Art Archive" }
        val fan = art?.candidates.orEmpty().filter { it.source == "fanart.tv" }
        append("""<div class="mu-sec">Candidates · Cover Art Archive</div>""")
        when {
            !alMatched(p) -> append("""<div class="tiny muted">A match fills this from the Cover Art Archive — front, back and booklet, as the release’s own scans.</div>""")
            art == null -> append("""<div class="tiny muted">The Cover Art Archive didn’t answer — try again in a moment.</div>""")
            caa.none { it.kind == "front" } -> append("""<div class="mu-note w"><div class="t"><b>No cover on the Cover Art Archive for this release-group.</b> Nobody has uploaded one yet. Upload one here — it is written as <span class="mono">cover.jpg</span> in the album folder and Jellyfin reads it on the next sync.</div><label class="btn sm primary" for="al-upload">Upload…</label></div>""")
            else -> append(artGrid(caa, "cover"))
        }
        if (alMatched(p)) {
            append("""<div class="mu-sec">Candidates · fanart.tv</div>""")
            append(if (fan.isNotEmpty()) artGrid(fan, "cover") else """<div class="tiny muted">fanart.tv has nothing for this album${if (art?.candidates?.isEmpty() != false) " (or no fanart.tv key is set)" else ""}.</div>""")
        }
    }
}

/** A provider's pictures, each with *Use* (a CD face is shown but never offered as the cover). */
internal fun artGrid(cands: List<dev.jellystructure.model.MusicArtCandidate>, target: String): String = buildString {
    append("""<div class="mu-art">""")
    for ((i, c) in cands.withIndex()) {
        val wide = c.kind == "background"
        val logo = c.kind == "logo" || c.kind == "cdart"
        append("""<div class="mu-aw"><div class="im${if (wide) " wide" else ""}${if (logo) " chk" else ""}" style="${if (logo) "background-size:contain;background-position:center;background-repeat:no-repeat;background-image:url('${c.thumb.esc()}')" else muImageStyle(c.thumb)}"></div>""")
        if (c.kind == "front" && c.approved) append("""<span class="mu-appr">approved</span>""")
        append("""<div class="cap"><b>${c.kind.esc()}</b><span class="d">${c.source.esc()}</span>""")
        if (c.kind != "cdart") append("""<span class="btn sm ghost" style="margin-left:auto" data-use="$i" data-src="${c.source.esc()}" data-target="$target">Use</span>""")
        append("</div>")
        c.credit?.let { append("""<div class="tiny muted" style="margin-top:4px;line-height:1.45">${it.esc()} — the credit travels in artist.nfo</div>""") }
        append("</div>")
    }
    append("</div>")
}

// ── Genres ──

private fun alGenres(p: MusicAlbumPageDto): String {
    val a = p.album
    if (!alMatched(p)) return buildString {
        append("""<div class="mu-sec">Genres</div>""")
        append(if (a.genres.isNotEmpty()) """<div class="mu-gchips">${a.genres.joinToString("") { """<span class="mu-gc on"><span class="bx">✓</span>${it.esc()}<span class="v">from the file’s tag</span></span>""" }}</div>"""
            else """<div class="tiny muted">None in the files. A match brings MusicBrainz’s genre votes.</div>""")
        append("""<div class="tiny muted" style="margin-top:10px;">After a match, MusicBrainz’s community votes replace the files’ tags as the source.</div>""")
    }
    val on = p.genres.toSet()
    return buildString {
        append("""<div class="mu-sec">MusicBrainz genres · votes${if (a.genresOverride != null) """ <span class="tiny" style="text-transform:none;letter-spacing:0;font-weight:500">· your own pick · <a href="#" data-a="genres-reset">back to MusicBrainz’s</a></span>""" else ""}</div>""")
        if (a.mbGenres.isEmpty()) append("""<div class="tiny muted">MusicBrainz has no genre votes for this release-group yet.</div>""")
        else append("""<div class="mu-gchips">${a.mbGenres.joinToString("") { g -> """<span class="mu-gc${if (g.name in on) " on" else ""}" data-g="${g.name.esc()}"><span class="bx">${if (g.name in on) "✓" else ""}</span>${g.name.esc()}<span class="v">${g.count}</span></span>""" }}</div>""")
        append("""<div class="tiny muted" style="margin-top:10px;line-height:1.6;">Ticked genres are written to <span class="mono">album.nfo</span>. The rule: up to four, each with at least 3 votes and at least a tenth of the top genre’s votes. Tick or untick to override — the override is kept across runs.</div>""")
    }
}

// ── NFO, history ──

private suspend fun alNfo(p: MusicAlbumPageDto): String {
    val n = MusicApi.albumNfo(p.album.id)
    return buildString {
        append("""<div class="row center" style="margin-bottom:10px;gap:8px"><span class="tiny muted mono">${(n?.path ?: "no album folder").esc()}</span><span class="spacer"></span>""")
        append(when {
            n?.text == null -> """<span class="badge">not written yet</span>"""
            n.drift != null -> """<span class="badge warn">rewritten by someone else · ${muPlural(n.drift, "field")} differ</span>"""
            else -> """<span class="badge ok">written${p.album.nfoWrittenAt?.let { " " + dev.jellystructure.formatStoredTs(it.toString()) } ?: ""}</span>"""
        })
        append("</div>")
        append(if (n?.text != null) """<div class="mu-nfo">${n.text.esc()}</div>"""
            else """<div class="tiny muted">${if (alMatched(p)) "Written by the next run’s write_music_nfo step, or now with Save → NFO." else "An album.nfo is written once the album is matched."}</div>""")
    }
}

internal suspend fun muHistory(id: String): String {
    val h = MediaApi.getHistory(id)
    if (h.isEmpty()) return """<div class="tiny muted">Nothing recorded yet.</div>"""
    return """<div class="mu-hist">${h.sortedByDescending { it.timestamp }.joinToString("") { e -> """<div class="mu-hi"><span class="ts">${dev.jellystructure.formatStoredTs(e.timestamp.toString())}</span><span>${e.detail.esc()}</span></div>""" }}</div>"""
}

// ── actions ──

private fun alClick(t: Element, ev: org.w3c.dom.events.Event, scope: CoroutineScope) {
    val p = alPage ?: return
    val id = p.album.id
    t.closest("#al-tabs [data-tab]")?.let { tab ->
        alTab = tab.getAttribute("data-tab") ?: "tracks"
        Router.updateQuery(mapOf("tab" to alTab, "find" to null))
        alPanel(scope); return
    }
    if (t.closest(".al-cover") != null) { alLightbox(p); return }
    t.closest("[data-play]")?.let { pl ->
        val tid = pl.getAttribute("data-play") ?: return
        val audio = alAudio ?: return
        if (alPlaying == tid) { audio.pause(); alPlaying = null; alPanel(scope); return }
        scope.launch {
            val url = MusicApi.streamUrl(tid) ?: return@launch muToast("Jellyfin can’t direct-play that here")
            audio.src = url; audio.play(); alPlaying = tid; alPanel(scope)
        }
        return
    }
    t.closest("[data-rec]")?.let { r ->
        val tid = r.getAttribute("data-rec") ?: return
        alRecOpen = if (alRecOpen == tid) null else tid
        alPanel(scope)
        if (alRecOpen != null) scope.launch { alLoadRecordings(tid, scope) }
        return
    }
    t.closest("[data-recuse]")?.let { r ->
        val tid = r.getAttribute("data-recuse") ?: return
        val rec = r.getAttribute("data-mbid") ?: return
        scope.launch { if (MusicApi.useRecording(tid, rec) != null) { muToast("Recording matched · written with the next album.nfo"); alRecOpen = null; alReload(scope) } else muToast("That didn’t work") }
        return
    }
    t.closest("[data-g]")?.let { g ->
        val name = g.getAttribute("data-g") ?: return
        val now = p.genres.toMutableList()
        if (!now.remove(name)) now += name
        scope.launch { if (MusicApi.setGenres(id, now) != null) alReload(scope) else muToast("That didn’t save") }
        return
    }
    t.closest("[data-use]")?.let { u ->
        val i = u.getAttribute("data-use")?.toIntOrNull() ?: return
        scope.launch {
            val src = u.getAttribute("data-src")
            val c = MusicApi.albumArtwork(id)?.candidates?.filter { it.source == src }?.getOrNull(i)
                ?: return@launch muToast("That picture isn’t on offer any more")
            muToast(if (MusicApi.useAlbumCover(id, c)) "cover.jpg written · Jellyfin re-reads it on the next sync" else "That picture couldn’t be used")
            alReload(scope)
        }
        return
    }
    val a = t.closest("[data-a]") ?: return
    ev.preventDefault()
    when (a.getAttribute("data-a")) {
        "find" -> fmOpen(scope)
        "flagfind" -> { fmPrefill = a.getAttribute("data-q"); fmOpen(scope) }
        "flagok", "flagshow" -> scope.launch {
            val kind = a.getAttribute("data-kind") ?: return@launch
            val ok = a.getAttribute("data-a") == "flagok"
            if (MusicApi.setFlagDismissed(id, kind, ok)) { muToast(if (ok) "Won’t be flagged again unless the folder or the tags change" else "Flagged again"); alReload(scope) }
            else muToast("That didn’t save")
        }
        "lock" -> scope.launch { MusicApi.lock(id, !p.album.matchLocked)?.let { muToast(if (it.matchLocked) "Locked · runs leave this match alone" else "Unlocked · the next run may re-match it"); alReload(scope) } }
        "clear" -> scope.launch { MusicApi.clear(id)?.let { muToast("Match cleared and locked · the fields it filled stay"); alReload(scope) } }
        "convert" -> muOpenConvert(scope, MusicConvertRequest(albumId = id)) { alReload(scope) }
        "reassert" -> scope.launch { alSaved(MusicApi.saveAlbum(id, sync = true), sync = true); alReload(scope) }
        "savesync" -> scope.launch { alSaved(MusicApi.saveAlbum(id, sync = true), sync = true); alReload(scope) }
        "save" -> scope.launch { alSaved(MusicApi.saveAlbum(id, sync = false), sync = false); alReload(scope) }
        "sync" -> scope.launch { muToast(if (MusicApi.syncAlbum(id)) "Sync requested ↻ Jellyfin is re-reading" else "Jellyfin didn’t answer") }
        "repull-jf" -> scope.launch { muToast(if (MusicApi.syncAlbum(id)) "Jellyfin is re-reading the album · the next scan brings what changed" else "Jellyfin didn’t answer") }
        "repull-mb" -> scope.launch {
            val s = MusicApi.bulk("match", listOf(id)) ?: return@launch muToast("A matching pass is already running")
            muToast(s); alWaitForMatch(scope)
        }
        "lyrics" -> scope.launch { muToast("Asking LRCLIB…"); muToast(MusicApi.fetchLyrics(id) ?: "LRCLIB didn’t answer"); alReload(scope) }
        "genres-reset" -> scope.launch { MusicApi.setGenres(id, null)?.let { alReload(scope) } }
        "coverclear" -> scope.launch { muToast(if (MusicApi.clearAlbumCover(id)) "cover.jpg removed" else "Nothing removed"); alReload(scope) }
        "coverlock" -> scope.launch {
            val locked = MusicApi.albumArtwork(id)?.locked ?: false
            MusicApi.lockAlbumCover(id, !locked); muToast(if (!locked) "Cover locked · a run won’t replace it" else "Cover unlocked"); alPanel(scope)
        }
    }
}

private fun alSaved(outcome: String?, sync: Boolean) = muToast(when (outcome) {
    "written" -> if (sync) "album.nfo written · Jellyfin re-reading ↻" else "album.nfo written"
    "unchanged" -> if (sync) "album.nfo already says this · Jellyfin re-reading ↻" else "album.nfo already says this"
    "no_folder" -> "Not written — an album.nfo is written once the album is matched"
    null -> "The server didn’t answer"
    else -> "album.nfo couldn’t be written"
})

private suspend fun alWaitForMatch(scope: CoroutineScope) {
    while (true) {
        delay(1200)
        val st = MusicApi.status() ?: return
        if (!st.match.running) { alReload(scope); return }
    }
}

private suspend fun alLoadRecordings(trackId: String, scope: CoroutineScope) {
    val box = document.getElementById("al-rec") as? HTMLElement ?: return
    val t = alPage?.tracks?.firstOrNull { it.id == trackId } ?: return
    val recs = MusicApi.recordings(trackId)
    if (alRecOpen != trackId) return
    box.innerHTML = when {
        recs == null -> """<span class="tiny muted">MusicBrainz didn’t answer — try again in a moment.</span>"""
        recs.isEmpty() -> """<span class="tiny muted">MusicBrainz has no recording of “${t.title.esc()}” by this artist.</span>"""
        else -> buildString {
            append("""<b class="tiny">Recordings of “${t.title.esc()}”</b><table class="mu-mini">""")
            for (r in recs) {
                val diff = if (r.lengthMs != null && t.lengthMs != null) ((r.lengthMs - t.lengthMs) / 1000).toInt() else null
                val fit = when { diff == null -> """<td class="w">no length</td>"""; kotlin.math.abs(diff) <= 3 -> """<td class="ok">length agrees</td>"""; diff > 0 -> """<td class="w">$diff s longer</td>"""; else -> """<td class="w">${-diff} s shorter</td>""" }
                append("""<tr><td>${r.title.esc()}</td><td class="mono">${muLen(r.lengthMs)}</td><td>${r.releases.take(2).joinToString(" · ").esc()}</td>$fit<td><span class="btn sm ${if (diff != null && kotlin.math.abs(diff) <= 3) "primary" else "ghost"}" data-recuse="${t.id}" data-mbid="${r.mbid}">Use</span></td></tr>""")
            }
            append("</table>")
        }
    }
}

private fun alLightbox(p: MusicAlbumPageDto) {
    document.getElementById("mu-lb")?.remove()
    val lb = document.createElement("div") as HTMLElement
    lb.id = "mu-lb"; lb.className = "mu-lb on"
    lb.innerHTML = """<div class="im" style="${p.coverUrl?.let { muImageStyle(it) } ?: muWordmarkStyle(p.album.title)}"></div><div class="cap">${if (p.coverUrl != null) "cover" else "no cover on disk"}</div>"""
    lb.addEventListener("click") { lb.remove() }
    document.body?.appendChild(lb)
}

// ── Find match… (FR-276-4, drawn as a side panel) ──

/** Phase 283 — Find match… opened from a flag searches for the folder's name instead of the tag's. */
private var fmPrefill: String? = null

private fun fmOpen(scope: CoroutineScope) {
    val p = alPage ?: return
    fmCands = if (fmPrefill != null) emptyList() else p.album.candidates.map { it to null }
    fmChosen = null; fmReleases = null; fmRelease = 0; fmBusy = null; fmSound = null
    document.getElementById("fm-scrim")?.remove(); document.getElementById("fm-panel")?.remove()
    val scrim = document.createElement("div") as HTMLElement
    scrim.id = "fm-scrim"; scrim.className = "mu-scrim on"
    scrim.addEventListener("click") { fmClose() }
    val panel = document.createElement("aside") as HTMLElement
    panel.id = "fm-panel"; panel.className = "mu-panel on"
    panel.addEventListener("click") { ev -> fmClick(ev.target as? Element ?: return@addEventListener, scope) }
    document.body?.appendChild(scrim); document.body?.appendChild(panel)
    fmPaint()
    if (fmCands.isEmpty()) fmSearch(scope)
}

private fun fmClose() {
    fmPrefill = null
    document.getElementById("fm-scrim")?.remove(); document.getElementById("fm-panel")?.remove()
    if (Router.currentQuery()["find"] != null) Router.updateQuery(mapOf("find" to null))
}

private fun fmPaint() {
    val panel = document.getElementById("fm-panel") as? HTMLElement ?: return
    val p = alPage ?: return
    val a = p.album
    val artist = a.albumArtists.firstOrNull()?.name.orEmpty()
    val prevAr = (document.getElementById("fm-ar") as? HTMLInputElement)?.value
    val prevAl = (document.getElementById("fm-al") as? HTMLInputElement)?.value
    panel.innerHTML = buildString {
        append("""<div class="mu-ph"><h3>${if (a.matchState == "needs_you") "Choose a match" else "Find a match"} · ${a.title.esc()}</h3><span class="spacer"></span><span class="btn sm ghost" data-f="close">✕</span></div><div class="mu-pbody">""")
        append("""<div class="mu-q3" style="grid-template-columns:minmax(0,1fr) minmax(0,1.4fr) auto"><div class="field"><label>Artist</label><input id="fm-ar" value="${(prevAr ?: artist.takeIf { !it.equals("Various Artists", true) } ?: "").esc()}"></div><div class="field"><label>Album — or a MusicBrainz URL</label><input id="fm-al" value="${(prevAl ?: fmPrefill ?: a.title).esc()}"></div><span class="btn" data-f="search">Search</span></div>""")
        append("""<div class="tiny muted" style="margin-top:6px;">Pre-filled from ${a.path?.let { "the folder <span class=\"mono\">${it.substringAfterLast('/').esc()}</span>" } ?: "the tags"}. Paste a <span class="mono">musicbrainz.org/release-group/…</span> or <span class="mono">/release/…</span> URL to take exactly that.</div>""")
        when {
            fmBusy != null -> append("""<div class="mu-busy"><span class="sp"></span>${fmBusy!!.esc()}</div>""")
            fmCands.isEmpty() -> append("""<div class="tiny muted" style="padding:14px 2px;">No candidates. Try the album title alone, or paste a MusicBrainz URL.</div>""")
            else -> for ((i, pair) in fmCands.withIndex()) {
                val (c, fit) = pair
                append("""<div class="mu-cand${if (fmChosen == i) " on" else ""}" data-cand="$i"><div class="mu-ch"><div class="th" style="${muImageStyle("https://coverartarchive.org/release-group/${c.releaseGroupMbid}/front-250")};background-color:#222"></div><div><div class="nm">${c.title.esc()}</div><div class="sb">${c.artist.esc()} ${c.primaryType?.let { """<span class="mu-type">${it.esc()}</span>""" } ?: ""}${c.secondaryTypes.joinToString("") { """ <span class="mu-type">${it.esc()}</span>""" }} ${c.firstReleaseDate?.take(4)?.let { "first released $it" } ?: ""}${if (c.source == "acoustid") " · identified by sound" else ""}</div></div><div class="mu-score">score<b>${c.score}</b></div></div>""")
                val cls = when { c.agreeing == 0 -> "bad"; c.agreeing == c.total && c.lengthOffMaxSec == null -> "ok"; else -> "w" }
                append("""<div class="mu-agree $cls">${(fit ?: "<b>${c.agreeing} of ${c.total}</b> tracks agree on position and length").let { if (fit != null) it.esc() else it }}</div>""")
                if (fmChosen == i) {
                    append("""<div class="mu-rels"><div class="tiny muted" style="margin-bottom:4px;">Releases · the best-agreeing one is preselected</div>""")
                    val rels = fmReleases
                    if (rels == null) append("""<div class="mu-busy" style="padding:4px 2px"><span class="sp"></span>Reading its pressings…</div>""")
                    else for ((j, r) in rels.withIndex()) append("""<div class="mu-rel${if (fmRelease == j) " on" else ""}" data-rel="$j"><span class="rd"></span><span class="mono">${(r.country ?: "—").esc()}</span><span class="mono">${(r.date ?: "—").esc()}</span><span class="lb">${(r.label ?: "").esc()}</span><span class="fm">${(r.format ?: "").esc()} · ${r.trackCount} tr</span><span>${if (j == 0) """<span class="best">best · ${r.agreeing}/${c.total}</span>""" else """<span class="tiny muted">${r.agreeing}/${c.total}</span>"""}</span></div>""")
                    append("</div>")
                }
                append("</div>")
            }
        }
        append(if (p.acoustId) """<div class="mu-fp"><div class="h">Identify by sound · AcoustID<span class="spacer" style="flex:1"></span><span class="btn sm ghost" data-f="sound">Fingerprint ${muPlural(p.tracks.size.coerceAtMost(4), "track")}</span></div><div class="tiny muted" style="margin-top:6px;">${fmSound?.esc() ?: "Fingerprints the files and asks AcoustID which recordings they are — the tie-breaker when the tags are not enough."}</div></div>"""
            else """<div class="mu-fp"><div class="h">Identify by sound</div><div class="tiny muted" style="margin-top:6px;"><a href="#/settings?tab=connections">Add an AcoustID key in Settings</a> to identify by sound.</div></div>""")
        append("</div>")
        val off = fmChosen == null || fmReleases == null || fmCands.getOrNull(fmChosen ?: -1)?.first?.agreeing == 0
        append("""<div class="mu-pfoot"><span class="btn primary${if (off) " is-off" else ""}" data-f="use">Use this match</span><span class="btn${if (off) " is-off" else ""}" data-f="uselock">Use and lock</span><span class="spacer" style="flex:1"></span><span class="btn ghost" data-f="close">Cancel</span></div>""")
    }
}

private fun luceneEsc(s: String) = buildString { for (c in s) { if (c in "+-&|!(){}[]^\"~*?:\\/") append('\\'); append(c) } }

private fun fmSearch(scope: CoroutineScope) {
    val p = alPage ?: return
    val ar = (document.getElementById("fm-ar") as? HTMLInputElement)?.value?.trim().orEmpty()
    val al = (document.getElementById("fm-al") as? HTMLInputElement)?.value?.trim().orEmpty()
    val url = al.takeIf { "musicbrainz.org/" in it }
    val query = if (url != null || al.isEmpty()) null else "releasegroup:\"${luceneEsc(al)}\"" + if (ar.isNotEmpty()) " AND artist:\"${luceneEsc(ar)}\"" else ""
    fmBusy = "Searching MusicBrainz… it answers one request a second, so a search plus each candidate’s releases takes a few seconds."
    fmChosen = null; fmReleases = null; fmPaint()
    scope.launch {
        val r = MusicApi.search(p.album.id, query, url)
        fmBusy = null
        if (r == null) { fmCands = emptyList(); fmPaint(); muToast("MusicBrainz didn’t answer — try again in a moment"); return@launch }
        fmCands = r.map { it.candidate to it.fit }
        fmPaint()
    }
}

private fun fmClick(t: Element, scope: CoroutineScope) {
    val p = alPage ?: return
    val rel = t.closest("[data-rel]")
    val cand = t.closest("[data-cand]")
    if (rel != null && cand != null) { fmRelease = rel.getAttribute("data-rel")?.toIntOrNull() ?: 0; fmPaint(); return }
    val f = t.closest("[data-f]")
    if (cand != null && f == null) {
        val i = cand.getAttribute("data-cand")?.toIntOrNull() ?: return
        if (fmChosen == i) return
        fmChosen = i; fmReleases = null; fmRelease = 0; fmPaint()
        val c = fmCands[i].first
        scope.launch {
            val rels = MusicApi.releases(p.album.id, c.releaseGroupMbid)
            if (fmChosen != i) return@launch
            fmReleases = rels ?: emptyList()
            if (rels == null) muToast("MusicBrainz didn’t answer — try again in a moment")
            fmPaint()
        }
        return
    }
    when (f?.getAttribute("data-f")) {
        "close" -> fmClose()
        "search" -> fmSearch(scope)
        "sound" -> {
            fmSound = "fpcalc · fingerprinting…"; fmPaint()
            scope.launch {
                val r = MusicApi.identify(p.album.id)
                fmSound = r?.sentence ?: "AcoustID didn’t answer"
                r?.candidates?.let { found -> if (found.isNotEmpty()) fmCands = found.map { it to null } + fmCands.filter { c -> found.none { it.releaseGroupMbid == c.first.releaseGroupMbid } } }
                fmPaint()
            }
        }
        "use", "uselock" -> {
            val i = fmChosen ?: return
            val c = fmCands[i].first
            if (c.agreeing == 0) { muToast("No track agrees with that candidate — pick one whose tracks line up, or keep it unmatched"); return }
            val r = fmReleases?.getOrNull(fmRelease) ?: return
            val lock = f.getAttribute("data-f") == "uselock"
            scope.launch {
                val done = MusicApi.use(p.album.id, c.releaseGroupMbid, r.mbid, lock)
                if (done == null) { muToast("MusicBrainz didn’t answer — nothing changed"); return@launch }
                fmClose()
                muToast("Matched${if (lock) " and locked" else ""} · the cover, genres and album.nfo come with the next run — or Save & Sync now")
                alReload(scope)
            }
        }
    }
}
