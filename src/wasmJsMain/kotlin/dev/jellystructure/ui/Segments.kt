package dev.jellystructure.ui

import dev.jellystructure.Router
import dev.jellystructure.api.EvType
import dev.jellystructure.api.SegKind
import dev.jellystructure.api.SegSource
import dev.jellystructure.api.SegmentApi
import dev.jellystructure.api.SegmentDto
import dev.jellystructure.api.SegmentEpisodeRef
import dev.jellystructure.api.SegmentEpisodeRow
import dev.jellystructure.api.SegmentEvidenceDto
import dev.jellystructure.api.SegmentRailItem
import dev.jellystructure.api.SegmentSheetResponse
import dev.jellystructure.api.SegmentTrimResponse
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.KeyboardEvent
import kotlin.math.abs
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

/** Phase 189 (FR-189-2) — marker-row timecodes, with tenths: the ± steppers move by a whole second
 *  (below), and `fmtl`'s whole-second rounding made every click look like nothing happened. */
private fun fmtlt(sec: Double): String {
    val totalTenths = (sec * 10).roundToInt().coerceAtLeast(0)
    val s = totalTenths / 10
    val tenths = totalTenths % 10
    return "${(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}.$tenths"
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

private fun statusFrom(segments: List<SegmentDto>, checked: Boolean, outlier: Boolean): Pair<String, String> = when {
    segments.isEmpty() -> "d-bad" to "nothing found — needs a look"
    outlier -> "d-bad" to "disagrees with the season"
    segments.any { it.source == SegSource.HEURISTIC && (it.confidence ?: 1.0) < 0.60 } && !checked ->
        "d-warn" to "guessed, not checked yet"
    checked -> "d-ok" to "checked" + (if (segments.any { it.locked }) " · locked" else "")
    else -> "d-idle" to "matched, not checked"
}

private fun statusFor(row: SegmentEpisodeRow) = statusFrom(row.segments, row.checked, row.outlier)
private fun statusForRail(r: SegmentRailItem) = statusFrom(r.segments, r.checked, r.outlier)

private var openIndex = 0
private val picked = mutableSetOf<Int>()

private fun toast(msg: String) {
    val container = document.getElementById("toasts") ?: return
    val t = document.createElement("div") as HTMLElement
    t.className = "sxtoast"
    t.innerHTML = msg
    container.appendChild(t)
    window.setTimeout({ t.className = "sxtoast out"; null }, 2600)
    window.setTimeout({ t.remove(); null }, 3100)
}

/** Phase 164 (FR-164-7) — the redetect toast is now literally true: detection is enqueued onto the
 *  segments job lane (Activity ▸ Jobs & workers), not started inline, and a double-click (or a
 *  redetect racing an already-queued pipeline sweep for the same unit) reports back as already-queued
 *  rather than silently starting a second run. */
private fun redetectToast(subject: String, result: dev.jellystructure.api.SegmentRedetectResult): String = when {
    !result.ok -> "Couldn't queue detection for $subject — try again"
    result.enqueued > 0 && result.deduped > 0 -> "Queued detection for $subject (${result.deduped} already queued) — see Activity ▸ Jobs &amp; workers"
    result.enqueued > 0 -> "Queued detection for $subject — see Activity ▸ Jobs &amp; workers"
    else -> "Already queued for $subject"
}

// Step 4 — trim-view state + the keydown listener are page-lifetime module state, not per-render
// locals: the listener is attached once (document.body is replaced wholesale on every /segments visit,
// but `document` itself never is) and always reads the latest currentTrimData/trimState rather than
// closing over a stale render's data. currentTrimData is nulled outside the trim view so a stray
// keypress on any other page (sheet, or after navigating away entirely) is always a safe no-op.
private var currentTrimData: SegmentTrimResponse? = null
private var currentTrimScope: CoroutineScope? = null
private var keydownWired = false
private var teardownWired = false
private var trimSelectedKind: String? = null
private var trimLastEdge = "b"   // "a" | "b" — which edge , / . nudges next
private var trimPlayheadMs = 0L
// Phase 190 — set once the server's stream-mode decision comes back (wireVideo); null until then.
// "direct" = Static=true, unchanged behaviour. "remux" = a live video-copy/audio-transcode session:
// not byte-range seekable (confirmed live against Jellyfin 10.11.11 — see SegmentRoutes.kt's doc), so
// every seek reloads the stream from a new offset instead of setting video.currentTime in place.
private var trimStreamMode: String? = null
private var trimPlaySessionId: String = ""
// The absolute ms offset the CURRENT video.src was loaded from, when trimStreamMode == "remux" (always
// 0 for "direct", where a single persistent resource covers the whole file). A remux reload's own
// loadedmetadata reports only the duration REMAINING from this offset, not the file's real length —
// correctDuration() only trusts a load where this is 0 (the initial open, always at the real start).
private var trimRemuxBaseMs = 0L

fun renderSegments(scope: CoroutineScope, query: Map<String, String>) {
    val body = document.body ?: return
    // The default UA body margin would otherwise leave a few px of unreachable scroll around .sx even
    // once .sx itself is correctly bounded to the viewport (see segments.css's .sx rule).
    body.style.margin = "0"
    body.innerHTML = """<div class="sx" id="seg-root"><div class="sxbar"><span class="sxsub">Loading…</span></div></div><div id="toasts"></div>"""
    openIndex = 0
    picked.clear()
    currentTrimData = null
    wireKeydownOnce()
    wireStreamTeardownOnce()

    val series = query["series"]
    val season = query["season"]?.toIntOrNull()
    val movie = query["movie"]
    val filter = query["filter"]
    val episodeKey = query["episode"]
    val episodeNumber = query["episodeNumber"]?.toIntOrNull() ?: 0

    scope.launch {
        when {
            series != null && episodeKey != null -> {
                val data = SegmentApi.episodeTrim(series, episodeKey, episodeNumber)
                if (data == null) renderSegmentsError() else renderTrim(data, scope)
            }
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
                val data = SegmentApi.movieTrim(movie)
                if (data == null) renderSegmentsError() else renderTrim(data, scope)
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

private val SRC_META = mapOf(
    SegSource.FINGERPRINT to ("fp" to "matched across episodes"),
    SegSource.HEURISTIC to ("he" to "guessed from black frames"),
    SegSource.CHAPTER to ("ch" to "from a chapter mark"),
    SegSource.JELLYFIN to ("jf" to "from Jellyfin"),
    SegSource.TMDB to ("jf" to "from TMDB"),
    SegSource.MANUAL to ("me" to "set by you"),
)

private fun fmtConf(c: Double): String {
    val scaled = (c * 100).roundToInt().coerceIn(0, 999)
    return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
}

private fun srcChipHtml(s: SegmentDto): String {
    val (cls, label) = SRC_META[s.source] ?: return ""
    val conf = s.confidence?.let { " · ${fmtConf(it)}" } ?: ""
    return """<span class="src $cls">$label$conf</span>"""
}

/** Extracts one string field out of a segment_evidence `detail` JSON blob (e.g. `{"pairedEpisode":"S05E12"}`)
 *  without pulling in a full JSON parser for a single-field, server-controlled payload. */
private fun extractJsonField(json: String?, field: String): String? {
    if (json == null) return null
    val marker = "\"$field\":\""
    val idx = json.indexOf(marker)
    if (idx < 0) return null
    val start = idx + marker.length
    val end = json.indexOf('"', start)
    return if (end < 0) null else json.substring(start, end)
}

private fun miniHtml(durationSec: Double, segments: List<SegmentDto>): String {
    if (durationSec <= 0 || segments.isEmpty()) return """<div class="mini"></div>"""
    val bars = segments.joinToString("") { s ->
        val startSec = s.startMs / 1000.0
        val endSec = (s.endMs ?: s.startMs) / 1000.0
        val left = startSec / durationSec * 100
        val width = ((endSec - startSec) / durationSec * 100).coerceAtLeast(1.2)
        """<i style="left:${left}%;width:${width}%;background:${kindOf(s.kind).color}"></i>"""
    }
    return """<div class="mini">$bars</div>"""
}

private fun buildRuler(durationSec: Double): String {
    if (durationSec <= 0) return """<div class="ruler"></div>"""
    val marks = StringBuilder()
    var t = 0.0
    while (t <= durationSec) {
        val pct = t / durationSec * 100
        marks.append("""<i style="left:${pct}%"></i><u style="left:${pct}%">${fmt(t)}</u>""")
        t += 120.0
    }
    return """<div class="ruler" id="seg-ruler">$marks</div>"""
}

private fun buildTrack(segments: List<SegmentDto>, durationSec: Double, selectedKind: String?): String {
    if (durationSec <= 0) return ""
    return segments.joinToString("") { s ->
        val startSec = s.startMs / 1000.0
        val endSec = (s.endMs ?: s.startMs) / 1000.0
        val left = startSec / durationSec * 100
        val width = ((endSec - startSec) / durationSec * 100).coerceAtLeast(0.5)
        val m = kindOf(s.kind)
        val lockCls = if (s.locked) " lk" else ""
        val onCls = if (s.kind == selectedKind) " on" else ""
        val handles = if (!s.locked) """<i class="h l" data-e="a"></i><i class="h r" data-e="b"></i>""" else ""
        """<div class="seg ${m.cls}$lockCls$onCls" data-s="${s.kind}" style="left:${left}%;width:${width}%">${if (width > 6) m.label else ""}$handles</div>"""
    }
}

private fun buildEvidenceLane(evidence: List<SegmentEvidenceDto>, durationSec: Double): String {
    if (durationSec <= 0 || evidence.isEmpty()) return ""
    val bars = evidence.joinToString("") { e ->
        val startSec = e.startMs / 1000.0
        val endSec = (e.endMs ?: e.startMs) / 1000.0
        val left = startSec / durationSec * 100
        val width = ((endSec - startSec) / durationSec * 100).coerceAtLeast(0.4)
        when (e.evidenceType) {
            EvType.BLACK_FRAME -> """<span class="b blk" style="left:${left}%;width:${width}%" title="black frames"></span>"""
            EvType.SILENCE -> """<span class="b blk" style="left:${left}%;width:${width}%" title="silence"></span>"""
            EvType.FINGERPRINT_MATCH -> {
                val label = extractJsonField(e.detail, "pairedEpisode")?.let { "matches $it" } ?: "fingerprint match"
                """<span class="b fp" style="left:${left}%;width:${width}%">$label</span>"""
            }
            EvType.CHAPTER_CANDIDATE -> {
                val title = extractJsonField(e.detail, "title") ?: "chapter mark"
                """<span class="ch" style="left:${left}%" title="$title"></span>"""
            }
            else -> ""
        }
    }
    return """<div class="ev" id="seg-evidence"><span class="evlbl">why</span>$bars</div>"""
}

private fun buildMarkRow(s: SegmentDto, selected: Boolean): String {
    val m = kindOf(s.kind)
    val startSec = s.startMs / 1000.0
    val endSec = (s.endMs ?: s.startMs) / 1000.0
    return """<div class="mk${if (selected) " sel" else ""}${if (s.locked) " lkd" else ""}" data-m="${s.kind}">
        <span class="sw" style="background:${m.color}"></span><span class="nm">${m.label}</span>
        <span class="tc"><span class="stp"><button data-d="-1" data-e="a" data-k="${s.kind}">−</button><button data-d="1" data-e="a" data-k="${s.kind}">+</button></span><span class="ts-a">${fmtlt(startSec)}</span>
          <s>→</s><span class="ts-b">${fmtlt(endSec)}</span><span class="stp"><button data-d="-1" data-e="b" data-k="${s.kind}">−</button><button data-d="1" data-e="b" data-k="${s.kind}">+</button></span>
          <s class="len">${fmt(endSec - startSec)} long</s><span class="src-slot">${srcChipHtml(s)}</span></span>
        <span class="acts"><button class="btn sm ghost" data-p="${s.kind}">▶ play the cut</button>
          <button class="lockb${if (s.locked) " on" else ""}" data-l="${s.kind}">${if (s.locked) "🔒 locked" else "🔓 lock"}</button>
          <button class="btn sm ghost" data-remove="${s.kind}" title="Remove this marker">✕ Remove</button></span>
      </div>"""
}

private fun buildAddRow(missing: List<String>, mediaKind: String): String {
    if (missing.isEmpty()) return ""
    val unit = if (mediaKind == "movie") "film — normal for one" else "episode"
    val names = missing.joinToString(", ") { kindOf(it).label.lowercase() }
    val buttons = missing.joinToString("") { k -> """<button class="btn sm ghost" data-add="$k">＋ ${kindOf(k).label}</button>""" }
    return """<div class="mk add"><span class="sw" style="background:var(--fill-3)"></span>
        <span class="sxhint">No $names in this $unit.</span>
        <span class="acts">$buttons</span></div>"""
}

private fun buildRail(data: SegmentTrimResponse): String {
    val rows = data.rail.joinToString("") { r ->
        val (dotCls, statusTxt) = statusForRail(r)
        val onCls = if (r.episodeKey == data.episodeKey && r.episodeNumber == data.episodeNumber) " on" else ""
        """<div class="qr$onCls" data-q="${r.episodeKey}␟${r.episodeNumber}"><span class="id">${r.code}</span>
             <div><div class="t">${r.title}</div><div class="st">$statusTxt</div>${miniHtml(r.durationSec, r.segments)}</div>
             <span class="dot $dotCls"></span></div>"""
    }
    return """<div class="rh"><b>Season</b><span class="sxsp"></span><span class="lbl">${data.checkedCount} of ${data.totalCount} checked</span></div>
        <div class="q">$rows</div>
        <div class="qfoot"><div class="keys">
          <div><span class="kbd">I</span><span class="kbd">O</span>set in / out at the playhead</div>
          <div><span class="kbd">,</span><span class="kbd">.</span>nudge a frame · <span class="kbd">⇧</span> for a second</div>
          <div><b>−</b><b>+</b> on a row nudges by a second</div>
          <div><span class="kbd">L</span>lock the selected marker</div>
          <div><span class="kbd">↵</span>save and open the next episode</div></div>
          <div class="sxhint">A locked marker survives every future <b>detect_segments</b> run — that is the whole point of the lock.</div>
          <a class="sxlink" href="#/settings?tab=libraries">Detection settings →</a></div>"""
}

private fun renderTrim(data: SegmentTrimResponse, scope: CoroutineScope) {
    val root = document.getElementById("seg-root") ?: return
    val isNewTitle = currentTrimData?.mediaId != data.mediaId || currentTrimData?.episodeKey != data.episodeKey || currentTrimData?.episodeNumber != data.episodeNumber
    if (isNewTitle) {
        // Phase 190 (FR-190-6) — leaving this title behind means whatever remux session it opened is
        // about to be abandoned; release it before moving on (same reasoning as Phase 180's teardown —
        // an abandoned transcode keeps running for nobody). Safe to call even when the previous title
        // was direct-play (blank playSessionId, stopStream no-ops).
        if (trimPlaySessionId.isNotBlank()) { val psid = trimPlaySessionId; scope.launch { SegmentApi.stopStream(psid) } }
        trimSelectedKind = data.segments.firstOrNull()?.kind
        trimLastEdge = "b"
        trimPlayheadMs = 0L
        trimStreamMode = null
        trimPlaySessionId = ""
        trimRemuxBaseMs = 0L
    } else if (trimSelectedKind != null && data.segments.none { it.kind == trimSelectedKind }) {
        trimSelectedKind = data.segments.firstOrNull()?.kind
    }
    currentTrimData = data
    currentTrimScope = scope

    val selected = data.segments.firstOrNull { it.kind == trimSelectedKind }

    val backHtml = if (data.kind == "movie") """<a class="sxback" href="#/media/${data.mediaId}">‹ ${data.itemTitle}</a>"""
        else """<a class="sxback" href="#" data-a="back">‹ Season ${data.seasonNumber}</a>"""
    val confirmChip = if (data.checked) """<span class="src me">confirmed</span>""" else """<span class="src he">not confirmed — Jellyfin will not get it yet</span>"""
    val nextLabel = if (data.kind == "movie") "Save &amp; next film →" else "Save &amp; next episode →"
    val playPill = selected?.let { s -> val m = kindOf(s.kind); """<span class="vpill" style="color:${m.color};border-color:${m.color}44">▍${m.label}</span>""" } ?: ""
    val playheadSec = trimPlayheadMs / 1000.0
    val phLabel = selected?.let { "${kindOf(it.kind).label.lowercase()} selected" } ?: "click the timeline to move the playhead"

    root.innerHTML = """
        <div class="sxbar">$backHtml
          <span class="num">${data.code}</span><h1>${data.title}</h1><span class="sxsub" id="seg-duration-label">${fmtl(data.durationSec)}</span>
          <span class="sxsp"></span>$confirmChip
          <button class="btn sm" data-a="redetect-one">↻ Re-detect</button>
          <button class="btn sm pri" data-a="next">$nextLabel</button></div>
        <div class="sxmain" style="grid-template-columns:1fr${if (data.kind == "tv") " 322px" else ""}"><div class="sxstage">
          <div class="vid" id="seg-vid">
            <video id="seg-video" style="width:100%;height:100%;object-fit:contain;display:none" preload="metadata"></video>
            <div class="ph" id="seg-ph"><em>${fmtl(playheadSec)}</em>$phLabel</div>
            <div class="tag">$playPill</div>
            <div class="tag2" id="seg-vid-tag2"><span class="vpill">checking playback…</span></div>
            <div class="foot">
              <span class="vbtn pri" id="seg-play-btn">▶</span>
              <span class="vbtn" data-a="fb">◂◂</span>
              <span class="vbtn" data-a="ff">▸▸</span>
              <span class="sxsp"></span>
              <span class="vpill mono" id="seg-timecode">${fmtl(playheadSec)} / ${fmtl(data.durationSec)}</span>
            </div>
          </div>
          <div class="tl" id="seg-tl">${buildTimelineInnerHtml(data, trimSelectedKind, playheadSec)}</div>
          <div class="mks" id="seg-mks">${buildMarksInnerHtml(data, trimSelectedKind)}</div>
          <div id="seg-jf-candidates"></div>
        </div>
        ${if (data.kind == "tv") """<div class="sxrail" id="seg-rail">${buildRail(data)}</div>""" else ""}
        </div>
    """.trimIndent()

    wireTrim(root, data, scope)
}

/** The `.tl` block's inner content — extracted so [refreshTrimBody] can rebuild it in place (a
 *  structural change: lock icon, handle presence, marker set) without touching `.vid`/`<video>`. */
private fun buildTimelineInnerHtml(data: SegmentTrimResponse, selectedKind: String?, playheadSec: Double): String = """
    <div class="tlh"><span class="lbl">Timeline</span>${legendHtml()}</div>
    ${buildRuler(data.durationSec)}
    <div class="track" id="seg-track">
      <div class="grid"></div>${buildTrack(data.segments, data.durationSec, selectedKind)}
      <div class="play" id="seg-playhead" style="left:${if (data.durationSec > 0) playheadSec / data.durationSec * 100 else 0}%"></div>
    </div>
    <div class="wave" id="seg-wave"></div>
    ${buildEvidenceLane(data.evidence, data.durationSec)}
""".trimIndent()

/** The `.mks` block's inner content — see [buildTimelineInnerHtml]. */
private fun buildMarksInnerHtml(data: SegmentTrimResponse, selectedKind: String?): String {
    val missing = SegKind.ORDER.filterNot { k -> data.segments.any { it.kind == k } }
    return data.segments.joinToString("") { buildMarkRow(it, it.kind == selectedKind) } + buildAddRow(missing, data.kind)
}

/**
 * Phase 189 (FR-189-3) — re-fetches and rebuilds the timeline, marker rows and (on a series) the rail,
 * but never touches `.vid`/`<video>` — so locking,
 * removing, adding a marker or applying one of Jellyfin's own candidates never restarts playback. Used
 * by every action that changes WHICH markers exist or their locked state (structural changes a per-field
 * DOM patch like [patchSegmentDom] can't express); [applyEditInPlace] handles the narrower "same markers,
 * new times" case without even a refetch.
 */
private fun refreshTrimBody(scope: CoroutineScope) {
    val data = currentTrimData ?: return
    scope.launch {
        val fresh = if (data.kind == "movie") SegmentApi.movieTrim(data.mediaId) else SegmentApi.episodeTrim(data.mediaId, data.episodeKey, data.episodeNumber)
        if (fresh == null) {
            toast("Couldn't refresh this title — try reloading the page")
            return@launch
        }
        if (trimSelectedKind != null && fresh.segments.none { it.kind == trimSelectedKind }) {
            trimSelectedKind = fresh.segments.firstOrNull()?.kind
        }
        currentTrimData = fresh
        val root = document.getElementById("seg-root") ?: return@launch

        document.getElementById("seg-duration-label")?.textContent = fmtl(fresh.durationSec)
        (document.querySelector(".sxbar .src") as? HTMLElement)?.let { chip ->
            chip.className = if (fresh.checked) "src me" else "src he"
            chip.textContent = if (fresh.checked) "confirmed" else "not confirmed — Jellyfin will not get it yet"
        }
        document.getElementById("seg-tl")?.innerHTML = buildTimelineInnerHtml(fresh, trimSelectedKind, trimPlayheadMs / 1000.0)
        document.getElementById("seg-mks")?.innerHTML = buildMarksInnerHtml(fresh, trimSelectedKind)
        if (fresh.kind == "tv") document.getElementById("seg-rail")?.innerHTML = buildRail(fresh)
        updatePlayheadDom(trimPlayheadMs, fresh.durationSec, currentTrimSelectedLabel())

        wireEditableRegion(root, fresh, scope)
        wireWaveform(fresh, scope)
        wireJellyfinCandidates(fresh, scope)
    }
}

// Phase 189 — split from one wireTrim into "chrome wired once" (this function; back link, redetect/next,
// transport buttons, the <video> element itself) and wireEditableRegion (everything refreshTrimBody
// replaces and must therefore re-wire on every structural change: locking, adding, removing a marker).
private fun wireTrim(root: Element, data: SegmentTrimResponse, scope: CoroutineScope) {
    if (data.kind == "tv") {
        root.querySelector("[data-a='back']")?.addEventListener("click") { ev ->
            ev.preventDefault()
            Router.navigate("/segments", mapOf("series" to data.mediaId, "season" to data.seasonNumber.toString()))
        }
    }
    root.querySelector("[data-a='redetect-one']")?.addEventListener("click") {
        val live = currentTrimData ?: data
        scope.launch {
            val result = if (live.kind == "movie") SegmentApi.redetect(movie = live.mediaId)
            else SegmentApi.redetect(items = listOf(SegmentEpisodeRef(live.mediaId, live.episodeKey, live.episodeNumber)))
            toast(redetectToast(live.code, result) + " — locked markers are skipped")
        }
    }
    root.querySelector("[data-a='next']")?.addEventListener("click") { goNext(currentTrimData ?: data, scope) }
    root.querySelector("[data-a='fb']")?.addEventListener("click") { seekRelative(currentTrimData ?: data, -10_000, scope) }
    root.querySelector("[data-a='ff']")?.addEventListener("click") { seekRelative(currentTrimData ?: data, 10_000, scope) }
    root.querySelector("#seg-play-btn")?.addEventListener("click") {
        val video = document.getElementById("seg-video") as? HTMLVideoElement ?: return@addEventListener
        if (video.paused) video.play() else video.pause()
    }

    wireEditableRegion(root, data, scope)
    wireVideo(data, scope)
    wireWaveform(data, scope)
    wireJellyfinCandidates(data, scope)
}

// Phase 189 (FR-189-3) — everything refreshTrimBody() replaces (the rail, marker rows, the track's
// select/seek listeners) lives here so both the initial render and every later structural refresh
// (lock/remove/add/apply-Jellyfin) wire the SAME set, freshly, without touching `.vid`/`<video>`.
// Reads currentTrimData rather than closing over the `data` this was called with, so it never goes
// stale across a later edit — the keydown listener already follows this rule; this makes the mouse
// paths match it.
private fun wireEditableRegion(root: Element, data: SegmentTrimResponse, scope: CoroutineScope) {
    if (data.kind == "tv") {
        root.querySelectorAll("[data-q]").let { nodes ->
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? HTMLElement ?: continue
                el.addEventListener("click") {
                    val live = currentTrimData ?: data
                    val parts = (el.getAttribute("data-q") ?: return@addEventListener).split("␟")
                    Router.navigate("/segments", mapOf("series" to live.mediaId, "episode" to parts[0], "episodeNumber" to parts.getOrElse(1) { "0" }))
                }
            }
        }
    }
    // Select a marker (click the row, not one of its buttons). Marker SELECTION only changes which bar
    // is highlighted — a lightweight in-place restyle, never renderTrim (which would restart the video).
    root.querySelectorAll("[data-m]").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") { ev ->
                val target = ev.target as? Element
                if (target?.closest("button") != null) return@addEventListener
                selectMarker(el.getAttribute("data-m"))
            }
        }
    }
    // Select a marker by clicking its bar on the track (not a resize handle).
    root.querySelectorAll(".seg[data-s]").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            el.addEventListener("mousedown") { ev ->
                val target = ev.target as? Element
                if (target?.classList?.contains("h") == true) return@addEventListener
                selectMarker(el.getAttribute("data-s"))
            }
        }
    }
    // Click empty track space (ruler included) to move the playhead — a lightweight DOM update, not a
    // full re-render: re-rendering would tear down and recreate <video>, restarting the stream.
    (root.querySelector("#seg-track") as? HTMLElement)?.addEventListener("click") { ev ->
        val live = currentTrimData ?: data
        val target = ev.target as? Element
        if (target?.classList?.contains("grid") != true) return@addEventListener
        val box = (ev.target as HTMLElement).getBoundingClientRect()
        val me = ev as org.w3c.dom.events.MouseEvent
        val frac = ((me.clientX - box.left) / box.width).coerceIn(0.0, 1.0)
        seekToAbsoluteMs(live, (frac * live.durationSec * 1000).toLong(), scope)
    }

    root.querySelectorAll("[data-l]").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val live = currentTrimData ?: data
                val kind = el.getAttribute("data-l") ?: return@addEventListener
                val seg = live.segments.firstOrNull { it.kind == kind } ?: return@addEventListener
                scope.launch {
                    // FR-189-6 — every write's result is checked; a failed one is reported instead of
                    // silently claiming success (refreshTrimBody would then just show the old state,
                    // indistinguishable from the click having done nothing).
                    if (SegmentApi.setLock(live.mediaId, kind, live.episodeKey, live.episodeNumber, !seg.locked)) {
                        toast(if (!seg.locked) "${kindOf(kind).label} locked — detection will not touch it" else "${kindOf(kind).label} unlocked")
                    } else {
                        toast("Couldn't ${if (!seg.locked) "lock" else "unlock"} ${kindOf(kind).label} — try again")
                    }
                    refreshTrimBody(scope)
                }
            }
        }
    }
    root.querySelectorAll("[data-remove]").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val live = currentTrimData ?: data
                val kind = el.getAttribute("data-remove") ?: return@addEventListener
                scope.launch {
                    if (SegmentApi.deleteSegment(live.mediaId, kind, live.episodeKey, live.episodeNumber)) {
                        toast("${kindOf(kind).label} removed")
                    } else {
                        toast("Couldn't remove ${kindOf(kind).label} — try again")
                    }
                    refreshTrimBody(scope)
                }
            }
        }
    }
    root.querySelectorAll(".stp button").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val kind = el.getAttribute("data-k") ?: return@addEventListener
                val edge = el.getAttribute("data-e") ?: return@addEventListener
                val delta = el.getAttribute("data-d")?.toIntOrNull() ?: return@addEventListener
                // FR-189-2 — a coarse 1s step per click, not the 40ms frame-nudge (that stays on `,`/`.`).
                nudgeSegment(currentTrimData ?: data, scope, kind, edge, delta * 1000L)
            }
        }
    }
    root.querySelectorAll("[data-add]").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val live = currentTrimData ?: data
                val kind = el.getAttribute("data-add") ?: return@addEventListener
                val endAnchored = kind == SegKind.CREDITS || kind == SegKind.STINGER || kind == SegKind.PREVIEW
                val durMs = (live.durationSec * 1000).toLong()
                val start = if (endAnchored) (durMs - 60_000).coerceAtLeast(0) else 30_000L
                val end = (start + 40_000).coerceAtMost(durMs)
                scope.launch {
                    if (SegmentApi.editSegment(live.mediaId, kind, live.episodeKey, live.episodeNumber, start, end)) {
                        trimSelectedKind = kind
                        toast("${kindOf(kind).label} added — drag the handles or nudge the timecodes")
                    } else {
                        toast("Couldn't add ${kindOf(kind).label} — try again")
                    }
                    refreshTrimBody(scope)
                }
            }
        }
    }
    root.querySelectorAll("[data-p]").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val live = currentTrimData ?: data
                val kind = el.getAttribute("data-p") ?: return@addEventListener
                val seg = live.segments.firstOrNull { it.kind == kind } ?: return@addEventListener
                playCut(seg, live, scope)
            }
        }
    }

    wireDragHandles(root, data, scope)
}

