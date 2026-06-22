@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.MetaFacets
import dev.jellystructure.api.TrackFacets
import dev.jellystructure.model.MediaKind
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

// ── R32 unified filter workbench ────────────────────────────────────────────────
// One shared condition-stack builder for the Library, Ravilo Channels and Content rows.
// Facets = the Phase-30 axes (studio/network/genre/tag) + an Audio-track group
// (audio_language incl. "untagged", audio_codec, track_title contains) + a Ravilo-layout
// hero_item facet. Live "N titles match" count + a poster preview, served by /api/media.

class WbCond(var facet: String, var op: String, val values: MutableList<String> = mutableListOf())

private val WB_GROUPS = listOf(
    "Metadata" to listOf("studio" to "Studio", "network" to "Network", "genre" to "Genre", "tag" to "Tag"),
    "Audio track" to listOf("audio_language" to "Audio language", "audio_codec" to "Audio codec", "track_title" to "Audio track title"),
    "Ravilo layout" to listOf("hero_item" to "Hero item"),
)
private val WB_LABELS = WB_GROUPS.flatMap { it.second }.toMap()

private fun isListFacet(f: String) = f in setOf("studio", "network", "genre", "tag", "audio_language", "audio_codec", "hero_item")
private fun opsFor(f: String): List<Pair<String, String>> = when (f) {
    "track_title" -> listOf("contains" to "contains", "not_contains" to "does not contain")
    "hero_item" -> listOf("is_any_of" to "is any of")
    else -> listOf("is_any_of" to "is any of", "is_none_of" to "is none of")
}

private var wbConds = mutableListOf<WbCond>()
private var wbMatch = "ALL"
private var wbInclude = "all"
private var wbViewer: String? = null
private var wbMeta: MetaFacets? = null
private var wbTrack: TrackFacets? = null
private var wbScope: CoroutineScope? = null
private var wbOnApply: ((String, String, List<WbCond>) -> Unit)? = null
private var wbOnSave: ((String, String, String, List<WbCond>) -> Unit)? = null
private var wbShowSaveAs = false
private var wbTitle = ""
private var wbApplyLabel = "Apply"

/**
 * Open the workbench modal.
 * @param onApply     called with (match, include, conditions) when the user applies the filter.
 * @param onSaveAs    when non-null, "Save as… Channel/Row" buttons appear; called with
 *                    (target = "channel"|"row", match, include, conditions).
 */
fun openWorkbench(
    scope: CoroutineScope,
    title: String,
    viewer: String?,
    initialMatch: String = "ALL",
    initialInclude: String = "all",
    initialConds: List<WbCond> = emptyList(),
    applyLabel: String = "Apply",
    onApply: (String, String, List<WbCond>) -> Unit,
    onSaveAs: ((String, String, String, List<WbCond>) -> Unit)? = null,
) {
    wbScope = scope
    wbTitle = title
    wbViewer = viewer
    wbMatch = initialMatch
    wbInclude = initialInclude
    wbConds = initialConds.map { WbCond(it.facet, it.op, it.values.toMutableList()) }.toMutableList()
    if (wbConds.isEmpty()) wbConds.add(WbCond("studio", "is_any_of"))
    wbApplyLabel = applyLabel
    wbOnApply = onApply
    wbOnSave = onSaveAs
    wbShowSaveAs = onSaveAs != null
    injectWorkbenchStyles()

    val existing = document.getElementById("wb-overlay")
    existing?.parentElement?.removeChild(existing)
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "wb-overlay"
    overlay.className = "wb-overlay"
    overlay.innerHTML = """
        <div class="wb-modal">
          <div class="wb-head">
            <h3 style="margin:0;">${title.esc()}</h3>
            <span class="spacer"></span>
            <span class="seg wb-match">
              <span class="${if (wbMatch == "ALL") "on" else ""}" data-match="ALL">Match ALL</span>
              <span class="${if (wbMatch == "ANY") "on" else ""}" data-match="ANY">Match ANY</span>
            </span>
            <span class="seg wb-include" style="margin-left:8px;">
              <span class="${if (wbInclude == "all") "on" else ""}" data-inc="all">All</span>
              <span class="${if (wbInclude == "movies") "on" else ""}" data-inc="movies">Movies</span>
              <span class="${if (wbInclude == "series") "on" else ""}" data-inc="series">Series</span>
            </span>
            <span class="wb-x" id="wb-close" style="margin-left:12px;cursor:pointer;">✕</span>
          </div>
          <div class="wb-body" id="wb-conds"></div>
          <button id="wb-add" class="btn sm ghost">+ Add condition</button>
          <div class="wb-count" id="wb-count"><span class="muted tiny">Computing…</span></div>
          <div class="wb-preview" id="wb-preview"></div>
          <div class="wb-foot">
            <span class="spacer"></span>
            <button id="wb-cancel" class="btn sm ghost">Cancel</button>
            ${if (wbShowSaveAs) """<button id="wb-save-channel" class="btn sm ghost">Save as Channel</button><button id="wb-save-row" class="btn sm ghost">Save as Content row</button>""" else ""}
            <button id="wb-apply" class="btn sm">${applyLabel.esc()}</button>
          </div>
        </div>
    """.trimIndent()
    document.body?.appendChild(overlay)

    scope.launch {
        if (wbMeta == null) wbMeta = MediaApi.metaFacets()
        if (wbTrack == null) wbTrack = MediaApi.trackFacets()
        wbRenderConds()
        wbWireChrome()
        wbRefreshPreview()
    }
}

