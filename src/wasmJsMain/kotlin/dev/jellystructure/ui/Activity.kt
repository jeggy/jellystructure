package dev.jellystructure.ui

import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.httpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent

private fun currentTimeString(): String = js("new Date().toLocaleTimeString([], {hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false})")

private const val TMDB_POSTER_W92 = "https://image.tmdb.org/t/p/w92"

private var activitySocket: WebSocket? = null
private var activityScope: CoroutineScope? = null
private var jobItemCount = 0
private var jobDoneCount = 0
private var jobFailCount = 0
private var activityCurrentTitle: String? = null
private var activityCurrentPoster: String? = null
private var scanRunning = false
private var activeLogCategory: String = ""
private var errorsOnlyFilter: Boolean = false
private var activeRunFilter: String? = null   // 93g: scope the log to one scan/pipeline run
private var lastToolCommand: String? = null

@Serializable
private data class ActivityEntryDto(
    val id: Int,
    val ts: Long,
    val level: String,
    val category: String,
    val message: String,
    val mediaId: String? = null,
    val runId: String? = null,
    val step: String? = null,
)

@Serializable
private data class ActivityLogPageDto(val entries: List<ActivityEntryDto>, val total: Int)

@Serializable
private data class RunSummaryDto(
    val runId: String, val trigger: String, val startedAt: Long, val finishedAt: Long? = null,
    val events: Int = 0, val errors: Int = 0,
)