/** Highlights a different marker WITHOUT a full renderTrim() — restyles the `.mk`/`.seg` `sel`/`on`
 *  classes in place (video, track positions and every listener untouched). */
private fun selectMarker(kind: String?) {
    trimSelectedKind = kind
    document.querySelectorAll(".mk[data-m]").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            el.classList.toggle("sel", el.getAttribute("data-m") == kind)
        }
    }
    document.querySelectorAll(".seg[data-s]").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            el.classList.toggle("on", el.getAttribute("data-s") == kind)
        }
    }
    val data = currentTrimData
    val selected = data?.segments?.firstOrNull { it.kind == kind }
    if (data != null) {
        (document.querySelector(".tag") as? HTMLElement)?.innerHTML = selected?.let { s ->
            val m = kindOf(s.kind); """<span class="vpill" style="color:${m.color};border-color:${m.color}44">▍${m.label}</span>"""
        } ?: ""
    }
    updatePlayheadDom(trimPlayheadMs, data?.durationSec ?: 0.0, currentTrimSelectedLabel())
}

/** Step 6 — fetched once per render (not per timeupdate tick — the peaks don't change during playback),
 *  so a plain targeted innerHTML update is fine here, unlike the playhead. */
private fun wireWaveform(data: SegmentTrimResponse, scope: CoroutineScope) {
    if (data.durationSec <= 0) return
    scope.launch {
        val peaks = SegmentApi.waveform(data.mediaId, data.episodeKey, data.episodeNumber, 0, (data.durationSec * 1000).toLong())
        if (peaks != null) {
            document.getElementById("seg-wave")?.innerHTML = peaks.joinToString("") { p -> """<i style="height:${p.coerceAtLeast(1)}%"></i>""" }
        }
    }
}

