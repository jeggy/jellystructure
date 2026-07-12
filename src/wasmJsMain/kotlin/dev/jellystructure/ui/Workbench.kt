@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.api.BatchCountRequest
import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.MetaFacets
import dev.jellystructure.api.NarrowedFacets
import dev.jellystructure.api.TrackFacetItem
import dev.jellystructure.api.RaviloApi
import dev.jellystructure.api.TrackFacets
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.MediaPage
import dev.jellystructure.shared.tv.ChannelLogo
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.shared.tv.QueryJoin
import dev.jellystructure.shared.tv.QueryNode
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.isLive
import kotlin.js.JsString
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.files.File
import org.w3c.files.FileReader

// ── Phase 140 — Workbench query blocks ──────────────────────────────────────────
// Recursive AND/OR groups (blocks/sub-blocks, depth <=3) with per-block NOT, replacing the flat
// ALL/ANY condition stack. Shared across the Library filter, Ravilo Channels, Home content rows,
// and per-channel rows (where the owning channel's query renders as a locked read-only first
// block). Facets = the Phase-30 axes (studio/network/genre/tag) + an Audio-track group
// (audio_language incl. "untagged", audio_codec, track_title contains) + a Ravilo-layout
// hero_item facet + the contextual content_row facet (R87). Live "N titles match" count + per-
// block count badges + a poster preview, served by /api/media & /api/media/batch-count.
//
// WbNode/WbGroup/WbCond are admin-only *mutable* editing state (direct re-render-on-mutation, no
// virtual DOM) — the public openWorkbench()/onApply surface trades exclusively in the shared,
// immutable ConditionGroup/Condition tree; callers never see WbNode.

sealed class WbNode
class WbCond(
    var facet: String,
    var op: String,
    val values: MutableList<String> = mutableListOf(),
    // R87: referenced content rows for the `content_row` facet (`values` is unused for it).
    val rows: MutableList<RowConfig> = mutableListOf(),
) : WbNode()

class WbGroup(
    var join: String = "or",   // "and" | "or"
    var not: Boolean = false,
    val children: MutableList<WbNode> = mutableListOf(),
) : WbNode()

// ─── Conversion to/from the shared, persisted tree ──────────────────────────────

fun WbNode.toShared(): QueryNode = when (this) {
    is WbCond -> Condition(facet, op, values.toList(), rows.toList())
    is WbGroup -> toSharedGroup()
}
fun WbGroup.toSharedGroup(): ConditionGroup =
    ConditionGroup(if (join == "or") QueryJoin.OR else QueryJoin.AND, not, children.map { it.toShared() })

fun QueryNode.toWb(): WbNode = when (this) {
    is Condition -> WbCond(facet, op, values.toMutableList(), rows.toMutableList())
    is ConditionGroup -> toWbGroup()
}
fun ConditionGroup.toWbGroup(): WbGroup =
    WbGroup(if (join == QueryJoin.OR) "or" else "and", not, children.map { it.toWb() }.toMutableList())

private fun WbGroup.deepCopy(): WbGroup = toSharedGroup().toWbGroup()

// ─── Live / summary helpers (also used by RaviloConfig.kt for row/channel subtitles, via .toWb()) ──

internal fun nodeLive(n: WbNode): Boolean = when (n) {
    is WbCond -> if (n.facet == "content_row") n.rows.isNotEmpty() else n.values.isNotEmpty()
    is WbGroup -> n.children.any { nodeLive(it) }
}

private fun facetLabel(facet: String): String = WB_FACET_LABELS[facet] ?: facet

private fun condSummary(c: WbCond): String {
    val label = facetLabel(c.facet)
    val opLbl = opLabel(c.facet, c.op)
    val valTxt = when (c.facet) {
        "content_row" -> c.rows.joinToString(", ") { it.title?.takeIf { t -> t.isNotBlank() } ?: "Untitled row" }
        "hero_item" -> c.values.joinToString(", ") { if (it == "featured") "Featured" else "Not featured" }
        else -> c.values.joinToString("\", \"").let { if (it.isBlank()) "…" else "\"$it\"" }
    }
    return "$label $opLbl $valTxt"
}

/** Human summary of a query tree with parentheses — e.g. `(Tag "nordic-noir", "dansk-tv" or Genre
 *  "Thriller") and Network "Kringvarp"`. Mirrors the design mockup's `groupSummary`. [top] suppresses
 *  the outer parens (the root call). */
internal fun groupSummary(g: WbGroup, top: Boolean): String {
    val liveKids = g.children.filter { nodeLive(it) }
    if (liveKids.isEmpty()) return ""
    val parts = liveKids.map { when (it) {
        is WbCond -> condSummary(it)
        is WbGroup -> groupSummary(it, top = false)
    } }.filter { it.isNotBlank() }
    if (parts.isEmpty()) return ""
    var s = parts.joinToString(if (g.join == "or") " or " else " and ")
    if (!top && (parts.size > 1 || g.not)) s = "($s)"
    if (g.not) s = "not $s"
    return s
}