fun renderActivity(container: Element, scope: CoroutineScope, query: Map<String, String> = emptyMap()) {
    activityScope = scope
    activitySocket?.close()
    activitySocket = null
    jobItemCount = 0
    jobDoneCount = 0
    jobFailCount = 0
    activityCurrentTitle = null
    activityCurrentPoster = null
    scanRunning = false
    activeLogCategory = ""
    errorsOnlyFilter = false

    container.innerHTML = """
        <div class="pagebar">
          <h1>Activity</h1>
          <span id="act-crumb" style="display:none" class="crumb"></span>
          <span class="spacer"></span>
          <span id="ws-status" class="badge">Connecting…</span>
          <button id="act-cancel-btn" class="btn sm ghost" style="display:none">Pause</button>
        </div>
        <p class="page-sub">Full activity log: scan events, NFO writes, artwork downloads, and track operations. Streams live over WebSocket; history is loaded from disk on page open.</p>

        <div id="overall-card" class="card" style="display:none;margin-bottom:14px">
          <div class="row center">
            <b>Overall</b>
            <span class="spacer"></span>
            <span class="mono tiny" id="ov-label">0 items</span>
          </div>
          <div class="bar" style="margin-top:8px"><i id="ov-bar" style="width:0%"></i></div>
          <div id="act-chips" style="display:flex;gap:10px;flex-wrap:wrap;margin-top:10px"></div>
        </div>

        <div id="act-columns" class="row" style="display:none;align-items:stretch;gap:14px;margin-bottom:14px">
          <div class="card fill" id="now-card">
            <div class="row center">
              <h4 style="margin:0">Now processing</h4>
              <span class="spacer"></span>
              <span class="mono tiny" id="now-filename" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:220px"></span>
            </div>
            <hr class="dash" style="margin:10px 0">
            <div class="row" style="gap:10px;align-items:flex-start">
              <div class="imgslot" id="now-poster" style="width:60px;height:88px;flex:none;background:var(--fill-3);border-radius:6px;overflow:hidden;display:flex;align-items:center;justify-content:center">
                <span class="tiny muted">poster</span>
              </div>
              <div class="tiny" style="line-height:1.85" id="now-ops">
                <div class="muted">Waiting for next item…</div>
              </div>
            </div>
          </div>
        </div>

        <div id="act-idle" class="card" style="margin-bottom:14px;display:none">
          <div class="muted tiny">No job is currently running. Start a scan from the Dashboard or Library.</div>
        </div>

        <div class="card" id="log-section">
          <div class="row center" style="margin-bottom:10px">
            <h4 style="margin:0">Log</h4>
            <span class="spacer"></span>
            <button id="clear-log-btn" class="btn sm ghost">Clear log</button>
          </div>
          <div id="log-filter-bar" style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:8px">
            <button data-cat="" class="chip act">All</button>
            <button data-cat="scan" class="chip">Scan</button>
            <button data-cat="nfo" class="chip">NFO</button>
            <button data-cat="artwork" class="chip">Artwork</button>
            <button data-cat="track" class="chip">Tracks</button>
            <button data-cat="system" class="chip">System</button>
            <span style="flex:1"></span>
            <select id="run-filter" class="input" style="height:26px;padding:0 6px;font-size:.78rem" title="Scope the log to one scan/pipeline run">
              <option value="">All runs</option>
            </select>
            <label style="display:flex;align-items:center;gap:7px;cursor:pointer;font-size:.82rem"><span class="toggle" id="errors-only-toggle"></span> Errors only</label>
            <span id="workers-chip" class="chip" style="display:none"></span>
          </div>
          <div class="log" id="activity-console" style="height:420px;min-height:80px;max-height:none">
            <div class="muted tiny">Loading activity log…</div>
          </div>
          <div id="log-resize-handle" style="height:12px;cursor:ns-resize;display:flex;align-items:center;justify-content:flex-end;margin:2px -16px -16px;padding:0 6px;border-radius:0 0 10px 10px;opacity:.35;transition:opacity .15s">
            <svg width="12" height="12" viewBox="0 0 12 12" fill="currentColor"><path d="M11 3.5a.5.5 0 0 0-.5-.5h-7a.5.5 0 0 0 0 1h7a.5.5 0 0 0 .5-.5zm0 5a.5.5 0 0 0-.5-.5h-7a.5.5 0 0 0 0 1h7a.5.5 0 0 0 .5-.5z"/></svg>
          </div>
        </div>
    """.trimIndent()

    container.querySelector("#act-cancel-btn")?.addEventListener("click") {
        scope.launch { MediaApi.cancelScan() }
    }

    container.querySelector("#clear-log-btn")?.addEventListener("click") {
        if (window.confirm("Clear the entire activity log?")) {
            scope.launch {
                runCatching { httpClient.delete("/api/activity/log") }
                val console = container.querySelector("#activity-console")
                console?.innerHTML = """<div class="muted tiny">Log cleared.</div>"""
            }
        }
    }

    container.querySelector("#errors-only-toggle")?.addEventListener("click") { ev ->
        val toggle = ev.currentTarget as? HTMLElement ?: return@addEventListener
        errorsOnlyFilter = !errorsOnlyFilter
        if (errorsOnlyFilter) toggle.classList.add("on") else toggle.classList.remove("on")
        reapplyFilter(container)
    }

    val filterBtns = container.querySelectorAll("#log-filter-bar button[data-cat]")
    for (i in 0 until filterBtns.length) {
        val btn = filterBtns.item(i) as? HTMLElement ?: continue
        btn.addEventListener("click") { _ ->
            activeLogCategory = btn.getAttribute("data-cat") ?: ""
            for (j in 0 until filterBtns.length) {
                val b = filterBtns.item(j) as? HTMLElement ?: continue
                if (b.getAttribute("data-cat") == activeLogCategory) b.classList.add("act")
                else b.classList.remove("act")
            }
            reapplyFilter(container)
        }
    }

    // 93g — run picker: scope the log to one scan/pipeline run (server-side reload).
    (container.querySelector("#run-filter") as? HTMLSelectElement)?.addEventListener("change") { ev ->
        activeRunFilter = (ev.target as? HTMLSelectElement)?.value?.ifBlank { null }
        scope.launch { loadLogHistory(container) }
    }

    connectWebSocket(container)
    scope.launch { loadRuns(container) }
    scope.launch { loadLogHistory(container) }
    // If a scan is already running when this page opens, show the running-job UI immediately — otherwise
    // we miss the WS "started" event and wrongly show "No job is currently running" for the whole run.
    scope.launch {
        val st = MediaApi.scanStatus()
        if (st?.running == true) {
            scanRunning = true
            jobItemCount = st.processedCount
            jobDoneCount = st.processedCount
            showJobUI(container)
            (container.querySelector("#act-cancel-btn") as? HTMLElement)?.style?.display = ""
            (container.querySelector("#act-crumb") as? HTMLElement)?.let { it.textContent = "Scanning"; it.style.display = "" }
            updateActivityChips(container)
            updateOvLabel(container)
            pollWorkers(container)
        } else {
            hideJobUI(container)
        }
    }
    wireLogResize(container)
}

