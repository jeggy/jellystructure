package dev.jellystructure.ui

import dev.jellystructure.Router
import dev.jellystructure.api.SegKind
import dev.jellystructure.api.SegSource
import dev.jellystructure.api.SegmentApi
import dev.jellystructure.api.SegmentEpisodeRow
import dev.jellystructure.api.SegmentSheetResponse
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import kotlin.math.roundToInt

/**
 * Phase 163 — the intro/credits editor. Fullscreen, chrome-less (see Main.kt's shellMounted tracking):
 * a season sheet (every episode on one aligned timeline) with a read-only drawer preview per episode.
 * Bulk actions/consensus-apply/lock (step 3), the trim view (step 4/5), waveform + Jellyfin read-in
 * (step 6) land in later steps of this same build.
 */

private data class KindMeta(val label: String, val color: String, val cls: String)

private val KIND_META = mapOf(
    SegKind.RECAP to KindMeta("Recap", "var(--info)", "k-recap"),
    SegKind.INTRO to KindMeta("Intro", "var(--hi)", "k-intro"),
    SegKind.PREVIEW to KindMeta("Next time", "#4fd1c5", "k-preview"),
    SegKind.CREDITS to KindMeta("Credits", "var(--warn)", "k-credits"),
    SegKind.STINGER to KindMeta("After-credits", "var(--acc-a)", "k-stinger"),
)

private fun kindOf(k: String) = KIND_META[k] ?: KindMeta(k, "var(--ink-dim)", "")