/** Step 6 — Jellyfin's own markers, offered as candidates only. Empty (no provider plugin installed) is
 *  the normal case on this server, not an error — the section just renders nothing. */
private fun wireJellyfinCandidates(data: SegmentTrimResponse, scope: CoroutineScope) {
    scope.launch {
        val candidates = SegmentApi.jellyfinCandidates(data.mediaId, data.episodeKey, data.episodeNumber)
        val container = document.getElementById("seg-jf-candidates") ?: return@launch
        if (candidates.isEmpty()) return@launch
        container.innerHTML = """<div class="mk add"><span class="sw" style="background:var(--info)"></span>
            <span class="sxhint">Jellyfin has its own marker${if (candidates.size == 1) "" else "s"} for this title — never applied automatically.</span>
            <span class="acts">${candidates.joinToString("") { c ->
                """<button class="btn sm ghost" data-jf-apply="${c.kind}" data-jf-start="${c.startMs}" data-jf-end="${c.endMs ?: c.startMs}">Use Jellyfin's ${kindOf(c.kind).label} →</button>"""
            }}</span></div>"""
        container.querySelectorAll("[data-jf-apply]").let { nodes ->
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? HTMLElement ?: continue
                el.addEventListener("click") {
                    val kind = el.getAttribute("data-jf-apply") ?: return@addEventListener
                    val start = el.getAttribute("data-jf-start")?.toLongOrNull() ?: return@addEventListener
                    val end = el.getAttribute("data-jf-end")?.toLongOrNull()
                    scope.launch {
                        if (SegmentApi.editSegment(data.mediaId, kind, data.episodeKey, data.episodeNumber, start, end)) {
                            toast("${kindOf(kind).label} set from Jellyfin")
                        } else {
                            toast("Couldn't apply Jellyfin's ${kindOf(kind).label} — try again")
                        }
                        refreshTrimBody(scope)
                    }
                }
            }
        }
    }
}

private fun currentTrimSelectedLabel(): String? = trimSelectedKind?.let { "${kindOf(it).label.lowercase()} selected" }

/** Updates only the playhead marker + timecode DOM directly — never a full renderTrim(), which would
 *  tear down and recreate <video>, restarting the stream. Used by the video's own timeupdate listener
 *  (fires ~4x/sec during playback) and every playhead-only interaction (track click, seek buttons). */
private fun updatePlayheadDom(ms: Long, durationSec: Double, phLabel: String?) {
    val pct = if (durationSec > 0) (ms / 1000.0 / durationSec * 100) else 0.0
    (document.getElementById("seg-playhead") as? HTMLElement)?.style?.left = "$pct%"
    document.getElementById("seg-timecode")?.textContent = "${fmtl(ms / 1000.0)} / ${fmtl(durationSec)}"
    val ph = document.getElementById("seg-ph") as? HTMLElement
    if (ph != null && ph.style.display != "none") {
        ph.innerHTML = "<em>${fmtl(ms / 1000.0)}</em>${phLabel ?: "click the timeline to move the playhead"}"
    }
}

private fun seekVideoTo(ms: Long) {
    (document.getElementById("seg-video") as? HTMLVideoElement)?.currentTime = ms / 1000.0
}

/** Phase 190 — the absolute playhead position implied by the `<video>`'s own `currentTime`. Trivial for
 *  direct play (one persistent resource covers the whole file); a remux session's `currentTime` is
 *  relative to whatever offset it was last reloaded FROM ([trimRemuxBaseMs]). */
private fun currentVideoAbsoluteMs(video: HTMLVideoElement): Long =
    if (trimStreamMode == "remux") trimRemuxBaseMs + (video.currentTime * 1000).toLong()
    else (video.currentTime * 1000).toLong()

/**
 * Phase 190 (FR-190-5) — the one seek entry point every interaction (track click, ±10s, "play the cut")
 * goes through. Direct play seeks in place — `video.currentTime`, works natively. A remux session is a
 * live transcode with no known total size — confirmed live against Jellyfin 10.11.11 that it responds
 * `Accept-Ranges: none` — so it is NOT byte-range seekable as a single persistent resource. Seeking
 * therefore means reloading the stream from the new offset (`StartTimeTicks`), the same pattern
 * Jellyfin's own web client uses for exactly this situation (also confirmed live: `StartTimeTicks` is
 * honoured and seeks efficiently via ffmpeg's own `-ss`, not by decoding from zero).
 */
private fun seekToAbsoluteMs(data: SegmentTrimResponse, ms: Long, scope: CoroutineScope, resumePlaying: Boolean? = null) {
    val clamped = ms.coerceIn(0L, (data.durationSec * 1000).toLong())
    trimPlayheadMs = clamped
    updatePlayheadDom(trimPlayheadMs, data.durationSec, currentTrimSelectedLabel())
    if (trimStreamMode != "remux") {
        seekVideoTo(clamped)
        if (resumePlaying == true) (document.getElementById("seg-video") as? HTMLVideoElement)?.play()
        return
    }
    val video = document.getElementById("seg-video") as? HTMLVideoElement ?: return
    val wasPlaying = resumePlaying ?: !video.paused
    trimRemuxBaseMs = clamped
    scope.launch {
        val info = SegmentApi.streamInfo(data.mediaId, data.episodeKey.ifEmpty { null }, data.episodeNumber, startMs = clamped)
        if (info == null) { toast("Couldn't seek — try again"); return@launch }
        video.src = info.url
        if (wasPlaying) video.play()
    }
}

private fun seekRelative(data: SegmentTrimResponse, deltaMs: Long, scope: CoroutineScope) {
    seekToAbsoluteMs(data, trimPlayheadMs + deltaMs, scope)
}

/** "▶ play the cut" — seeks 3s before the marker's start, plays, and auto-pauses 3s after its end via
 *  a one-shot timeupdate listener (removed the moment it fires, so repeated plays don't stack listeners). */
private fun playCut(seg: SegmentDto, data: SegmentTrimResponse, scope: CoroutineScope) {
    val video = document.getElementById("seg-video") as? HTMLVideoElement
    if (video == null || video.style.display == "none") {
        toast("Playback isn't available for this title yet — it may not be matched in Jellyfin, or this browser can't play it here")
        return
    }
    val startMs = (seg.startMs - 3000).coerceAtLeast(0L)
    val endMs = (seg.endMs ?: seg.startMs) + 3000
    seekToAbsoluteMs(data, startMs, scope, resumePlaying = true)
    lateinit var stopHandler: (org.w3c.dom.events.Event) -> Unit
    stopHandler = {
        if (currentVideoAbsoluteMs(video) >= endMs) {
            video.pause()
            video.removeEventListener("timeupdate", stopHandler)
        }
    }
    video.addEventListener("timeupdate", stopHandler)
}

private fun wireVideo(data: SegmentTrimResponse, scope: CoroutineScope) {
    val video = document.getElementById("seg-video") as? HTMLVideoElement ?: return
    val tag2 = document.getElementById("seg-vid-tag2")
    val ph = document.getElementById("seg-ph") as? HTMLElement
    val playBtn = document.getElementById("seg-play-btn") as? HTMLElement

    video.addEventListener("loadedmetadata") {
        video.style.display = "block"
        ph?.style?.display = "none"
        // Phase 190 (FR-190-3) — the badge must never claim "direct play" for a file whose audio was
        // actually re-encoded; the reported bug is exactly a file that LOOKS like it's playing fine.
        tag2?.innerHTML = if (trimStreamMode == "remux")
            """<span class="vpill ok">audio re-encoded so your browser can play it · video untouched</span>"""
        else """<span class="vpill ok">direct play · no transcode</span>"""
        // A remux RELOAD (seeking mid-session) starts its own ffmpeg process at the requested offset
        // and reports only the duration REMAINING from there, not the file's real length — only the
        // very first load (trimRemuxBaseMs == 0: true for direct play always, and for a fresh remux
        // open at position 0) is trusted to correct the timeline.
        if (trimRemuxBaseMs == 0L) {
            if (trimStreamMode != "remux") video.currentTime = trimPlayheadMs / 1000.0
            correctDuration(video.duration)
        }
    }
    video.addEventListener("error") {
        video.style.display = "none"
        tag2?.innerHTML = """<span class="vpill warn">can't play this file here — use the timecodes below</span>"""
    }
    video.addEventListener("timeupdate") {
        trimPlayheadMs = currentVideoAbsoluteMs(video)
        updatePlayheadDom(trimPlayheadMs, data.durationSec, currentTrimSelectedLabel())
    }
    video.addEventListener("play") { playBtn?.textContent = "❚❚" }
    video.addEventListener("pause") { playBtn?.textContent = "▶" }

    scope.launch {
        // Phase 190 — request from wherever the playhead already is (0 on a fresh open; a resumed
        // position on the same title). Harmless for a "direct" response — the server only honours
        // startMs in remux mode (SegmentRoutes.kt) — but saves a mode-aware branch here.
        val info = SegmentApi.streamInfo(data.mediaId, data.episodeKey.ifEmpty { null }, data.episodeNumber, startMs = trimPlayheadMs)
        if (info == null) {
            tag2?.innerHTML = """<span class="vpill warn">not matched in Jellyfin yet</span>"""
            return@launch
        }
        trimStreamMode = info.mode
        trimPlaySessionId = info.playSessionId
        trimRemuxBaseMs = if (info.mode == "remux") trimPlayheadMs else 0L
        video.src = info.url
    }
}