/** 93g — populate the run picker from /api/activity/runs (newest first). */
private suspend fun loadRuns(container: Element) {
    runCatching {
        val runs: List<RunSummaryDto> = httpClient.get("/api/activity/runs").body()
        val sel = container.querySelector("#run-filter") as? HTMLSelectElement ?: return
        val sb = StringBuilder("""<option value="">All runs</option>""")
        runs.forEach { r ->
            val whenStr = dev.jellystructure.formatStoredTs(r.startedAt.toString())
            val status = when {
                r.finishedAt == null -> "running"
                r.errors > 0 -> "${r.events} events · ${r.errors} err"
                else -> "${r.events} events"
            }
            sb.append("""<option value="${r.runId.escapeHtml()}">${r.trigger.escapeHtml()} · $whenStr · $status</option>""")
        }
        sel.innerHTML = sb.toString()
        sel.value = activeRunFilter ?: ""
    }
}

private fun wireLogResize(container: Element) {
    val console = container.querySelector("#activity-console") as? HTMLElement ?: return
    val handle = container.querySelector("#log-resize-handle") as? HTMLElement ?: return

    localStorage.getItem("activity-log-height")?.toIntOrNull()?.let { h ->
        console.style.height = "${h}px"
    }

    var dragging = false
    var startY = 0.0
    var startH = 0.0

    handle.addEventListener("mouseenter") { (handle as HTMLElement).style.opacity = "0.8" }
    handle.addEventListener("mouseleave") { if (!dragging) (handle as HTMLElement).style.opacity = "0.4" }

    handle.addEventListener("mousedown") { ev ->
        val me = ev as? MouseEvent ?: return@addEventListener
        dragging = true
        startY = me.clientY.toDouble()
        startH = console.clientHeight.toDouble()
        ev.preventDefault()
    }

    window.addEventListener("mousemove") { ev ->
        if (!dragging) return@addEventListener
        val me = ev as? MouseEvent ?: return@addEventListener
        val dy = me.clientY.toDouble() - startY
        val newH = (startH + dy).coerceAtLeast(80.0)
        console.style.height = "${newH.toInt()}px"
    }

    window.addEventListener("mouseup") { _: Event ->
        if (!dragging) return@addEventListener
        dragging = false
        (handle as HTMLElement).style.opacity = "0.4"
        val h = console.clientHeight
        if (h > 0) localStorage.setItem("activity-log-height", h.toString())
    }
}

private fun reapplyFilter(container: Element) {
    val console = container.querySelector("#activity-console") ?: return
    val allLines = console.querySelectorAll("[data-cat]")
    for (i in 0 until allLines.length) {
        val line = allLines.item(i) as? HTMLElement ?: continue
        val cat = line.getAttribute("data-cat") ?: ""
        val lvl = line.getAttribute("data-level") ?: ""
        val catOk = activeLogCategory.isEmpty() || cat == activeLogCategory
        val lvlOk = !errorsOnlyFilter || lvl == "error" || lvl == "warn"
        val runOk = activeRunFilter == null || line.getAttribute("data-run") == activeRunFilter
        line.style.display = if (catOk && lvlOk && runOk) "" else "none"
    }
}

private suspend fun loadLogHistory(container: Element) {
    runCatching {
        val page: ActivityLogPageDto = httpClient.get("/api/activity/log") {
            parameter("pageSize", "200")
            activeRunFilter?.let { parameter("run", it) }   // 93g: server-side scope to one run
        }.body()
        val console = container.querySelector("#activity-console") ?: return
        if (page.entries.isEmpty()) {
            console.innerHTML = """<div class="muted tiny">No activity entries${if (activeRunFilter != null) " for this run" else " yet"}.</div>"""
            return
        }
        console.innerHTML = ""
        page.entries.forEach { entry ->
            appendLogEntry(container, entry.level, entry.category, entry.message, ts = entry.ts, runId = entry.runId)
        }
        (console as? HTMLElement)?.let { it.scrollTop = it.scrollHeight.toDouble() }
    }.onFailure {
        val console = container.querySelector("#activity-console")
        console?.innerHTML = """<div class="muted tiny">Could not load log history.</div>"""
    }
}

private fun connectWebSocket(container: Element) {
    val proto = if (window.location.protocol == "https:") "wss" else "ws"
    val url = "$proto://${window.location.host}/ws"
    val ws = WebSocket(url)
    activitySocket = ws

    ws.onopen = { _: Event ->
        (container.querySelector("#ws-status") as? HTMLElement)?.let {
            it.textContent = "● live"
            it.className = "badge ok"
        }
    }

    ws.onclose = { _: Event ->
        (container.querySelector("#ws-status") as? HTMLElement)?.let {
            it.textContent = "○ disconnected"
            it.className = "badge"
        }
        appendLogEntry(container, "warn", "system", "WebSocket disconnected.")
    }

    ws.onerror = { _: Event ->
        (container.querySelector("#ws-status") as? HTMLElement)?.let {
            it.textContent = "✗ error"
            it.className = "badge bad"
        }
        appendLogEntry(container, "error", "system", "WebSocket error — check server logs.")
    }

    ws.onmessage = { ev ->
        handleEvent(container, ev.data.toString())
    }
}