private fun wbValuesFor(facet: String): List<String> = when (facet) {
    "studio" -> wbMeta?.studios?.map { it.value } ?: emptyList()
    "network" -> wbMeta?.networks?.map { it.value } ?: emptyList()
    "genre" -> wbMeta?.genres?.map { it.value } ?: emptyList()
    "tag" -> wbMeta?.tags?.map { it.value } ?: emptyList()
    "audio_language" -> (wbTrack?.audioLanguages?.map { it.value } ?: emptyList()) + "untagged"
    "audio_codec" -> wbTrack?.audioCodecs?.map { it.value } ?: emptyList()
    "hero_item" -> listOf("featured", "not_featured")
    else -> emptyList()
}

private fun wbRenderConds() {
    val host = document.getElementById("wb-conds") as? HTMLElement ?: return
    val facetOpts = WB_GROUPS.joinToString("") { (group, facets) ->
        "<optgroup label=\"$group\">" + facets.joinToString("") { (f, l) -> "<option value=\"$f\">${l.esc()}</option>" } + "</optgroup>"
    }
    host.innerHTML = wbConds.mapIndexed { i, c ->
        val opOpts = opsFor(c.facet).joinToString("") { (v, l) -> "<option value=\"$v\"${if (c.op == v) " selected" else ""}>${l.esc()}</option>" }
        val valEditor = if (c.facet == "track_title") {
            """<input class="input wb-text" data-i="$i" placeholder="e.g. Commentary, SDH, Synstolkning" value="${(c.values.firstOrNull() ?: "").esc()}">"""
        } else {
            val chips = wbValuesFor(c.facet).take(80).joinToString("") { v ->
                val on = c.values.contains(v)
                val lbl = if (c.facet == "hero_item") (if (v == "featured") "Featured" else "Not featured") else v
                """<span class="wb-vchip${if (on) " on" else ""}" data-i="$i" data-v="${v.esc()}">${lbl.esc()}</span>"""
            }
            """<div class="wb-vchips">$chips</div>"""
        }
        """<div class="wb-cond">
              <div class="wb-cond-head">
                ${if (i > 0) """<span class="wb-join">${if (wbMatch == "ANY") "OR" else "AND"}</span>""" else """<span class="wb-join muted">where</span>"""}
                <select class="input wb-facet" data-i="$i">${facetOpts.replace("value=\"${c.facet}\"", "value=\"${c.facet}\" selected")}</select>
                <select class="input wb-op" data-i="$i">$opOpts</select>
                <span class="spacer"></span>
                <span class="wb-rm" data-i="$i" style="cursor:pointer;opacity:.6;">✕</span>
              </div>
              $valEditor
           </div>"""
    }.joinToString("")
    wbWireConds()
}