private fun fmt(sec: Double): String {
    val s = sec.roundToInt().coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun fmtl(sec: Double): String {
    val s = sec.roundToInt().coerceAtLeast(0)
    return "${(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
}

private fun legendHtml(kinds: List<String> = SegKind.ORDER): String =
    "<div class=\"legend\">" + kinds.joinToString("") { k ->
        val m = kindOf(k)
        "<span><i style=\"background:${m.color}\"></i>${m.label}</span>"
    } + "</div>"

/** Read-only segment bars for the drawer preview — no drag handles (those land in step 4's trim view). */
private fun segBarsHtml(row: SegmentEpisodeRow): String {
    if (row.durationSec <= 0) return ""
    return row.segments.joinToString("") { s ->
        val startSec = s.startMs / 1000.0
        val endSec = (s.endMs ?: s.startMs) / 1000.0
        val left = startSec / row.durationSec * 100
        val width = ((endSec - startSec) / row.durationSec * 100).coerceAtLeast(0.6)
        val m = kindOf(s.kind)
        val lockCls = if (s.locked) " lk" else ""
        "<div class=\"seg ${m.cls}$lockCls\" style=\"left:${left}%;width:${width}%\">" +
            (if (width > 6) m.label else "") + "</div>"
    }
}

private fun statusFor(row: SegmentEpisodeRow): Pair<String, String> = when {
    row.segments.isEmpty() -> "d-bad" to "nothing found — needs a look"
    row.outlier -> "d-bad" to "disagrees with the season"
    row.segments.any { it.source == SegSource.HEURISTIC && (it.confidence ?: 1.0) < 0.60 } && !row.checked ->
        "d-warn" to "guessed, not checked yet"
    row.checked -> "d-ok" to "checked" + (if (row.segments.any { it.locked }) " · locked" else "")
    else -> "d-idle" to "matched, not checked"
}

private var openIndex = 0

fun renderSegments(scope: CoroutineScope, query: Map<String, String>) {
    val body = document.body ?: return
    body.innerHTML = """<div class="sx" id="seg-root"><div class="sxbar"><span class="sxsub">Loading…</span></div></div><div id="toasts"></div>"""
    openIndex = 0

    val series = query["series"]
    val season = query["season"]?.toIntOrNull()
    val movie = query["movie"]
    val filter = query["filter"]
    val episodeKey = query["episode"]

    scope.launch {
        when {
            // TV episode trim view (step 4/5) — placeholder until that step lands later in this build.
            series != null && episodeKey != null -> renderTrimPlaceholder()
            filter != null -> {
                val sheet = SegmentApi.crossLibrarySheet(filter)
                if (sheet == null) renderSegmentsError() else renderSheet(sheet, scope)
            }
            series != null && season != null -> {
                val sheet = SegmentApi.seasonSheet(series, season)
                if (sheet == null) renderSegmentsError() else renderSheet(sheet, scope)
            }
            // Movies have no season sheet (spec §A) — a bare ?movie= opens straight into the trim view.
            movie != null -> {
                val sheet = SegmentApi.movieSheet(movie)
                if (sheet == null) renderSegmentsError() else renderTrimPlaceholder(sheet)
            }
            else -> renderSegmentsError()
        }
    }
}

private fun renderSegmentsError() {
    val root = document.getElementById("seg-root") ?: return
    root.innerHTML = """
        <div class="sxbar"><a class="sxback" href="#/dashboard">‹ Dashboard</a><h1>Intro &amp; credits</h1></div>
        <div class="sxmain" style="grid-template-columns:1fr"><div class="sxstage">
          <p class="sxhint">Couldn't load this title's segments — it may have been removed from the library.</p>
        </div></div>
    """.trimIndent()
}

/** Step 4/5 placeholder — replaced with the real trim view (timeline, handles, evidence, playback)
 *  later in this same build. Movies open straight into the trim view (no season sheet behind them). */
private fun renderTrimPlaceholder(movieSheet: SegmentSheetResponse? = null) {
    val root = document.getElementById("seg-root") ?: return
    val back = if (movieSheet != null) """<a class="sxback" href="#/media/${movieSheet.itemId}">‹ ${movieSheet.title}</a>"""
        else """<a class="sxback" href="javascript:history.back()">‹ Back</a>"""
    root.innerHTML = """
        <div class="sxbar">$back<h1>${movieSheet?.title ?: "Trim view"}</h1></div>
        <div class="sxmain" style="grid-template-columns:1fr"><div class="sxstage">
          <p class="sxhint">The full trim editor (timeline, playback, evidence) lands later in this build.</p>
        </div></div>
    """.trimIndent()
}

private fun renderSheet(sheet: SegmentSheetResponse, scope: CoroutineScope) {
    val root = document.getElementById("seg-root") ?: return
    if (openIndex >= sheet.episodes.size) openIndex = 0
    val maxDur = sheet.episodes.maxOfOrNull { it.durationSec }?.takeIf { it > 0 } ?: 1.0
    val ci = sheet.consensus.firstOrNull { it.kind == SegKind.INTRO }

    val rowsHtml = sheet.episodes.mapIndexed { idx, row ->
        val (dotCls, statusTxt) = statusFor(row)
        val oddCls = if (row.outlier || row.segments.isEmpty()) " odd" else ""
        val openCls = if (idx == openIndex) " open" else ""
        val laneInner = if (row.segments.isEmpty()) """<span class="lane-empty">nothing marked</span>"""
            else row.segments.joinToString("") { s ->
                val startSec = s.startMs / 1000.0
                val endSec = (s.endMs ?: s.startMs) / 1000.0
                val left = startSec / maxDur * 100
                val width = ((endSec - startSec) / maxDur * 100).coerceAtLeast(0.9)
                val m = kindOf(s.kind)
                val oddm = if (row.outlier && s.kind == SegKind.INTRO) " oddm" else ""
                """<i class="$oddm" style="left:${left}%;width:${width}%;background:${m.color}"></i>"""
            }
        val titleCell = if (sheet.kind == "cross") "${row.itemTitle ?: ""} · ${row.title}" else row.title
        """<div class="srow$openCls$oddCls" data-r="$idx">
             <span></span>
             <span class="id">${row.code}</span>
             <span class="et">$titleCell</span>
             <div class="lane">$laneInner</div>
             <span class="stt"><span class="dot $dotCls"></span>$statusTxt</span>
             <span class="pubc">${if (row.segments.any { it.locked }) "<span class=\"src me\" title=\"locked — detection will not touch it\">🔒</span>" else ""}</span>
           </div>"""
    }.joinToString("")

    val found = sheet.stats.found
    val open = sheet.episodes.getOrNull(openIndex)
    val drawerHtml = if (open != null) buildDrawer(open) else ""

    val backHref = when (sheet.kind) {
        "cross" -> """href="#/dashboard""""
        else -> """href="#/media/${sheet.itemId}""""
    }
    val backLabel = if (sheet.kind == "cross") "‹ Dashboard" else "‹ ${sheet.title}"
    val subtitle = if (sheet.seasonName != null) "${sheet.title} · ${sheet.seasonName} · ${sheet.episodes.size} episodes" else sheet.title

    root.innerHTML = """
        <div class="sxbar"><a class="sxback" $backHref>$backLabel</a><h1>Intro &amp; credits</h1>
          <span class="sxsub">$subtitle</span><span class="sxsp"></span>
        </div>
        <div class="sxmain" style="grid-template-columns:1fr"><div class="sxstage">
          <div class="stat">
            <div class="scard"><b>$found / ${sheet.stats.total}</b><span>intro and credits found</span></div>
            <div class="scard"><b style="color:var(--warn)">${sheet.stats.lowConfidence}</b><span>guessed, not checked yet</span></div>
            <div class="scard"><b style="color:var(--bad)">${sheet.stats.outliers}</b><span>disagree with the season</span></div>
            <div class="scard"><b style="color:var(--ok)">${sheet.stats.locked}</b><span>locked by you</span></div>
            <div class="scard"><b>${ciLabel(ci)}</b><span>what the season agrees on</span></div>
          </div>
          <div class="sheet">
            <div class="sh"><span></span><span class="lbl">Ep</span><span class="lbl thead">Title</span>
              <div class="shl"><span class="lbl">Aligned on one timeline — an odd one out sticks out</span>${legendHtml()}</div>
              <span class="lbl">State</span><span class="lbl"></span></div>
            <div class="srows">$rowsHtml</div>
          </div>
          $drawerHtml
        </div></div>
    """.trimIndent()

    root.querySelectorAll("[data-r]").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val idx = el.getAttribute("data-r")?.toIntOrNull() ?: return@addEventListener
                openIndex = idx
                renderSheet(sheet, scope)
            }
        }
    }
    root.querySelector("[data-a='open-editor']")?.addEventListener("click") {
        val row = sheet.episodes.getOrNull(openIndex) ?: return@addEventListener
        if (sheet.kind == "tv") {
            Router.navigate("/segments", mapOf("series" to row.mediaId, "episode" to row.episodeKey, "episodeNumber" to row.episodeNumber.toString()))
        } else {
            Router.navigate("/segments", mapOf("movie" to row.mediaId))
        }
    }
}