private fun handleEvent(container: Element, raw: String) {
    val type = extractJsonField(raw, "type") ?: run {
        appendLogEntry(container, "info", "system", raw)
        return
    }
    when (type) {
        "started" -> {
            val jobId = extractJsonField(raw, "jobId") ?: "?"
            val total = extractJsonField(raw, "total") ?: "?"
            jobItemCount = 0; jobDoneCount = 0; jobFailCount = 0
            scanRunning = true
            showJobUI(container)
            (container.querySelector("#ov-bar") as? HTMLElement)?.style?.width = "0%"
            (container.querySelector("#act-cancel-btn") as? HTMLElement)?.style?.display = ""
            (container.querySelector("#act-crumb") as? HTMLElement)?.let { it.textContent = "Scanning"; it.style.display = "" }
            updateActivityChips(container)
            appendLogEntry(container, "info", "scan", "▶ Job $jobId started${if (total != "-1") " — $total files" else ""}")
            activityScope?.launch { pollWorkers(container) }
        }
        "progress" -> {
            val file = extractJsonField(raw, "file") ?: "?"
            val current = extractJsonField(raw, "current") ?: "?"
            val total = extractJsonField(raw, "total") ?: "?"
            val shortName = file.substringAfterLast('/')
            updateNowFilename(container, shortName)
            // Overall scan total is unknown (items are discovered), so drive the bar from the
            // current item's real per-file progress (P0-5 — it was hardcoded to 0%).
            val cur = current.toIntOrNull(); val tot = total.toIntOrNull()
            if (cur != null && tot != null && tot > 0) {
                val pct = (cur * 100 / tot).coerceIn(0, 100)
                (container.querySelector("#ov-bar") as? HTMLElement)?.style?.width = "$pct%"
            }
        }
        "item_scanned" -> {
            if (!scanRunning) {   // missed "started" (page opened mid-scan) — surface the job UI now
                scanRunning = true
                showJobUI(container)
                (container.querySelector("#act-cancel-btn") as? HTMLElement)?.style?.display = ""
                (container.querySelector("#act-crumb") as? HTMLElement)?.let { it.textContent = "Scanning"; it.style.display = "" }
                activityScope?.launch { pollWorkers(container) }
            }
            jobItemCount++
            val title = extractJsonField(raw, "title") ?: extractNestedField(raw, "item", "title")
            val poster = extractJsonField(raw, "posterPath") ?: extractNestedField(raw, "item", "posterPath")
            val path = extractNestedField(raw, "item", "path")
            activityCurrentTitle = title
            activityCurrentPoster = poster
            updateNowCard(container, title, poster, path)
            updateOvLabel(container)
            updateActivityChips(container)
        }
        "file_done" -> {
            val file = extractJsonField(raw, "file") ?: "?"
            val ok = extractJsonField(raw, "ok") ?: "false"
            val msg = extractJsonField(raw, "msg")
            val icon = if (ok == "true") "✓" else "✗"
            val detail = if (msg != null) " — $msg" else ""
            val level = if (ok == "true") "info" else "error"
            if (ok == "true") jobDoneCount++ else jobFailCount++
            updateActivityChips(container)
            appendLogEntry(container, level, "scan", "$icon ${file.substringAfterLast('/')}$detail")
        }
        "finished" -> {
            val jobId = extractJsonField(raw, "jobId") ?: "?"
            val succeeded = extractJsonField(raw, "succeeded") ?: "?"
            val failed = extractJsonField(raw, "failed") ?: "?"
            scanRunning = false
            hideJobUI(container)
            (container.querySelector("#act-cancel-btn") as? HTMLElement)?.style?.display = "none"
            (container.querySelector("#act-crumb") as? HTMLElement)?.style?.display = "none"
            (container.querySelector("#workers-chip") as? HTMLElement)?.style?.display = "none"
            appendLogEntry(container, "info", "scan", "■ Job $jobId done — $succeeded succeeded, $failed failed")
            activityScope?.launch { loadRuns(container) }   // 93g: surface the just-finished run in the picker
        }
        "log_line" -> {
            val level = extractJsonField(raw, "level") ?: "info"
            val category = extractJsonField(raw, "category") ?: "system"
            val message = extractJsonField(raw, "message") ?: ""
            val runId = extractJsonField(raw, "runId")
            if (category == "track" && (message.startsWith("ffmpeg:") || message.startsWith("mkvpropedit:"))) {
                lastToolCommand = message
                refreshNowOps(container)
            }
            appendLogEntry(container, level, category, message, runId = runId)
        }
        else -> appendLogEntry(container, "info", "system", raw)
    }
}