private fun wbWireChrome() {
    document.querySelectorAll("#wb-overlay .wb-match span").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") { wbMatch = el.getAttribute("data-match") ?: "ALL"; refreshChromeSeg(); wbRenderConds(); wbRefreshPreview() } }
    }
    document.querySelectorAll("#wb-overlay .wb-include span").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") { wbInclude = el.getAttribute("data-inc") ?: "all"; refreshChromeSeg(); wbRefreshPreview() } }
    }
    document.getElementById("wb-add")?.addEventListener("click") { wbConds.add(WbCond("studio", "is_any_of")); wbRenderConds(); wbRefreshPreview() }
    document.getElementById("wb-close")?.addEventListener("click") { closeWorkbench() }
    document.getElementById("wb-cancel")?.addEventListener("click") { closeWorkbench() }
    document.getElementById("wb-apply")?.addEventListener("click") {
        wbOnApply?.invoke(wbMatch, wbInclude, wbConds); closeWorkbench()
    }
    document.getElementById("wb-save-channel")?.addEventListener("click") { wbOnSave?.invoke("channel", wbMatch, wbInclude, wbConds); closeWorkbench() }
    document.getElementById("wb-save-row")?.addEventListener("click") { wbOnSave?.invoke("row", wbMatch, wbInclude, wbConds); closeWorkbench() }
}

private fun refreshChromeSeg() {
    fun seg(sel: String, attr: String, cur: String) = document.querySelectorAll(sel).let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLElement ?: continue
            if (el.getAttribute(attr) == cur) el.classList.add("on") else el.classList.remove("on") }
    }
    seg("#wb-overlay .wb-match span", "data-match", wbMatch)
    seg("#wb-overlay .wb-include span", "data-inc", wbInclude)
}

private fun wbWireConds() {
    document.querySelectorAll("#wb-conds .wb-facet").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLSelectElement ?: continue
            el.addEventListener("change") {
                val idx = el.getAttribute("data-i")?.toIntOrNull() ?: return@addEventListener
                val c = wbConds.getOrNull(idx) ?: return@addEventListener
                c.facet = el.value; c.op = opsFor(c.facet).first().first; c.values.clear()
                wbRenderConds(); wbRefreshPreview()
            } }
    }
    document.querySelectorAll("#wb-conds .wb-op").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLSelectElement ?: continue
            el.addEventListener("change") {
                val idx = el.getAttribute("data-i")?.toIntOrNull() ?: return@addEventListener
                wbConds.getOrNull(idx)?.op = el.value; wbRefreshPreview()
            } }
    }
    document.querySelectorAll("#wb-conds .wb-rm").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val idx = el.getAttribute("data-i")?.toIntOrNull() ?: return@addEventListener
                if (wbConds.size > 1) wbConds.removeAt(idx) else wbConds[0].values.clear()
                wbRenderConds(); wbRefreshPreview()
            } }
    }
    document.querySelectorAll("#wb-conds .wb-vchip").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val idx = el.getAttribute("data-i")?.toIntOrNull() ?: return@addEventListener
                val v = el.getAttribute("data-v") ?: return@addEventListener
                val c = wbConds.getOrNull(idx) ?: return@addEventListener
                if (c.facet == "hero_item") { c.values.clear(); c.values.add(v) }   // single-select
                else if (c.values.contains(v)) c.values.remove(v) else c.values.add(v)
                wbRenderConds(); wbRefreshPreview()
            } }
    }
    document.querySelectorAll("#wb-conds .wb-text").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLInputElement ?: continue
            el.addEventListener("input") {
                val idx = el.getAttribute("data-i")?.toIntOrNull() ?: return@addEventListener
                val c = wbConds.getOrNull(idx) ?: return@addEventListener
                c.values.clear(); if (el.value.isNotBlank()) c.values.add(el.value)
                wbRefreshPreview()
            } }
    }
}

/** Whether every active condition can be served exactly by /api/media (ALL mode, positive ops). */
private fun wbExactlyServable(): Boolean {
    if (wbMatch == "ANY") return false
    return wbConds.none { it.op == "is_none_of" || it.op == "not_contains" }
}