private val WB_GROUPS = listOf(
    "Metadata" to listOf("studio" to "Studio", "network" to "Network", "genre" to "Genre", "tag" to "Tag", "age_rating" to "Age rating"),
    "Audio track" to listOf("audio_language" to "Audio language", "audio_codec" to "Audio codec", "track_title" to "Audio track title"),
    "Ravilo layout" to listOf("hero_item" to "Hero item"),
)
private val WB_FACET_LABELS: Map<String, String> = WB_GROUPS.flatMap { it.second }.toMap() + ("content_row" to "Content row")

private fun opsFor(f: String): List<Pair<String, String>> = when (f) {
    "track_title" -> listOf("contains" to "contains", "not_contains" to "does not contain")
    "hero_item" -> listOf("is_any_of" to "is any of")
    else -> listOf("is_any_of" to "is any of", "is_none_of" to "is none of")
}
private fun opLabel(facet: String, op: String) = opsFor(facet).firstOrNull { it.first == op }?.second ?: op

private var wbRoot = WbGroup("and")
private var wbInclude = "all"
private var wbBaseRoot: WbGroup? = null   // Phase 140: the owning channel's locked query, if any
private var wbLockedCount: Int? = null
private var wbRowsContext = emptyList<RowConfig>()  // R87: rows the `content_row` facet can reference
private var wbViewer: String? = null
private var wbMeta: MetaFacets? = null
private var wbTrack: TrackFacets? = null
private var wbNarrowed: NarrowedFacets? = null  // R127: facet counts narrowed to the channel scope
private var wbScope: CoroutineScope? = null
private var wbOnApply: ((ConditionGroup, String) -> Unit)? = null
private var wbTitle = ""
private var wbApplyLabel = "Apply"