private suspend fun pollWorkers(container: Element) {
    while (scanRunning) {
        val status = MediaApi.scanStatus()
        if (status != null) {
            (container.querySelector("#workers-chip") as? HTMLElement)?.let {
                it.textContent = "Workers: ${status.activeWorkers}/${status.configuredWorkers}"
                it.style.display = ""
            }
        }
        delay(2000)
    }
}

private fun showJobUI(container: Element) {
    (container.querySelector("#overall-card") as? HTMLElement)?.style?.display = "block"
    (container.querySelector("#act-columns") as? HTMLElement)?.style?.display = "flex"
    (container.querySelector("#act-idle") as? HTMLElement)?.style?.display = "none"
}

private fun hideJobUI(container: Element) {
    (container.querySelector("#overall-card") as? HTMLElement)?.style?.display = "none"
    (container.querySelector("#act-columns") as? HTMLElement)?.style?.display = "none"
    (container.querySelector("#act-idle") as? HTMLElement)?.style?.display = "block"
    resetNowCard(container)
}

private fun updateNowFilename(container: Element, shortName: String) {
    (container.querySelector("#now-filename") as? HTMLElement)?.textContent = shortName
}

private fun updateNowCard(container: Element, title: String?, poster: String?, path: String?) {
    val displayName = title ?: path?.substringAfterLast('/') ?: "Unknown"
    updateNowFilename(container, displayName)

    val posterEl = container.querySelector("#now-poster") as? HTMLElement
    if (posterEl != null) {
        val posterUrl = when {
            poster == null -> null
            poster.startsWith("http") -> poster
            poster.startsWith("/") && poster.length < 120 -> "$TMDB_POSTER_W92$poster"
            else -> null
        }
        if (posterUrl != null) {
            posterEl.innerHTML = """<img src="$posterUrl" style="width:100%;height:100%;object-fit:cover" alt="poster">"""
        } else {
            posterEl.innerHTML = """<span class="tiny muted" style="text-align:center;padding:4px">${displayName.take(20)}</span>"""
        }
    }

    val opsEl = container.querySelector("#now-ops") as? HTMLElement
    if (opsEl != null) {
        opsEl.innerHTML = buildString {
            if (title != null) append("""<div><b>$title</b></div>""")
            if (path != null) append("""<div class="muted mono" style="font-size:.7rem">${path.substringAfterLast('/')}</div>""")
            append("""<div style="margin-top:6px;color:var(--hi)">⟳ processing…</div>""")
            val cmd = lastToolCommand
            if (cmd != null) append("""<pre class="log" style="font-size:.68rem;margin-top:4px;white-space:pre-wrap;word-break:break-all">${cmd.escapeHtml()}</pre>""")
        }
    }
}

private fun refreshNowOps(container: Element) {
    val opsEl = container.querySelector("#now-ops") as? HTMLElement ?: return
    val existing = opsEl.innerHTML
    val cmdHtml = """<pre class="log" style="font-size:.68rem;margin-top:4px;white-space:pre-wrap;word-break:break-all">${lastToolCommand?.escapeHtml() ?: ""}</pre>"""
    val preIdx = existing.indexOf("<pre")
    if (preIdx >= 0) {
        opsEl.innerHTML = existing.substring(0, preIdx) + cmdHtml
    } else {
        opsEl.innerHTML = existing + cmdHtml
    }
}

private fun resetNowCard(container: Element) {
    (container.querySelector("#now-filename") as? HTMLElement)?.textContent = ""
    (container.querySelector("#now-poster") as? HTMLElement)?.innerHTML = """<span class="tiny muted">poster</span>"""
    (container.querySelector("#now-ops") as? HTMLElement)?.innerHTML = """<div class="muted">Waiting for next item…</div>"""
    activityCurrentTitle = null
    activityCurrentPoster = null
    lastToolCommand = null
}

