@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.MetaFacets
import dev.jellystructure.api.RaviloApi
import dev.jellystructure.api.TrackFacets
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.ChannelLogo
import kotlin.js.JsString
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.files.File
import org.w3c.files.FileReader

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

// R36 channel-button section (shown only when editing a channel).
private var wbChannelMode = false
private var wbChStyle = "logo"            // "logo" | "text"
private var wbChColor = ""                // CSS fill: a preset gradient, a solid hex, or a custom gradient
private var wbChName = ""                 // channel name (for the preview / initials)
private var wbLogoUrl: String? = null
private var wbLogos: List<ChannelLogo> = emptyList()
private var wbOnSaveChannel: ((String, String, String?, String, String, List<WbCond>) -> Unit)? = null
private var wbCustomType = "gradient"     // custom builder: "solid" | "gradient"
private var wbCustomC1 = "#7b6ef0"
private var wbCustomC2 = "#3fb6f5"
private var wbCustomAngle = 135

private val WB_CH_PRESETS = listOf(
    "linear-gradient(135deg,#3b2a78,#15102e)",
    "linear-gradient(135deg,#e3122b,#7d0a1a)",
    "linear-gradient(135deg,#0a93a6,#063d47)",
    "linear-gradient(135deg,#1455d8,#0a2766)",
    "linear-gradient(135deg,#c8102e,#1a1a1a)",
)

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
    // R36: when channelMode, the modal also edits the channel button; onSaveChannel receives
    // (style "LOGO"|"TEXT", brandColor, logoUrl, match, include, conditions).
    channelMode: Boolean = false,
    initialStyle: String = "logo",
    initialBrandColor: String = "",
    initialLogoUrl: String? = null,
    channelName: String = "",
    onSaveChannel: ((String, String, String?, String, String, List<WbCond>) -> Unit)? = null,
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
    wbChannelMode = channelMode
    wbChStyle = if (initialStyle.equals("text", ignoreCase = true)) "text" else "logo"
    wbChColor = initialBrandColor
    wbLogoUrl = initialLogoUrl
    wbChName = channelName
    wbOnSaveChannel = onSaveChannel
    if (wbChColor.isNotEmpty() && wbChColor !in WB_CH_PRESETS) {  // seed the custom builder from an existing value
        if (wbChColor.startsWith("linear-gradient")) {
            wbCustomType = "gradient"
            val inner = wbChColor.substringAfter('(').substringBeforeLast(')').split(',').map { it.trim() }
            wbCustomAngle = inner.firstOrNull { it.endsWith("deg") }?.removeSuffix("deg")?.toIntOrNull() ?: 135
            val cols = inner.filter { it.startsWith("#") }
            wbCustomC1 = cols.getOrNull(0) ?: "#7b6ef0"
            wbCustomC2 = cols.getOrNull(1) ?: "#3fb6f5"
        } else {
            wbCustomType = "solid"; wbCustomC1 = wbChColor
        }
    }
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
          <div id="wb-channel"></div>
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
        if (wbChannelMode) {
            if (wbChColor.isEmpty()) wbChColor = WB_CH_PRESETS[0]
            wbLogos = runCatching { RaviloApi.listChannelLogos() }.getOrDefault(emptyList())
            wbRenderChannel()
        }
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
        if (wbChannelMode) {
            val style = if (wbChStyle == "text") "TEXT" else "LOGO"
            val logo = if (wbChStyle == "logo") wbLogoUrl else null
            wbOnSaveChannel?.invoke(style, wbChColor, logo, wbMatch, wbInclude, wbConds)
        } else {
            wbOnApply?.invoke(wbMatch, wbInclude, wbConds)
        }
        closeWorkbench()
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

private fun wbCustomCss(): String =
    if (wbCustomType == "solid") wbCustomC1 else "linear-gradient(${wbCustomAngle}deg, $wbCustomC1, $wbCustomC2)"

private fun wbChannelChip(): String {
    val fill = wbChColor.ifEmpty { WB_CH_PRESETS[0] }
    val inner = if (wbChStyle == "logo" && !wbLogoUrl.isNullOrBlank()) {
        """<img src="${wbLogoUrl!!.esc()}" alt="">"""
    } else {
        (if (wbChStyle == "logo") wbChName.take(3).uppercase() else wbChName.take(12).ifEmpty { "Channel" }).esc()
    }
    return """<div class="wbc-chip" style="background:$fill;">$inner</div>"""
}