// R36 channel-button section (shown only when editing a channel) — unaffected by Phase 140.
private var wbChannelMode = false
private var wbChStyle = "logo"            // "logo" | "text"
private var wbChColor = ""                // CSS fill: a preset gradient, a solid hex, or a custom gradient
private var wbChName = ""                 // channel name (for the preview / initials)
private var wbLogoUrl: String? = null
private var wbLogos: List<ChannelLogo> = emptyList()
private var wbOnSaveChannel: ((String, String, String?, ConditionGroup, String) -> Unit)? = null
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
 * @param initialQuery the tree to seed the editor with (an empty root gets one starter block).
 * @param baseQuery when set, renders as a locked read-only first block (the owning channel's own
 *   query, for a per-channel row's editor) — counts (total, per-block badges, value-picker) are all
 *   scoped inside it.
 * @param onApply    called with (query, include) when the user applies the filter.
 */
fun openWorkbench(
    scope: CoroutineScope,
    title: String,
    viewer: String?,
    initialQuery: ConditionGroup = ConditionGroup(),
    initialInclude: String = "all",
    applyLabel: String = "Apply",
    onApply: (ConditionGroup, String) -> Unit,
    // R36: when channelMode, the modal also edits the channel button; onSaveChannel receives
    // (style "LOGO"|"TEXT", brandColor, logoUrl, query, include).
    channelMode: Boolean = false,
    initialStyle: String = "logo",
    initialBrandColor: String = "",
    initialLogoUrl: String? = null,
    channelName: String = "",
    onSaveChannel: ((String, String, String?, ConditionGroup, String) -> Unit)? = null,
    // Phase 140: the owning channel's query, shown as a locked first block above the row's own.
    baseQuery: ConditionGroup? = null,
    // R87: rows the `content_row` facet may reference; when non-empty the facet is offered.
    rowsContext: List<RowConfig> = emptyList(),
) {
    wbScope = scope
    wbTitle = title
    wbViewer = viewer
    wbRoot = initialQuery.toWbGroup()
    if (wbRoot.children.isEmpty()) wbRoot.children.add(WbGroup("or", children = mutableListOf(WbCond("studio", "is_any_of"))))
    wbInclude = initialInclude
    wbBaseRoot = baseQuery?.toWbGroup()
    wbLockedCount = null
    wbRowsContext = rowsContext
    wbApplyLabel = applyLabel
    wbOnApply = onApply
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
            <span class="seg wb-include" style="margin-left:8px;">
              <span class="${if (wbInclude == "all") "on" else ""}" data-inc="all">All</span>
              <span class="${if (wbInclude == "movies") "on" else ""}" data-inc="movies">Movies</span>
              <span class="${if (wbInclude == "series") "on" else ""}" data-inc="series">Series</span>
            </span>
            <span class="wb-x" id="wb-close" style="margin-left:12px;cursor:pointer;">✕</span>
          </div>
          <div class="wb-body" id="wb-body">
            <div class="wb-main">
              <div id="wb-blocks"></div>
              <div id="wb-summary" class="wb-summary tiny muted"></div>
              <div id="wb-channel"></div>
            </div>
            <div class="wb-side">
              <div class="wb-count" id="wb-count"><span class="muted tiny">Computing…</span></div>
              <div class="wb-preview" id="wb-preview"></div>
            </div>
          </div>
          <div class="wb-foot">
            <span class="spacer"></span>
            <button id="wb-cancel" class="btn sm ghost">Cancel</button>
            <button id="wb-apply" class="btn sm">${applyLabel.esc()}</button>
          </div>
        </div>
    """.trimIndent()
    document.body?.appendChild(overlay)

    scope.launch {
        // Bug fix: routed through the shared FacetsCache (also used by MediaDetail's genre picker)
        // instead of each screen fetching independently — whichever opens first warms it for the other.
        wbMeta = FacetsCache.meta()
        wbTrack = FacetsCache.track()
        // R127: in a channel content-row scope, narrow facet counts to the channel's filter (computed once;
        // values not present in the channel disappear, the rest are ordered by their in-channel count).
        wbNarrowed = wbBaseRoot?.toSharedGroup()?.takeIf { it.isLive() }?.let { MediaApi.narrowedFacets(it) }
        wbRenderBlocks()
        wbWireChrome()
        wbRefreshPreview()
        if (wbChannelMode) {
            if (wbChColor.isEmpty()) wbChColor = WB_CH_PRESETS[0]
            wbLogos = runCatching { RaviloApi.listChannelLogos() }.getOrDefault(emptyList())
            wbRenderChannel()
        }
    }
}

// R127: facet values WITH their counts — narrowed to the channel scope (wbNarrowed) when present,
// otherwise the global library counts. Already count-sorted by the API; order preserved.
private fun wbItemsFor(facet: String): List<TrackFacetItem> {
    val n = wbNarrowed
    return when (facet) {
        "studio" -> n?.studios ?: wbMeta?.studios ?: emptyList()
        "network" -> n?.networks ?: wbMeta?.networks ?: emptyList()
        "genre" -> n?.genres ?: wbMeta?.genres ?: emptyList()
        "tag" -> n?.tags ?: wbMeta?.tags ?: emptyList()
        "age_rating" -> n?.ageRatings ?: wbMeta?.ageRatings ?: emptyList()
        "audio_language" -> (n?.audioLanguages ?: wbTrack?.audioLanguages ?: emptyList()) + TrackFacetItem("untagged", 0)
        "audio_codec" -> n?.audioCodecs ?: wbTrack?.audioCodecs ?: emptyList()
        "track_title" -> n?.trackTitles ?: wbTrack?.trackTitles ?: emptyList()
        "hero_item" -> listOf(TrackFacetItem("featured", 0), TrackFacetItem("not_featured", 0))
        else -> emptyList()
    }
}

// ─── Path addressing: "0-1-2" = wbRoot.children[0].children[1].children[2] ─────

private fun pathStr(path: List<Int>) = path.joinToString("-")
private fun parsePath(s: String): List<Int> = if (s.isBlank()) emptyList() else s.split("-").map { it.toInt() }

private fun groupAt(path: List<Int>): WbGroup {
    var g = wbRoot
    for (i in path) g = g.children[i] as WbGroup
    return g
}

private fun condAt(path: List<Int>): WbCond? {
    if (path.isEmpty()) return null
    val parent = groupAt(path.dropLast(1))
    return parent.children.getOrNull(path.last()) as? WbCond
}

/** Removes the node at [path]. A top-level block (path size 1) is left as one fresh empty block if
 *  it was the last one (never zero blocks); a nested node's removal that empties its containing
 *  group dissolves that group too, cascading upward. */
private fun removeNodeAt(path: List<Int>) {
    if (path.isEmpty()) return
    if (path.size == 1) {
        if (wbRoot.children.size > 1) wbRoot.children.removeAt(path[0])
        else { wbRoot.children.clear(); wbRoot.children.add(WbGroup("or", children = mutableListOf(WbCond("studio", "is_any_of")))) }
        return
    }
    val parentPath = path.dropLast(1)
    val parent = groupAt(parentPath)
    parent.children.removeAt(path.last())
    if (parent.children.isEmpty()) removeNodeAt(parentPath)
}

// ─── Rendering ──────────────────────────────────────────────────────────────────

private fun wbRenderBlocks() {
    val host = document.getElementById("wb-blocks") as? HTMLElement ?: return
    val lockedHtml = wbBaseRoot?.let { renderLockedBlock(it) }.orEmpty()
    val lockedJoin = if (wbBaseRoot != null) """<div class="wb-jpill wb-jpill-locked">AND</div>""" else ""
    val blocksHtml = wbRoot.children.mapIndexed { i, child ->
        val block = child as? WbGroup ?: return@mapIndexed ""
        val joinPill = if (i > 0) """<div class="wb-jpill wb-jpill-root" data-rootjoin>${if (wbRoot.join == "or") "OR" else "AND"}</div>""" else ""
        joinPill + renderBlock(block, listOf(i), depth = 1)
    }.joinToString("")
    val addBlockBtn = """<button id="wb-add-block" class="btn sm ghost" style="margin-top:8px;">+ Add block</button>"""
    host.innerHTML = lockedHtml + lockedJoin + blocksHtml + addBlockBtn
    wbWireBlocks()
    wbRenderSummary()
}

private fun renderLockedBlock(base: WbGroup): String {
    val summary = groupSummary(base, top = true).ifBlank { "No conditions — the channel shows everything." }
    val count = wbLockedCount?.let { "$it in collection" } ?: "…"
    return """
      <div class="wb-block wb-block-locked">
        <div class="wb-block-head">
          <span class="wb-lock-eyebrow">🔒 Collection</span>
          <span class="spacer"></span>
          <span class="wb-block-count">${count.esc()}</span>
        </div>
        <div class="wb-block-body tiny muted">${summary.esc()}</div>
        <div class="tiny muted" style="margin-top:6px;">Locked — this row can only show titles inside this channel. Edit the filter on the channel itself.</div>
      </div>
    """.trimIndent()
}

private fun renderBlock(g: WbGroup, path: List<Int>, depth: Int): String {
    val p = pathStr(path)
    val notCls = if (g.not) " wb-not-on" else ""
    val childrenHtml = g.children.mapIndexed { i, child ->
        val joinPill = if (i > 0) """<div class="wb-jpill wb-jpill-inner" data-blockjoin="$p">${if (g.join == "or") "OR" else "AND"}</div>""" else ""
        val childPath = path + i
        joinPill + when (child) {
            is WbCond -> renderCondRow(child, childPath)
            is WbGroup -> """<div class="wb-subblock-wrap">${renderBlock(child, childPath, depth + 1)}</div>"""
        }
    }.joinToString("")
    val addSubBtn = if (depth < 3) """<button class="wb-add-sub btn sm ghost" data-path="$p">+ Sub-block</button>""" else ""
    return """
      <div class="wb-block$notCls" data-blockpath="$p">
        <div class="wb-block-head">
          <span class="wb-not-toggle${if (g.not) " on" else ""}" data-notpath="$p">NOT</span>
          ${if (g.not) """<span class="tiny" style="color:var(--bad);margin-left:6px">excludes</span>""" else ""}
          <span class="spacer"></span>
          <span class="wb-block-count" data-countpath="$p">…</span>
          <span class="wb-block-rm" data-rmpath="$p" style="cursor:pointer;opacity:.6;margin-left:10px;">✕</span>
        </div>
        <div class="wb-block-body">$childrenHtml</div>
        <div class="wb-block-foot">
          <button class="wb-add-cond btn sm ghost" data-path="$p">+ Condition</button>
          $addSubBtn
        </div>
      </div>
    """.trimIndent()
}

private fun renderCondRow(c: WbCond, path: List<Int>): String {
    val p = pathStr(path)
    val facetOpts = WB_GROUPS.joinToString("") { (group, facets) ->
        // R87: the contextual `content_row` facet — only offered when a rows context is supplied.
        val extra = if (group == "Ravilo layout" && wbRowsContext.isNotEmpty()) "<option value=\"content_row\">Content row</option>" else ""
        "<optgroup label=\"$group\">" + facets.joinToString("") { (f, l) -> "<option value=\"$f\">${l.esc()}</option>" } + extra + "</optgroup>"
    }
    val opOpts = opsFor(c.facet).joinToString("") { (v, l) -> "<option value=\"$v\"${if (c.op == v) " selected" else ""}>${l.esc()}</option>" }
    val valEditor = when (c.facet) {
        "track_title" -> {
            """<input class="input wb-text" data-path="$p" placeholder="e.g. Commentary, SDH, Synstolkning" value="${(c.values.firstOrNull() ?: "").esc()}">"""
        }
        "tag" -> {
            fun tagChip(t: TrackFacetItem): String {
                val dot = if (t.color != null) """<span class="tag-dot" style="background:${t.color}"></span>""" else ""
                val cnt = if (t.count > 0) """<span class="wb-vcount">${t.count}</span>""" else ""
                return """<span class="wb-vchip${if (c.values.contains(t.value)) " on" else ""}" data-path="$p" data-v="${t.value.esc()}">$dot${t.value.esc()}$cnt</span>"""
            }
            val (js, other) = wbItemsFor("tag").take(80).partition { it.color != null }
            buildString {
                if (js.isNotEmpty()) append("""<div class="wb-vgroup">Jellystructure tags</div><div class="wb-vchips">${js.joinToString("") { tagChip(it) }}</div>""")
                if (other.isNotEmpty()) append("""<div class="wb-vgroup">Other tags</div><div class="wb-vchips">${other.joinToString("") { tagChip(it) }}</div>""")
            }
        }
        "content_row" -> {
            val chips = wbRowsContext.joinToString("") { row ->
                val on = c.rows.any { it.id == row.id }
                val name = row.title?.takeIf { it.isNotBlank() } ?: "Untitled row"
                """<span class="wb-vchip${if (on) " on" else ""}" data-path="$p" data-row="${row.id.esc()}">${name.esc()}</span>"""
            }
            """<div class="wb-vchips">${chips.ifEmpty { """<span class="muted tiny">No content rows in scope</span>""" }}</div>"""
        }
        else -> {
            val chips = wbItemsFor(c.facet).take(80).joinToString("") { t ->
                val on = c.values.contains(t.value)
                val lbl = if (c.facet == "hero_item") (if (t.value == "featured") "Featured" else "Not featured") else t.value
                val cnt = if (t.count > 0) """<span class="wb-vcount">${t.count}</span>""" else ""
                """<span class="wb-vchip${if (on) " on" else ""}" data-path="$p" data-v="${t.value.esc()}">${lbl.esc()}$cnt</span>"""
            }
            """<div class="wb-vchips">$chips</div>"""
        }
    }
    return """<div class="wb-cond">
          <div class="wb-cond-head">
            <select class="input wb-facet" data-path="$p">${facetOpts.replace("value=\"${c.facet}\"", "value=\"${c.facet}\" selected")}</select>
            <select class="input wb-op" data-path="$p">$opOpts</select>
            <span class="spacer"></span>
            <span class="wb-rm" data-path="$p" style="cursor:pointer;opacity:.6;">✕</span>
          </div>
          $valEditor
       </div>"""
}

private fun wbRenderSummary() {
    val host = document.getElementById("wb-summary") as? HTMLElement ?: return
    val summary = groupSummary(wbRoot, top = true)
    host.textContent = if (summary.isBlank()) "No conditions yet — this filter matches everything." else summary
}

// ─── Wiring ─────────────────────────────────────────────────────────────────────

private fun wbWireChrome() {
    document.querySelectorAll("#wb-overlay .wb-include span").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                wbInclude = el.getAttribute("data-inc") ?: "all"
                document.querySelectorAll("#wb-overlay .wb-include span").let { all ->
                    for (j in 0 until all.length) { val e = all.item(j) as? HTMLElement ?: continue
                        if (e.getAttribute("data-inc") == wbInclude) e.classList.add("on") else e.classList.remove("on") }
                }
                wbRefreshPreview()
            } }
    }
    document.getElementById("wb-close")?.addEventListener("click") { closeWorkbench() }
    document.getElementById("wb-cancel")?.addEventListener("click") { closeWorkbench() }
    document.getElementById("wb-apply")?.addEventListener("click") {
        val result = wbRoot.toSharedGroup()
        if (wbChannelMode) {
            val style = if (wbChStyle == "text") "TEXT" else "LOGO"
            val logo = if (wbChStyle == "logo") wbLogoUrl else null
            wbOnSaveChannel?.invoke(style, wbChColor, logo, result, wbInclude)
        } else {
            wbOnApply?.invoke(result, wbInclude)
        }
        closeWorkbench()
    }
}

private fun wbWireBlocks() {
    (document.querySelector("#wb-blocks [data-rootjoin]") as? HTMLElement)?.addEventListener("click") {
        wbRoot.join = if (wbRoot.join == "or") "and" else "or"
        wbRenderBlocks(); wbRefreshPreview()
    }
    document.querySelectorAll("#wb-blocks [data-blockjoin]").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val path = parsePath(el.getAttribute("data-blockjoin") ?: return@addEventListener)
                val g = groupAt(path)
                g.join = if (g.join == "or") "and" else "or"
                wbRenderBlocks(); wbRefreshPreview()
            } }
    }
    document.querySelectorAll("#wb-blocks [data-notpath]").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val path = parsePath(el.getAttribute("data-notpath") ?: return@addEventListener)
                val g = groupAt(path)
                g.not = !g.not
                wbRenderBlocks(); wbRefreshPreview()
            } }
    }
    document.querySelectorAll("#wb-blocks [data-rmpath]").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val path = parsePath(el.getAttribute("data-rmpath") ?: return@addEventListener)
                removeNodeAt(path)
                wbRenderBlocks(); wbRefreshPreview()
            } }
    }
    document.querySelectorAll("#wb-blocks .wb-add-cond").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val path = parsePath(el.getAttribute("data-path") ?: return@addEventListener)
                groupAt(path).children.add(WbCond("studio", "is_any_of"))
                wbRenderBlocks(); wbRefreshPreview()
            } }
    }
    // + Sub-block defaults to the OPPOSITE join of its parent — mixing operators is the only reason to nest.
    document.querySelectorAll("#wb-blocks .wb-add-sub").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val path = parsePath(el.getAttribute("data-path") ?: return@addEventListener)
                val parent = groupAt(path)
                val oppositeJoin = if (parent.join == "or") "and" else "or"
                parent.children.add(WbGroup(oppositeJoin, children = mutableListOf(WbCond("studio", "is_any_of"))))
                wbRenderBlocks(); wbRefreshPreview()
            } }
    }
    (document.getElementById("wb-add-block") as? HTMLElement)?.addEventListener("click") {
        wbRoot.children.add(WbGroup("or", children = mutableListOf(WbCond("studio", "is_any_of"))))
        wbRenderBlocks(); wbRefreshPreview()
    }
    wbWireCondRows()
}

private fun wbWireCondRows() {
    document.querySelectorAll("#wb-blocks .wb-facet").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLSelectElement ?: continue
            el.addEventListener("change") {
                val path = parsePath(el.getAttribute("data-path") ?: return@addEventListener)
                val c = condAt(path) ?: return@addEventListener
                c.facet = el.value; c.op = opsFor(c.facet).first().first; c.values.clear()
                wbRenderBlocks(); wbRefreshPreview()
            } }
    }
    document.querySelectorAll("#wb-blocks .wb-op").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLSelectElement ?: continue
            el.addEventListener("change") {
                val path = parsePath(el.getAttribute("data-path") ?: return@addEventListener)
                condAt(path)?.op = el.value; wbRefreshPreview(); wbRenderSummary()
            } }
    }
    document.querySelectorAll("#wb-blocks .wb-cond .wb-rm").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val path = parsePath(el.getAttribute("data-path") ?: return@addEventListener)
                removeNodeAt(path)
                wbRenderBlocks(); wbRefreshPreview()
            } }
    }
    document.querySelectorAll("#wb-blocks .wb-vchip:not([data-row])").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val path = parsePath(el.getAttribute("data-path") ?: return@addEventListener)
                val v = el.getAttribute("data-v") ?: return@addEventListener
                val c = condAt(path) ?: return@addEventListener
                if (c.facet == "hero_item") { c.values.clear(); c.values.add(v) }   // single-select
                else if (c.values.contains(v)) c.values.remove(v) else c.values.add(v)
                wbRenderBlocks(); wbRefreshPreview()
            } }
    }
    document.querySelectorAll("#wb-blocks .wb-text").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLInputElement ?: continue
            el.addEventListener("input") {
                val path = parsePath(el.getAttribute("data-path") ?: return@addEventListener)
                val c = condAt(path) ?: return@addEventListener
                c.values.clear(); if (el.value.isNotBlank()) c.values.add(el.value)
                wbRefreshPreview(); wbRenderSummary()
            } }
    }
    // R87: content_row chips toggle membership in c.rows (by row id), resolved from the rows context.
    document.querySelectorAll("#wb-blocks .wb-vchip[data-row]").let { els ->
        for (i in 0 until els.length) { val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val path = parsePath(el.getAttribute("data-path") ?: return@addEventListener)
                val rid = el.getAttribute("data-row") ?: return@addEventListener
                val c = condAt(path) ?: return@addEventListener
                val existing = c.rows.indexOfFirst { it.id == rid }
                if (existing >= 0) c.rows.removeAt(existing)
                else wbRowsContext.firstOrNull { it.id == rid }?.let { c.rows.add(it) }
                wbRenderBlocks(); wbRefreshPreview()
            } }
    }
}

/** R73/R74/Phase 140: reusable count helper — sends a query tree to /api/media and returns the
 *  result page. Caller can pass pageSize=1 for a cheap count-only call or larger for a preview grid. */
internal suspend fun countMatching(
    query: ConditionGroup,
    include: String,
    viewer: String? = null,
    pageSize: Int = 1,
): MediaPage? = MediaApi.list(
    kind = when (include) { "movies" -> MediaKind.MOVIE; "series" -> MediaKind.TV_SHOW; else -> null },
    pageSize = pageSize,
    viewer = viewer,
    query = query,
)

/** Walks the tree, collecting every group's path (root's top-level blocks + every nested sub-block). */
private fun collectBlockPaths(n: WbNode, path: List<Int>, acc: MutableList<List<Int>>) {
    if (n is WbGroup) {
        acc.add(path)
        n.children.forEachIndexed { i, child -> collectBlockPaths(child, path + i, acc) }
    }
}

/** Phase 140 §C.2 — one POST /api/media/batch-count call per refresh for every block's own live
 *  count badge (channel tree AND that block, in channel scope; else the block alone), plus the
 *  locked block's own count when present. Never N separate GET /api/media counts. */
private fun wbRefreshCounts() {
    val scope = wbScope ?: return
    scope.launch {
        val paths = mutableListOf<List<Int>>()
        wbRoot.children.forEachIndexed { i, child -> collectBlockPaths(child, listOf(i), paths) }
        val baseTree = wbBaseRoot?.toSharedGroup()?.takeIf { it.isLive() }
        val blockQueries = paths.map { p ->
            val blockTree = groupAt(p).toSharedGroup()
            if (baseTree != null) ConditionGroup(QueryJoin.AND, children = listOf(baseTree, blockTree)) else blockTree
        }
        val allQueries = if (baseTree != null) blockQueries + baseTree else blockQueries
        val requests = allQueries.mapIndexed { i, q -> BatchCountRequest(index = i, query = q) }
        val results = MediaApi.batchCount(requests) ?: return@launch
        for (i in paths.indices) {
            val total = results.getOrNull(i)?.total ?: continue
            (document.querySelector("#wb-blocks [data-countpath=\"${pathStr(paths[i])}\"]") as? HTMLElement)?.textContent = "$total"
        }
        if (baseTree != null) {
            wbLockedCount = results.getOrNull(paths.size)?.total
            (document.querySelector("#wb-blocks .wb-block-locked .wb-block-count") as? HTMLElement)?.textContent =
                wbLockedCount?.let { "$it in channel" } ?: "—"
        }
    }
}

private fun wbRefreshPreview() {
    val scope = wbScope ?: return
    val countEl = document.getElementById("wb-count") as? HTMLElement
    countEl?.innerHTML = """<span class="muted tiny">Computing…</span>"""
    scope.launch {
        val rootTree = wbRoot.toSharedGroup()
        val baseTree = wbBaseRoot?.toSharedGroup()?.takeIf { it.isLive() }
        val fullTree = if (baseTree != null) ConditionGroup(QueryJoin.AND, children = listOf(baseTree, rootTree)) else rootTree
        val page = countMatching(fullTree, wbInclude, viewer = wbViewer, pageSize = 18)
        val total = page?.total ?: 0
        countEl?.innerHTML = if (baseTree != null) "<b>$total title(s)</b> in channel" else "<b>$total title(s) match</b>"
        val prev = document.getElementById("wb-preview") as? HTMLElement
        prev?.innerHTML = (page?.items ?: emptyList()).joinToString("") { m ->
            val img = if (!m.posterPath.isNullOrBlank()) """<img src="${posterSrc(m.posterPath, "https://image.tmdb.org/t/p/w185")}" alt="">""" else """<div class="wb-noimg">${m.title.take(2).esc()}</div>"""
            """<div class="wb-pcard" title="${m.title.esc()}">$img</div>"""
        }
    }
    wbRefreshCounts()
}