private fun ciLabel(ci: dev.jellystructure.api.SegmentConsensus?): String {
    val start = ci?.startMs ?: return "—"
    val end = ci.endMs ?: start
    return "${fmt(start / 1000.0)} → ${fmt(end / 1000.0)}"
}

private fun buildDrawer(row: SegmentEpisodeRow): String {
    val bars = segBarsHtml(row)
    val hint = when {
        row.outlier -> "This episode's intro lands well away from the rest of the season — worth a look before it's trusted."
        row.segments.isNotEmpty() -> "Looks like the rest of the season. Open the editor if you want to watch the cut before signing it off."
        else -> "Nothing was detected yet. Open the editor to set the boundaries by hand, or wait for the next detect_segments run."
    }
    return """
        <div class="drawer"><div class="vid"><div class="ph"><em>${fmtl(row.durationSec)}</em>${row.code}${if (row.segments.isEmpty()) " · nothing marked" else ""}</div></div>
          <div class="dcol">
            <div class="drow"><b>${row.code} · ${row.title}</b><span class="sxsp"></span>
              <button class="btn sm ghost" data-a="open-editor">Open the full editor →</button></div>
            <div class="track" style="height:32px"><div class="grid"></div>$bars</div>
            <div class="sxhint">$hint</div>
          </div>
        </div>
    """.trimIndent()
}