// The backend's durationSec is only a rough estimate (TMDB's whole-minute runtime, or the furthest
// known segment edge — see SegmentRoutes.kt's durationSecOf) used so the timeline has something to draw
// before playback exists. Once the real <video> loads, its duration is the frame-accurate truth — patch
// the duration-dependent pieces of the DOM in place rather than a full renderTrim (which would recreate
// the <video> element and restart the stream).
// Phase 189 (FR-189-1) — this used to replace #seg-track's innerHTML wholesale, which destroys every
// .h drag-handle and .seg bar-select listener wireDragHandles/wireTrim attached — the handles die a few
// hundred milliseconds after the view appears (a whole-minute TMDB estimate vs. a real file's duration
// essentially never agree within 1s), before an operator can reach them. The segment SET doesn't change
// here, only the scale each bar is positioned against, so there is nothing to rebuild — patch each
// existing bar's left/width in place, the same way updatePlayheadDom already patches the playhead.
private fun correctDuration(newDurationSec: Double) {
    val data = currentTrimData ?: return
    if (newDurationSec.isNaN() || !newDurationSec.isFinite() || newDurationSec <= 0) return
    if (abs(newDurationSec - data.durationSec) < 1.0) return
    val corrected = data.copy(durationSec = newDurationSec)
    currentTrimData = corrected

    document.getElementById("seg-duration-label")?.textContent = fmtl(newDurationSec)
    document.getElementById("seg-ruler")?.outerHTML = buildRuler(newDurationSec)
    document.getElementById("seg-evidence")?.outerHTML = buildEvidenceLane(corrected.evidence, newDurationSec)
    for (seg in corrected.segments) repositionSegmentBar(seg, newDurationSec)
    updatePlayheadDom(trimPlayheadMs, newDurationSec, currentTrimSelectedLabel())
}