private fun wbRenderChannel() {
    val host = document.getElementById("wb-channel") as? HTMLElement ?: return
    if (!wbChannelMode) { host.innerHTML = ""; return }
    val isCustom = wbChColor.isNotEmpty() && wbChColor !in WB_CH_PRESETS
    val presetSw = WB_CH_PRESETS.joinToString("") { c ->
        """<span class="wbc-sw${if (c == wbChColor) " on" else ""}" data-color="${c.esc()}" style="background:$c;"></span>"""
    }
    val customSw = """<span class="wbc-sw wbc-custom${if (isCustom) " on" else ""}" data-customsw title="Custom color or gradient" style="${if (isCustom) "background:${wbChColor.esc()};" else ""}">${if (isCustom) "" else "+"}</span>"""
    val builder = if (isCustom) """
      <div class="wbc-grad">
        <span class="seg wbc-gradmode"><span class="${if (wbCustomType == "solid") "on" else ""}" data-gm="solid">Solid</span><span class="${if (wbCustomType == "gradient") "on" else ""}" data-gm="gradient">Gradient</span></span>
        <label class="wbc-cf"><span class="tiny muted">${if (wbCustomType == "gradient") "From" else "Color"}</span><input type="color" class="wbc-c1" value="$wbCustomC1"></label>
        <label class="wbc-cf" style="${if (wbCustomType == "gradient") "" else "display:none;"}"><span class="tiny muted">To</span><input type="color" class="wbc-c2" value="$wbCustomC2"></label>
        <label class="wbc-cf" style="flex:1;min-width:150px;${if (wbCustomType == "gradient") "" else "display:none;"}"><span class="tiny muted">Angle <b class="wbc-angv">$wbCustomAngle°</b></span><input type="range" min="0" max="360" step="5" class="wbc-ang" value="$wbCustomAngle"></label>
      </div>""" else ""
    val logoOrText = if (wbChStyle == "logo") {
        val tiles = wbLogos.joinToString("") { lg ->
            """<button class="wbc-tile${if (lg.url == wbLogoUrl) " on" else ""}" data-logo="${lg.url.esc()}" title="${lg.label.esc()}"><img src="${lg.url.esc()}" alt=""></button>"""
        }
        """<div class="tiny muted" style="margin:12px 0 7px;">Pick a logo or upload your own — a transparent PNG/SVG sits cleanly on the brand fill.</div>
           <div class="wbc-grid">$tiles<button class="wbc-tile wbc-up" data-upload><span style="font-size:1.25rem;">⤒</span><span class="tiny">Upload</span></button></div>
           <input type="file" accept="image/png,image/svg+xml,image/*" class="wbc-file" style="display:none;">"""
    } else {
        """<div class="tiny muted" style="margin:12px 0 2px;">Text mode shows the channel name on the button.</div>"""
    }
    host.innerHTML = """
      <hr class="wbc-hr">
      <div class="wbc-flex">
        <div><div class="wbc-lbl">Display</div><span class="seg wbc-style"><span class="${if (wbChStyle == "logo") "on" else ""}" data-st="logo">Logo</span><span class="${if (wbChStyle == "text") "on" else ""}" data-st="text">Text</span></span></div>
        <div><div class="wbc-lbl">Brand fill</div><div class="wbc-sws">$presetSw$customSw</div></div>
        <div style="margin-left:auto;text-align:center;"><div class="wbc-lbl">Preview</div>${wbChannelChip()}</div>
      </div>
      $builder
      $logoOrText
    """.trimIndent()
    wbWireChannel()
}

private fun wbLiveUpdateChip() {
    (document.querySelector("#wb-channel .wbc-chip") as? HTMLElement)
        ?.setAttribute("style", "background:${wbChColor.ifEmpty { WB_CH_PRESETS[0] }};")
    (document.querySelector("#wb-channel .wbc-custom") as? HTMLElement)?.let {
        it.setAttribute("style", "background:$wbChColor;"); it.textContent = ""; it.classList.add("on")
    }
    val els = document.querySelectorAll("#wb-channel .wbc-sw[data-color]")
    for (i in 0 until els.length) (els.item(i) as? HTMLElement)?.classList?.remove("on")
}