private fun wbCustomCss(): String =
    if (wbCustomType == "solid") wbCustomC1 else "linear-gradient(${wbCustomAngle}deg, $wbCustomC1, $wbCustomC2)"

private fun wbChannelChip(): String {
    val fill = wbChColor.ifEmpty { WB_CH_PRESETS[0] }
    val inner = if (wbChStyle == "logo" && !wbLogoUrl.isNullOrBlank()) {
        """<img src="${wbLogoUrl!!.esc()}" alt="">"""
    } else {
        (if (wbChStyle == "logo") wbChName.take(3).uppercase() else wbChName.take(12).ifEmpty { "Collection" }).esc()
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
        .wb-modal { background:var(--fill); color:var(--ink); border:1px solid var(--line); box-shadow:var(--shadow); border-radius:16px; width:min(960px,100%); padding:18px; }
        .wb-head { display:flex; align-items:center; gap:6px; margin-bottom:14px; }
        .wb-body { display:flex; gap:18px; align-items:flex-start; }
        .wb-main { flex:1; min-width:0; }
        .wb-side { width:220px; flex:none; }
        .wb-summary { margin:8px 2px 14px; line-height:1.4; }
        .wb-jpill { display:inline-flex; align-items:center; justify-content:center; font-size:.68rem; font-weight:800; letter-spacing:.03em; color:var(--ink-soft); background:var(--fill-2); border:1px solid var(--line-2); border-radius:99px; padding:2px 10px; margin:6px 0; cursor:pointer; }
        .wb-jpill:hover { color:var(--ink); border-color:var(--hi); }
        .wb-jpill-root { margin-left:0; }
        .wb-jpill-locked { cursor:default; opacity:.7; }
        .wb-jpill-inner { margin:6px 0; }
        .wb-block { border:1px solid var(--line); border-radius:12px; padding:12px; margin-bottom:4px; background:var(--fill-2); }
        .wb-block-locked { background:var(--fill-3); border-style:dashed; }
        .wb-block.wb-not-on { border-color:var(--bad); background:color-mix(in srgb, var(--bad) 8%, var(--fill-2)); }
        .wb-block-head { display:flex; align-items:center; gap:6px; margin-bottom:8px; }
        .wb-lock-eyebrow { font-size:.72rem; font-weight:700; color:var(--ink-soft); }
        .wb-block-count { font-size:.72rem; color:var(--ink-soft); white-space:nowrap; }
        .wb-not-toggle { font-size:.66rem; font-weight:800; letter-spacing:.04em; padding:2px 8px; border-radius:6px; border:1px dashed var(--line-2); color:var(--ink-soft); cursor:pointer; }
        .wb-not-toggle.on { border-color:var(--bad); color:var(--bad); border-style:solid; }
        .wb-block-foot { display:flex; gap:8px; margin-top:8px; }
        .wb-subblock-wrap { margin:6px 0 6px 18px; padding-left:10px; border-left:2px solid var(--line-2); }
        .wb-cond { border:1px solid var(--line); border-radius:11px; padding:10px; margin-bottom:6px; background:var(--fill); }
        .wb-cond-head { display:flex; align-items:center; gap:8px; margin-bottom:8px; }
        .wb-facet, .wb-op { padding:4px 8px; }
        .wb-vchips { display:flex; flex-wrap:wrap; gap:6px; max-height:120px; overflow:auto; }
        .wb-vchip { display:inline-flex; align-items:center; gap:6px; padding:3px 9px; border-radius:18px; border:1px solid var(--line-2); background:var(--fill-2); color:var(--ink); cursor:pointer; font-size:.76rem; }
        .wb-vchip.on { background:var(--hi); border-color:transparent; color:#fff; }
        .wb-vcount { font-size:.66rem; line-height:1; padding:1px 6px; border-radius:99px; background:var(--fill-2); border:1px solid var(--line); color:var(--ink-soft); }
        .wb-vchip.on .wb-vcount { background:rgba(255,255,255,.18); border-color:transparent; color:#fff; }
        .wb-vgroup { width:100%; font-size:.66rem; text-transform:uppercase; letter-spacing:.06em; color:var(--ink-soft); margin:6px 0 2px; }
        .wb-vgroup:first-child { margin-top:0; }
        .wb-count { margin:0 0 8px; font-size:.9rem; color:var(--ink); }
        .wb-preview { display:grid; grid-template-columns:repeat(auto-fill,minmax(70px,1fr)); gap:7px; max-height:420px; overflow:auto; }
        .wb-pcard { aspect-ratio:2/3; border-radius:7px; overflow:hidden; background:var(--fill-3); }
        .wb-pcard img { width:100%; height:100%; object-fit:cover; }
        .wb-noimg { width:100%; height:100%; display:flex; align-items:center; justify-content:center; font-weight:700; color:var(--ink-soft); }
        .wb-foot { display:flex; gap:8px; align-items:center; margin-top:14px; }
        .wbc-hr { border:none; border-top:1px dashed var(--line); margin:16px 0; }
        .wbc-flex { display:flex; align-items:flex-start; gap:18px; flex-wrap:wrap; }
        .wbc-lbl { font-size:.72rem; color:var(--ink-soft); margin-bottom:6px; }
        .wbc-sws { display:flex; gap:7px; flex-wrap:wrap; align-items:center; }
        .wbc-sw { width:26px; height:26px; border-radius:8px; cursor:pointer; border:2px solid transparent; box-sizing:border-box; }
        .wbc-sw.on { border-color:var(--ink); box-shadow:0 0 0 2px var(--fill); }
        .wbc-custom { display:flex; align-items:center; justify-content:center; color:var(--ink-soft); font-size:1.1rem; background:var(--fill-3); }
        .wbc-chip { width:120px; height:46px; border-radius:10px; display:flex; align-items:center; justify-content:center; color:#fff; font-weight:700; font-size:.78rem; overflow:hidden; }
        .wbc-chip img { width:100%; height:100%; object-fit:cover; }
        .wbc-grad { display:flex; align-items:center; gap:14px; flex-wrap:wrap; margin-top:12px; }
        .wbc-cf { display:flex; flex-direction:column; gap:3px; }
        .wbc-cf input[type=color] { width:46px; height:30px; border:none; background:none; padding:0; cursor:pointer; }
        .wbc-cf input[type=range] { width:100%; }
        .wbc-grid { display:flex; gap:9px; flex-wrap:wrap; }
        .wbc-tile { width:74px; height:46px; border-radius:9px; border:1px solid var(--line-2); background:var(--fill-2); cursor:pointer; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:2px; overflow:hidden; color:var(--ink-soft); }
        .wbc-tile.on { border-color:var(--hi); box-shadow:0 0 0 2px var(--hi); }
        .wbc-tile img { max-width:84%; max-height:70%; object-fit:contain; }

        /* Phase 140 §F — comfortable tap targets on touch devices (ported from design/app/ravilo-builders.css). */
        @media (pointer: coarse) {
            .wb-facet, .wb-op { padding:9px 12px; }
            .wb-vchip { padding:8px 12px; }
            .wb-jpill { padding:7px 14px; }
            .wb-not-toggle { padding:7px 11px; }
            .wb-rm, .wb-block-rm { padding:7px 9px; }
        }
        /* narrow screens: the modal becomes a bottom sheet, the side (matches) panel stacks below the
           query, tighter rails — ported from the design's @media (max-width: 760px) block. */
        @media (max-width: 760px) {
            .wb-overlay { padding:0; align-items:flex-end; }
            .wb-modal { max-height:94dvh; border-radius:16px 16px 0 0; border-left:none; border-right:none; border-bottom:none; width:100%; overflow-y:auto; }
            .wb-body { flex-direction:column; }
            .wb-main { padding:0; }
            .wb-side { width:auto; }
            .wb-subblock-wrap { margin-left:10px; padding-left:8px; }
            .wb-block { padding:9px; border-radius:10px; }
            .wb-cond { padding:9px; }
        }
    """.trimIndent()
    document.head?.appendChild(style)
}