private fun wbRefreshPreview() {
    val scope = wbScope ?: return
    val countEl = document.getElementById("wb-count") as? HTMLElement
    countEl?.innerHTML = """<span class="muted tiny">Computing…</span>"""
    scope.launch {
        fun vals(f: String) = wbConds.filter { it.facet == f && it.op == "is_any_of" }.flatMap { it.values }.distinct()
        val audioLangsAll = vals("audio_language")
        val untagged = audioLangsAll.contains("untagged")
        val audioLangs = audioLangsAll.filter { it != "untagged" }
        val heroVals = wbConds.filter { it.facet == "hero_item" }.flatMap { it.values }
        val page = MediaApi.list(
            kind = when (wbInclude) { "movies" -> MediaKind.MOVIE; "series" -> MediaKind.TV_SHOW; else -> null },
            pageSize = 18,
            studios = vals("studio"),
            networks = vals("network"),
            genres = vals("genre"),
            tags = vals("tag"),
            audioLangs = audioLangs,
            audioCodec = vals("audio_codec").firstOrNull(),
            trackTitle = wbConds.firstOrNull { it.facet == "track_title" && it.op == "contains" }?.values?.firstOrNull(),
            untaggedAudio = untagged,
            heroItem = if (heroVals.contains("not_featured")) "not_featured" else if (heroVals.contains("featured")) "featured" else null,
            viewer = wbViewer,
        )
        val approx = !wbExactlyServable()
        val total = page?.total ?: 0
        countEl?.innerHTML = """<b>${if (approx) "≈ " else ""}$total title(s) match</b>${if (approx) """ <span class="tiny muted">— some conditions (none-of / not-contains / ANY) are evaluated on the TV</span>""" else ""}"""
        val prev = document.getElementById("wb-preview") as? HTMLElement
        prev?.innerHTML = (page?.items ?: emptyList()).joinToString("") { m ->
            val img = if (!m.posterPath.isNullOrBlank()) """<img src="https://image.tmdb.org/t/p/w185${m.posterPath}" alt="">""" else """<div class="wb-noimg">${m.title.take(2).esc()}</div>"""
            """<div class="wb-pcard" title="${m.title.esc()}">$img</div>"""
        }
    }
}

private fun closeWorkbench() {
    document.getElementById("wb-overlay")?.let { it.parentElement?.removeChild(it) }
}

private fun injectWorkbenchStyles() {
    if (document.getElementById("wb-styles") != null) return
    val style = document.createElement("style") as? org.w3c.dom.HTMLStyleElement ?: return
    style.id = "wb-styles"
    style.textContent = """
        .wb-overlay { position:fixed; inset:0; background:rgba(0,0,0,.55); display:flex; align-items:flex-start; justify-content:center; z-index:1000; overflow:auto; padding:40px 16px; }
        .wb-modal { background:var(--fill); color:var(--ink); border:1px solid var(--line); box-shadow:var(--shadow); border-radius:16px; width:min(860px,100%); padding:18px; }
        .wb-head { display:flex; align-items:center; gap:6px; margin-bottom:14px; }
        .wb-cond { border:1px solid var(--line); border-radius:11px; padding:10px; margin-bottom:9px; background:var(--fill-2); }
        .wb-cond-head { display:flex; align-items:center; gap:8px; margin-bottom:8px; }
        .wb-join { font-size:.72rem; font-weight:700; color:var(--ink-soft); min-width:38px; }
        .wb-facet, .wb-op { padding:4px 8px; }
        .wb-vchips { display:flex; flex-wrap:wrap; gap:6px; max-height:120px; overflow:auto; }
        .wb-vchip { padding:3px 9px; border-radius:18px; border:1px solid var(--line-2); background:var(--fill-2); color:var(--ink); cursor:pointer; font-size:.76rem; }
        .wb-vchip.on { background:var(--hi); border-color:transparent; color:#fff; }
        .wb-count { margin:12px 0 8px; font-size:.9rem; color:var(--ink); }
        .wb-preview { display:grid; grid-template-columns:repeat(auto-fill,minmax(70px,1fr)); gap:7px; max-height:240px; overflow:auto; margin-bottom:14px; }
        .wb-pcard { aspect-ratio:2/3; border-radius:7px; overflow:hidden; background:var(--fill-3); }
        .wb-pcard img { width:100%; height:100%; object-fit:cover; }
        .wb-noimg { width:100%; height:100%; display:flex; align-items:center; justify-content:center; font-weight:700; color:var(--ink-soft); }
        .wb-foot { display:flex; gap:8px; align-items:center; }
    """.trimIndent()
    document.head?.appendChild(style)
}