/** Phase 189 — the shared bar-positioning math `buildTrack` uses for a fresh render, applied to an
 *  EXISTING `.seg` element's style instead of rebuilding it, so its drag-handle/select listeners survive.
 *  No-op if the bar isn't in the DOM (e.g. a kind just removed). */
private fun repositionSegmentBar(seg: SegmentDto, durationSec: Double) {
    if (durationSec <= 0) return
    val bar = document.querySelector(".seg[data-s='${seg.kind}']") as? HTMLElement ?: return
    val startSec = seg.startMs / 1000.0
    val endSec = (seg.endMs ?: seg.startMs) / 1000.0
    bar.style.left = "${startSec / durationSec * 100}%"
    bar.style.width = "${((endSec - startSec) / durationSec * 100).coerceAtLeast(0.5)}%"
}

private fun nudgeSegment(data: SegmentTrimResponse, scope: CoroutineScope, kind: String, edge: String, deltaMs: Long) {
    val seg = data.segments.firstOrNull { it.kind == kind }
    if (seg == null) return
    if (seg.locked) {
        // FR-189-5 — "nothing happens" is the exact symptom this phase exists to eliminate; a locked
        // marker must say so rather than silently ignoring the click, indistinguishable from broken.
        toast("${kindOf(kind).label} is locked — unlock it to change the time")
        return
    }
    trimLastEdge = edge
    val start = seg.startMs
    val end = seg.endMs ?: seg.startMs
    val newStart = if (edge == "a") (start + deltaMs).coerceIn(0, end - 100) else start
    val newEnd = if (edge == "b") (end + deltaMs).coerceAtLeast(newStart + 100) else end
    applyEditInPlace(data, seg, newStart, newEnd, scope)
}