private fun wbWireChannel() {
    fun each(sel: String, fn: (HTMLElement) -> Unit) {
        val els = document.querySelectorAll(sel)
        for (i in 0 until els.length) (els.item(i) as? HTMLElement)?.let(fn)
    }
    each("#wb-channel .wbc-style span") { el -> el.addEventListener("click") { wbChStyle = el.getAttribute("data-st") ?: "logo"; wbRenderChannel() } }
    each("#wb-channel .wbc-sw[data-color]") { el -> el.addEventListener("click") { wbChColor = el.getAttribute("data-color") ?: ""; wbRenderChannel() } }
    (document.querySelector("#wb-channel [data-customsw]") as? HTMLElement)?.addEventListener("click") { wbChColor = wbCustomCss(); wbRenderChannel() }
    each("#wb-channel .wbc-gradmode span") { el -> el.addEventListener("click") { wbCustomType = el.getAttribute("data-gm") ?: "gradient"; wbChColor = wbCustomCss(); wbRenderChannel() } }
    (document.querySelector("#wb-channel .wbc-c1") as? HTMLInputElement)?.let { inp -> inp.addEventListener("input") { wbCustomC1 = inp.value; wbChColor = wbCustomCss(); wbLiveUpdateChip() } }
    (document.querySelector("#wb-channel .wbc-c2") as? HTMLInputElement)?.let { inp -> inp.addEventListener("input") { wbCustomC2 = inp.value; wbChColor = wbCustomCss(); wbLiveUpdateChip() } }
    (document.querySelector("#wb-channel .wbc-ang") as? HTMLInputElement)?.let { inp ->
        inp.addEventListener("input") {
            wbCustomAngle = inp.value.toIntOrNull() ?: 135
            (document.querySelector("#wb-channel .wbc-angv") as? HTMLElement)?.textContent = "$wbCustomAngle°"
            wbChColor = wbCustomCss(); wbLiveUpdateChip()
        }
    }
    each("#wb-channel .wbc-tile[data-logo]") { el -> el.addEventListener("click") { wbLogoUrl = el.getAttribute("data-logo"); wbRenderChannel() } }
    val fileInput = document.querySelector("#wb-channel .wbc-file") as? HTMLInputElement
    (document.querySelector("#wb-channel [data-upload]") as? HTMLElement)?.addEventListener("click") { fileInput?.click() }
    fileInput?.let { inp ->
        inp.addEventListener("change") {
            val file: File = inp.files?.item(0) ?: return@addEventListener
            val reader = FileReader()
            reader.onload = { _ ->
                val dataUrl = (reader.result as? JsString)?.toString() ?: ""
                if (dataUrl.isNotEmpty()) wbScope?.launch {
                    runCatching { RaviloApi.uploadChannelLogo(file.name, dataUrl) }.onSuccess { logo ->
                        wbLogos = listOf(logo) + wbLogos.filter { it.url != logo.url }
                        wbLogoUrl = logo.url
                        wbRenderChannel()
                    }
                }
            }
            reader.readAsDataURL(file)
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
        .wbc-hr { border:none; border-top:1px dashed var(--line); margin:16px 0; }
        .wbc-flex { display:flex; align-items:flex-start; gap:18px; flex-wrap:wrap; }
        .wbc-lbl { font-size:.72rem; color:var(--ink-soft); margin-bottom:6px; }
        .wbc-sws { display:flex; gap:7px; flex-wrap:wrap; align-items:center; }
        .wbc-sw { width:26px; height:26px; border-radius:8px; cursor:pointer; border:2px solid transparent; box-sizing:border-box; }
        .wbc-sw.on { border-color:var(--ink); box-shadow:0 0 0 2px var(--fill); }
        .wbc-custom { display:flex; align-items:center; justify-content:center; color:var(--ink-soft); font-size:1.1rem; background:var(--fill-3); }
        .wbc-chip { width:120px; height:46px; border-radius:10px; display:flex; align-items:center; justify-content:center; color:#fff; font-weight:700; font-size:.78rem; overflow:hidden; }
        .wbc-chip img { max-width:80%; max-height:64%; object-fit:contain; }
        .wbc-grad { display:flex; align-items:center; gap:14px; flex-wrap:wrap; margin-top:12px; }
        .wbc-cf { display:flex; flex-direction:column; gap:3px; }
        .wbc-cf input[type=color] { width:46px; height:30px; border:none; background:none; padding:0; cursor:pointer; }
        .wbc-cf input[type=range] { width:100%; }
        .wbc-grid { display:flex; gap:9px; flex-wrap:wrap; }
        .wbc-tile { width:74px; height:46px; border-radius:9px; border:1px solid var(--line-2); background:var(--fill-2); cursor:pointer; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:2px; overflow:hidden; color:var(--ink-soft); }
        .wbc-tile.on { border-color:var(--hi); box-shadow:0 0 0 2px var(--hi); }
        .wbc-tile img { max-width:84%; max-height:70%; object-fit:contain; }
    """.trimIndent()
    document.head?.appendChild(style)
}