private fun updateOvLabel(container: Element) {
    (container.querySelector("#ov-label") as? HTMLElement)?.textContent = "$jobItemCount item${if (jobItemCount != 1) "s" else ""} scanned"
}

private fun updateActivityChips(container: Element) {
    val el = container.querySelector("#act-chips") as? HTMLElement ?: return
    if (jobItemCount == 0 && jobDoneCount == 0 && jobFailCount == 0) { el.innerHTML = ""; return }
    el.innerHTML = buildString {
        if (jobItemCount > 0) append("""<span class="chip ok" style="background:var(--ok-soft)">$jobItemCount scanned</span>""")
        if (jobDoneCount > 0) append("""<span class="chip ok" style="background:var(--ok-soft)">$jobDoneCount done ✓</span>""")
        if (jobFailCount > 0) append("""<span class="chip bad" style="background:var(--bad-soft)">$jobFailCount failed</span>""")
    }
}

private fun appendLogEntry(container: Element, level: String, category: String, text: String, ts: Long? = null, runId: String? = null) {
    val console = container.querySelector("#activity-console") ?: return
    console.querySelector(".muted")?.remove()

    val tsStr = if (ts != null) dev.jellystructure.formatStoredTs(ts.toString()) else currentTimeString()
    val catLabel = when (category) {
        "scan" -> "<span class='chip' style='font-size:.6rem;padding:0 4px'>scan</span> "
        "nfo" -> "<span class='chip' style='font-size:.6rem;padding:0 4px;background:var(--fill-2)'>nfo</span> "
        "artwork" -> "<span class='chip' style='font-size:.6rem;padding:0 4px;background:var(--fill-2)'>art</span> "
        "track" -> "<span class='chip' style='font-size:.6rem;padding:0 4px;background:var(--fill-2)'>track</span> "
        else -> ""
    }
    val colorStyle = when {
        level == "error" -> "color:var(--bad)"
        level == "warn" -> "color:var(--warn,#f59e0b)"
        text.startsWith("▶") -> "color:var(--hi)"
        text.startsWith("■") -> "color:var(--warn,#f59e0b)"
        else -> ""
    }

    val div = document.createElement("div")
    div.setAttribute("data-cat", category)
    div.setAttribute("data-level", level)
    if (runId != null) div.setAttribute("data-run", runId)
    div.innerHTML = """<span class="ts">$tsStr</span> $catLabel<span style="$colorStyle">${text.escapeHtml()}</span>"""

    val catOk = activeLogCategory.isEmpty() || category == activeLogCategory
    val lvlOk = !errorsOnlyFilter || level == "error" || level == "warn"
    val runOk = activeRunFilter == null || runId == activeRunFilter
    if (!catOk || !lvlOk || !runOk) (div as? HTMLElement)?.style?.display = "none"

    console.appendChild(div)
    // Auto-scroll only when the user is already pinned to the bottom (within 80 px).
    // History loads use a separate explicit scroll-to-bottom after all entries are appended.
    (console as? HTMLElement)?.let { el ->
        val distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight
        if (distanceFromBottom <= 80) el.scrollTop = el.scrollHeight.toDouble()
    }
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun extractJsonField(json: String, field: String): String? {
    val key = "\"$field\":"
    val start = json.indexOf(key).takeIf { it >= 0 } ?: return null
    val valueStart = start + key.length
    val trimmed = json.substring(valueStart).trimStart()
    return when {
        trimmed.startsWith('"') -> {
            val end = trimmed.indexOf('"', 1).takeIf { it >= 0 } ?: return null
            trimmed.substring(1, end)
        }
        trimmed.startsWith('{') || trimmed.startsWith('[') -> null
        else -> {
            val end = trimmed.indexOfFirst { it == ',' || it == '}' || it == ']' }
                .takeIf { it >= 0 } ?: trimmed.length
            trimmed.substring(0, end).trim().takeIf { it != "null" }
        }
    }
}

private fun extractNestedField(json: String, outerKey: String, innerKey: String): String? {
    val outerStart = json.indexOf("\"$outerKey\":")
    if (outerStart < 0) return null
    val objStart = json.indexOf('{', outerStart)
    if (objStart < 0) return null
    var depth = 0
    var objEnd = objStart
    for (i in objStart until json.length) {
        when (json[i]) {
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) { objEnd = i; break } }
        }
    }
    val inner = json.substring(objStart, objEnd + 1)
    return extractJsonField(inner, innerKey)
}