/**
 * Phase 189 (FR-189-1/3/6) — writes an edit and patches the changed marker's bar + row DIRECTLY, never a
 * full renderTrim() (which tears down and recreates `<video>`, restarting the stream — the exact thing
 * this file's own "patch the DOM you own" idiom forbids elsewhere; see updatePlayheadDom's doc comment).
 * Used by the ± steppers, drag handles and the I/O keyboard shortcuts — every path that
 * changes an existing marker's start/end without changing which markers exist.
 *
 * Optimistic: the DOM is patched immediately (a click must always visibly change something), then
 * reverted if the write turns out to have failed, so a rejected/failed write can never again look
 * identical to nothing having happened.
 */
private fun applyEditInPlace(data: SegmentTrimResponse, seg: SegmentDto, newStart: Long, newEnd: Long, scope: CoroutineScope) {
    val kind = seg.kind
    val patched = seg.copy(startMs = newStart, endMs = newEnd, source = SegSource.MANUAL, confidence = null)
    currentTrimData = data.copy(segments = data.segments.map { if (it.kind == kind) patched else it })
    patchSegmentDom(patched, data.durationSec)
    scope.launch {
        val ok = SegmentApi.editSegment(data.mediaId, kind, data.episodeKey, data.episodeNumber, newStart, newEnd)
        if (ok) {
            toast("${kindOf(kind).label} now ${fmtl(newStart / 1000.0)} → ${fmtl(newEnd / 1000.0)} · saved")
        } else {
            toast("Couldn't save the ${kindOf(kind).label} change — reverted")
            currentTrimData = currentTrimData?.copy(segments = currentTrimData!!.segments.map { if (it.kind == kind) seg else it }) ?: data
            patchSegmentDom(seg, data.durationSec)
        }
    }
}

/** Patches one marker's timeline bar position + its row's timecodes/length/source badge in place —
 *  never rebuilds either element, so their button/drag-handle listeners survive. */
private fun patchSegmentDom(seg: SegmentDto, durationSec: Double) {
    repositionSegmentBar(seg, durationSec)
    val startSec = seg.startMs / 1000.0
    val endSec = (seg.endMs ?: seg.startMs) / 1000.0
    val row = document.querySelector(".mk[data-m='${seg.kind}']") as? Element
    row?.querySelector(".ts-a")?.textContent = fmtlt(startSec)
    row?.querySelector(".ts-b")?.textContent = fmtlt(endSec)
    row?.querySelector(".len")?.textContent = "${fmt(endSec - startSec)} long"
    row?.querySelector(".src-slot")?.innerHTML = srcChipHtml(seg)
}

private fun goNext(data: SegmentTrimResponse, scope: CoroutineScope) {
    scope.launch {
        SegmentApi.setChecked(listOf(SegmentEpisodeRef(data.mediaId, data.episodeKey, data.episodeNumber)))
        if (data.kind == "movie") {
            toast("${data.title} confirmed")
            Router.navigate("/media/${data.mediaId}")
            return@launch
        }
        val next = data.rail.firstOrNull { !it.checked && (it.episodeKey != data.episodeKey || it.episodeNumber != data.episodeNumber) }
        if (next != null) {
            toast("${data.code} confirmed · opened ${next.code}")
            Router.navigate("/segments", mapOf("series" to data.mediaId, "episode" to next.episodeKey, "episodeNumber" to next.episodeNumber.toString()))
        } else {
            toast("Every episode in the season is checked")
            Router.navigate("/segments", mapOf("series" to data.mediaId, "season" to data.seasonNumber.toString()))
        }
    }
}

// Phase 189 (FR-189-1) — wired ONCE per navigation (called from wireTrim only); it must go on working
// for as long as the view is open, including after correctDuration() and any number of edits, since none
// of those replace #seg-track's DOM anymore. Reads currentTrimData fresh on every mousedown instead of
// closing over the initial render's `data`, so a duration correction (or any prior edit) is never stale
// by the time the next drag starts — the same "read live state, don't close over a stale render" rule
// wireKeydownOnce already follows.
private fun wireDragHandles(root: Element, data: SegmentTrimResponse, scope: CoroutineScope) {
    val track = document.getElementById("seg-track") as? HTMLElement ?: return
    track.querySelectorAll(".h").let { nodes ->
        for (i in 0 until nodes.length) {
            val handle = nodes.item(i) as? HTMLElement ?: continue
            handle.addEventListener("mousedown") { downEv ->
                downEv.preventDefault()
                val live = currentTrimData ?: data
                val segEl = handle.closest(".seg") as? HTMLElement ?: return@addEventListener
                val kind = segEl.getAttribute("data-s") ?: return@addEventListener
                val edge = handle.getAttribute("data-e") ?: return@addEventListener
                val seg = live.segments.firstOrNull { it.kind == kind } ?: return@addEventListener
                if (seg.locked) {
                    toast("${kindOf(kind).label} is locked — unlock it to change the time")
                    return@addEventListener
                }
                trimSelectedKind = kind
                val box = track.getBoundingClientRect()
                val durationSec = live.durationSec
                var liveStartMs = seg.startMs
                var liveEndMs = seg.endMs ?: seg.startMs

                lateinit var moveHandler: (org.w3c.dom.events.Event) -> Unit
                lateinit var upHandler: (org.w3c.dom.events.Event) -> Unit
                moveHandler = handler@{ mv ->
                    val me = mv as org.w3c.dom.events.MouseEvent
                    val frac = ((me.clientX - box.left) / box.width).coerceIn(0.0, 1.0)
                    val tMs = (frac * durationSec * 1000).toLong()
                    if (edge == "a") liveStartMs = tMs.coerceAtMost(liveEndMs - 100) else liveEndMs = tMs.coerceAtLeast(liveStartMs + 100)
                    val left = liveStartMs / 1000.0 / durationSec * 100
                    val width = (liveEndMs - liveStartMs) / 1000.0 / durationSec * 100
                    segEl.style.left = "$left%"
                    segEl.style.width = "$width%"
                    (document.getElementById("seg-playhead") as? HTMLElement)?.style?.left = "${(if (edge == "a") liveStartMs else liveEndMs) / 1000.0 / durationSec * 100}%"
                }
                upHandler = handler@{
                    document.removeEventListener("mousemove", moveHandler)
                    document.removeEventListener("mouseup", upHandler)
                    trimLastEdge = edge
                    trimPlayheadMs = if (edge == "a") liveStartMs else liveEndMs
                    // FR-189-1/3/6 — same write-and-patch path the ± steppers use: no full re-render (the
                    // <video> element is untouched), and a failed write snaps the bar back to where it was.
                    applyEditInPlace(currentTrimData ?: live, seg, liveStartMs, liveEndMs, scope)
                }
                document.addEventListener("mousemove", moveHandler)
                document.addEventListener("mouseup", upHandler)
            }
        }
    }
}

/** Phase 190 (FR-190-6) — releases an in-flight remux transcode when the viewer leaves /segments
 *  entirely (back to a media page, the dashboard, anywhere). Wired once, page-lifetime, same idiom as
 *  [wireKeydownOnce]: `hashchange` fires on ANY hash change, so this only acts when the PATH itself
 *  stopped being /segments — a same-page navigation to a different title is handled separately, in
 *  [renderTrim]'s own `isNewTitle` branch, since that case never changes the path. */
private fun wireStreamTeardownOnce() {
    if (teardownWired) return
    teardownWired = true
    window.addEventListener("hashchange") {
        if (Router.currentPath() == "/segments") return@addEventListener
        val psid = trimPlaySessionId
        if (psid.isBlank()) return@addEventListener
        trimPlaySessionId = ""
        (currentTrimScope ?: return@addEventListener).launch { SegmentApi.stopStream(psid) }
    }
}

private fun wireKeydownOnce() {
    if (keydownWired) return
    keydownWired = true
    document.addEventListener("keydown") { ev ->
        val kev = ev as? KeyboardEvent ?: return@addEventListener
        if (Router.currentPath() != "/segments") return@addEventListener
        val data = currentTrimData ?: return@addEventListener
        val scope = currentTrimScope ?: return@addEventListener
        val target = kev.target
        if (target is HTMLElement && (target.tagName.equals("input", true) || target.tagName.equals("textarea", true))) return@addEventListener
        val kind = trimSelectedKind ?: return@addEventListener
        val seg = data.segments.firstOrNull { it.kind == kind }
        when (kev.key.lowercase()) {
            // FR-189-5 — the locked check moved INSIDE nudgeSegment/applyEditInPlace so every path (mouse
            // and keyboard alike) reports the same toast instead of the keyboard path silently no-op'ing.
            "," -> if (seg != null) { kev.preventDefault(); nudgeSegment(data, scope, kind, trimLastEdge, if (kev.shiftKey) -1000L else -40L) }
            "." -> if (seg != null) { kev.preventDefault(); nudgeSegment(data, scope, kind, trimLastEdge, if (kev.shiftKey) 1000L else 40L) }
            "i" -> if (seg != null) {
                kev.preventDefault()
                if (seg.locked) toast("${kindOf(kind).label} is locked — unlock it to change the time")
                else { trimLastEdge = "a"; applyEditInPlace(data, seg, trimPlayheadMs, seg.endMs ?: seg.startMs, scope) }
            }
            "o" -> if (seg != null) {
                kev.preventDefault()
                if (seg.locked) toast("${kindOf(kind).label} is locked — unlock it to change the time")
                else { trimLastEdge = "b"; applyEditInPlace(data, seg, seg.startMs, trimPlayheadMs, scope) }
            }
            "l" -> if (seg != null) {
                kev.preventDefault()
                scope.launch {
                    if (SegmentApi.setLock(data.mediaId, kind, data.episodeKey, data.episodeNumber, !seg.locked)) {
                        toast(if (!seg.locked) "${kindOf(kind).label} locked — detection will not touch it" else "${kindOf(kind).label} unlocked")
                    } else {
                        toast("Couldn't ${if (!seg.locked) "lock" else "unlock"} ${kindOf(kind).label} — try again")
                    }
                    refreshTrimBody(scope)
                }
            }
            "enter" -> { kev.preventDefault(); goNext(data, scope) }
            "escape" -> {
                kev.preventDefault()
                if (data.kind == "tv") Router.navigate("/segments", mapOf("series" to data.mediaId, "season" to data.seasonNumber.toString()))
                else Router.navigate("/media/${data.mediaId}")
            }
        }
    }
}

private fun renderSheet(sheet: SegmentSheetResponse, scope: CoroutineScope) {
    val root = document.getElementById("seg-root") ?: return
    if (openIndex >= sheet.episodes.size) openIndex = 0
    picked.retainAll(sheet.episodes.indices.toSet())
    // Bulk actions (apply consensus/lock/redetect) only make sense within one series' one season —
    // a cross-library filter= row list spans unrelated titles with no shared consensus to apply.
    val bulkEnabled = sheet.kind == "tv"
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
        val cb = if (bulkEnabled) {
            val on = idx in picked
            """<span class="cb${if (on) " on" else ""}" data-c="$idx">${if (on) "✓" else ""}</span>"""
        } else "<span></span>"
        """<div class="srow$openCls$oddCls" data-r="$idx">
             $cb
             <span class="id">${row.code}</span>
             <span class="et">$titleCell</span>
             <div class="lane">$laneInner</div>
             <span class="stt"><span class="dot $dotCls"></span>$statusTxt</span>
             <span class="pubc">${if (row.segments.any { it.locked }) "<span class=\"src me\" title=\"locked — detection will not touch it\">🔒</span>" else ""}</span>
           </div>"""
    }.joinToString("")

    val found = sheet.stats.found
    val open = sheet.episodes.getOrNull(openIndex)
    val drawerHtml = if (open != null) buildDrawer(open, bulkEnabled) else ""
    val bulkBarHtml = if (bulkEnabled) buildBulkBar() else ""
    val seasonRedetectBtn = if (bulkEnabled) """<button class="btn sm" data-a="redetect-season">↻ Re-detect the season</button>""" else ""

    val backHref = when (sheet.kind) {
        "cross" -> """href="#/dashboard""""
        else -> """href="#/media/${sheet.itemId}""""
    }
    val backLabel = if (sheet.kind == "cross") "‹ Dashboard" else "‹ ${sheet.title}"
    val subtitle = if (sheet.seasonName != null) "${sheet.title} · ${sheet.seasonName} · ${sheet.episodes.size} episodes" else sheet.title

    root.innerHTML = """
        <div class="sxbar"><a class="sxback" $backHref>$backLabel</a><h1>Intro &amp; credits</h1>
          <span class="sxsub">$subtitle</span><span class="sxsp"></span>$seasonRedetectBtn
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
            $bulkBarHtml
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
    root.querySelectorAll("[data-a='open-editor']").let { nodes ->
        for (i in 0 until nodes.length) {
            (nodes.item(i) as? HTMLElement)?.addEventListener("click") {
                val row = sheet.episodes.getOrNull(openIndex) ?: return@addEventListener
                if (sheet.kind == "tv") {
                    Router.navigate("/segments", mapOf("series" to row.mediaId, "episode" to row.episodeKey, "episodeNumber" to row.episodeNumber.toString()))
                } else {
                    Router.navigate("/segments", mapOf("movie" to row.mediaId))
                }
            }
        }
    }
    if (bulkEnabled) wireBulkActions(root, sheet, scope)
    if (bulkEnabled && open != null) wireDrawerActions(root, sheet, open, scope)
}

private fun ref(row: SegmentEpisodeRow) = SegmentEpisodeRef(row.mediaId, row.episodeKey, row.episodeNumber)

private fun wireBulkActions(root: org.w3c.dom.Element, sheet: SegmentSheetResponse, scope: CoroutineScope) {
    root.querySelectorAll("[data-c]").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") { ev ->
                ev.stopPropagation()
                val idx = el.getAttribute("data-c")?.toIntOrNull() ?: return@addEventListener
                if (!picked.add(idx)) picked.remove(idx)
                renderSheet(sheet, scope)
            }
        }
    }
    root.querySelector("[data-a='pick-attn']")?.addEventListener("click") {
        sheet.episodes.forEachIndexed { idx, row ->
            val (dotCls, _) = statusFor(row)
            if (dotCls == "d-bad" || dotCls == "d-warn") picked.add(idx)
        }
        renderSheet(sheet, scope)
        toast("Selected every episode that needs a decision")
    }
    root.querySelector("[data-a='apply']")?.addEventListener("click") {
        if (picked.isEmpty()) return@addEventListener
        val targets = picked.map { ref(sheet.episodes[it]) }
        scope.launch {
            val ok = SegmentApi.applyConsensus(sheet.itemId!!, sheet.seasonNumber!!, SegKind.INTRO, targets)
            picked.clear()
            reloadAndToast(sheet, scope, if (ok) "Intro set on ${targets.size} episode${if (targets.size == 1) "" else "s"} · written to disk" else "Couldn't apply — no season consensus yet")
        }
    }
    root.querySelector("[data-a='lock']")?.addEventListener("click") {
        if (picked.isEmpty()) return@addEventListener
        val targets = picked.map { ref(sheet.episodes[it]) }
        scope.launch {
            SegmentApi.bulkLock(targets, true)
            picked.clear()
            reloadAndToast(sheet, scope, "Locked — detection will leave these alone")
        }
    }
    root.querySelector("[data-a='redetect']")?.addEventListener("click") {
        if (picked.isEmpty()) return@addEventListener
        val targets = picked.map { ref(sheet.episodes[it]) }
        scope.launch {
            val result = SegmentApi.redetect(items = targets)
            toast(redetectToast("${targets.size} episode${if (targets.size == 1) "" else "s"}", result) + " — locked markers are skipped")
        }
    }
    root.querySelector("[data-a='redetect-season']")?.addEventListener("click") {
        scope.launch {
            val result = SegmentApi.redetect(series = sheet.itemId, season = sheet.seasonNumber)
            toast(redetectToast("the season", result) + " — locked markers are skipped")
        }
    }
}

private fun wireDrawerActions(root: org.w3c.dom.Element, sheet: SegmentSheetResponse, open: SegmentEpisodeRow, scope: CoroutineScope) {
    root.querySelector("[data-a='apply-one']")?.addEventListener("click") {
        scope.launch {
            val ok = SegmentApi.applyConsensus(sheet.itemId!!, sheet.seasonNumber!!, SegKind.INTRO, listOf(ref(open)))
            reloadAndToast(sheet, scope, if (ok) "Intro set from the season consensus · written to disk" else "No season consensus yet")
        }
    }
    root.querySelector("[data-a='lock-one']")?.addEventListener("click") {
        val nowLocked = open.segments.none { it.locked }
        scope.launch {
            SegmentApi.bulkLock(listOf(ref(open)), nowLocked)
            reloadAndToast(sheet, scope, if (nowLocked) "Locked — detection will not touch it" else "Unlocked — the next scan may change this")
        }
    }
    root.querySelector("[data-a='ok-one']")?.addEventListener("click") {
        scope.launch {
            SegmentApi.setChecked(listOf(ref(open)))
            reloadAndToast(sheet, scope, "${open.code} marked as checked")
        }
    }
    root.querySelector("[data-a='redetect-one']")?.addEventListener("click") {
        scope.launch {
            val result = SegmentApi.redetect(items = listOf(ref(open)))
            toast(redetectToast(open.code, result) + " — locked markers are skipped")
        }
    }
}

private suspend fun reloadAndToast(sheet: SegmentSheetResponse, scope: CoroutineScope, msg: String) {
    val itemId = sheet.itemId ?: return
    val season = sheet.seasonNumber ?: return
    val fresh = SegmentApi.seasonSheet(itemId, season) ?: return
    renderSheet(fresh, scope)
    toast(msg)
}

private fun buildBulkBar(): String = """
    <div class="bulk"><span class="sxhint"><b>${if (picked.isEmpty()) "No" else picked.size}</b> episode${if (picked.size == 1) "" else "s"} selected</span>
      <button class="btn sm ghost" data-a="pick-attn">Select everything that needs me</button><span class="sxsp"></span>
      <button class="btn sm" data-a="apply"${if (picked.isEmpty()) " disabled" else ""}>Give them the season's intro</button>
      <button class="btn sm" data-a="lock"${if (picked.isEmpty()) " disabled" else ""}>🔒 Lock</button>
      <button class="btn sm ghost" data-a="redetect"${if (picked.isEmpty()) " disabled" else ""}>↻ Detect again</button></div>
""".trimIndent()

private fun ciLabel(ci: dev.jellystructure.api.SegmentConsensus?): String {
    val start = ci?.startMs ?: return "—"
    val end = ci.endMs ?: start
    return "${fmt(start / 1000.0)} → ${fmt(end / 1000.0)}"
}

private fun buildDrawer(row: SegmentEpisodeRow, interactive: Boolean): String {
    val bars = segBarsHtml(row)
    val hint = when {
        row.outlier -> "This episode's intro lands well away from the rest of the season — worth a look before it's trusted."
        row.segments.isNotEmpty() -> "Looks like the rest of the season. Open the editor if you want to watch the cut before signing it off."
        else -> "Nothing was detected yet. Open the editor to set the boundaries by hand, or wait for the next detect_segments run."
    }
    val locked = row.segments.any { it.locked }
    val actionsRow = if (!interactive) "" else """
        <div class="drow"><button class="btn sm pri" data-a="apply-one">Use the season's intro</button>
          <button class="btn sm" data-a="open-editor">Trim by hand</button>
          <button class="lockb${if (locked) " on" else ""}" data-a="lock-one">${if (locked) "🔒 locked" else "🔓 Lock this episode"}</button>
          <button class="btn sm ghost" data-a="ok-one">Fine as it is</button>
          <button class="btn sm ghost" data-a="redetect-one">↻ Re-detect</button></div>
    """.trimIndent()
    return """
        <div class="drawer"><div class="vid"><div class="ph"><em>${fmtl(row.durationSec)}</em>${row.code}${if (row.segments.isEmpty()) " · nothing marked" else ""}</div></div>
          <div class="dcol">
            <div class="drow"><b>${row.code} · ${row.title}</b><span class="sxsp"></span>
              <button class="btn sm ghost" data-a="open-editor">Open the full editor →</button></div>
            <div class="track" style="height:32px"><div class="grid"></div>$bars</div>
            <div class="sxhint">$hint</div>
            $actionsRow
          </div>
        </div>
    """.trimIndent()
}
