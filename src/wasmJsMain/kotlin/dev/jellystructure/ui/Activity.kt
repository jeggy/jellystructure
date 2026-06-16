package dev.jellystructure.ui

import dev.jellystructure.api.MediaApi
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event

private fun currentTimeString(): String = js("new Date().toLocaleTimeString([], {hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false})")

private const val TMDB_POSTER_W92 = "https://image.tmdb.org/t/p/w92"

private var activitySocket: WebSocket? = null
private var activityScope: CoroutineScope? = null
private var jobItemCount = 0
private var jobDoneCount = 0
private var jobFailCount = 0
private var activityCurrentTitle: String? = null
private var activityCurrentPoster: String? = null

fun renderActivity(container: Element, scope: CoroutineScope) {
    activityScope = scope
    activitySocket?.close()
    activitySocket = null
    jobItemCount = 0
    jobDoneCount = 0
    jobFailCount = 0
    activityCurrentTitle = null
    activityCurrentPoster = null

    container.innerHTML = """
        <div class="pagebar">
          <h1>Activity</h1>
          <span id="act-crumb" style="display:none" class="crumb"></span>
          <span class="spacer"></span>
          <span id="ws-status" class="badge">Connecting…</span>
          <button id="act-cancel-btn" class="btn sm ghost" style="display:none">Pause</button>
          <button id="clear-btn" class="btn sm ghost">Clear</button>
        </div>
        <p class="page-sub">The full console for the job also shown in the ambient dock. Everything streams over WebSocket — no polling, no page reloads. Errors drop into <a href="#/triage">Triage</a> and the run keeps going.</p>

        <div id="overall-card" class="card" style="display:none;margin-bottom:14px">
          <div class="row center">
            <b>Overall</b>
            <span class="spacer"></span>
            <span class="mono tiny" id="ov-label">0 items</span>
          </div>
          <div class="bar" style="margin-top:8px"><i id="ov-bar" style="width:0%"></i></div>
          <div id="act-chips" style="display:flex;gap:10px;flex-wrap:wrap;margin-top:10px"></div>
        </div>

        <div id="act-columns" class="row" style="display:none;align-items:stretch;gap:14px">
          <div class="card fill" id="now-card">
            <div class="row center">
              <h4 style="margin:0">Now processing</h4>
              <span class="spacer"></span>
              <span class="mono tiny" id="now-filename" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:220px"></span>
            </div>
            <hr class="dash" style="margin:10px 0">
            <div id="now-status-row" class="row center" style="gap:8px;display:none">
              <span id="now-tool-badge" class="badge warn">processing</span>
            </div>
            <div class="bar" id="now-bar-wrap" style="margin-top:8px;display:none"><i id="now-bar" style="width:0%"></i></div>
            <hr class="dash" style="margin:12px 0">
            <div class="row" style="gap:10px;align-items:flex-start">
              <div class="imgslot" id="now-poster" style="width:60px;height:88px;flex:none;background:var(--fill-3);border-radius:6px;overflow:hidden;display:flex;align-items:center;justify-content:center">
                <span class="tiny muted">poster</span>
              </div>
              <div class="tiny" style="line-height:1.85" id="now-ops">
                <div class="muted">Waiting for next item…</div>
              </div>
            </div>
          </div>

          <div class="card" style="width:420px;min-width:280px;flex:none">
            <div class="row center">
              <h4 style="margin:0">Live log</h4>
              <span class="spacer"></span>
              <span class="chip" style="font-size:.65rem" id="log-ws-chip"><span class="dot ok"></span> ws connected</span>
            </div>
            <div class="log" id="activity-console" style="max-height:360px;margin-top:8px">
              <div class="muted tiny">Waiting for job events…</div>
            </div>
            <div class="tiny muted center-x" style="margin-top:8px">streaming live</div>
          </div>
        </div>

        <div id="act-idle" class="card" style="margin-top:16px">
          <div class="muted tiny">No job is currently running. Start a scan from the Dashboard or Library.</div>
        </div>
    """.trimIndent()

    container.querySelector("#clear-btn")?.addEventListener("click") {
        val console = container.querySelector("#activity-console")
        console?.innerHTML = """<div class="muted tiny">Console cleared.</div>"""
        jobItemCount = 0; jobDoneCount = 0; jobFailCount = 0
        updateActivityChips(container)
    }

    container.querySelector("#act-cancel-btn")?.addEventListener("click") {
        scope.launch { MediaApi.cancelScan() }
    }

    connectWebSocket(container)
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
        appendLine(container, "system", "WebSocket disconnected.")
    }

    ws.onerror = { _: Event ->
        (container.querySelector("#ws-status") as? HTMLElement)?.let {
            it.textContent = "✗ error"
            it.className = "badge bad"
        }
        appendLine(container, "error", "WebSocket error — check server logs.")
    }

    ws.onmessage = { ev ->
        val text = ev.data.toString()
        handleEvent(container, text)
    }
}

private fun handleEvent(container: Element, raw: String) {
    val type = extractJsonField(raw, "type") ?: run {
        appendLine(container, "raw", raw)
        return
    }
    when (type) {
        "started" -> {
            val jobId = extractJsonField(raw, "jobId") ?: "?"
            val total = extractJsonField(raw, "total") ?: "?"
            jobItemCount = 0; jobDoneCount = 0; jobFailCount = 0
            showJobUI(container, jobId)
            (container.querySelector("#act-cancel-btn") as? HTMLElement)?.style?.display = ""
            (container.querySelector("#act-crumb") as? HTMLElement)?.let { it.textContent = "Scanning"; it.style.display = "" }
            updateActivityChips(container)
            appendLine(container, "started", "▶ Job $jobId started${if (total != "-1") " — $total files" else ""}")
        }
        "progress" -> {
            val file = extractJsonField(raw, "file") ?: "?"
            val current = extractJsonField(raw, "current") ?: "?"
            val total = extractJsonField(raw, "total") ?: "?"
            val shortName = file.substringAfterLast('/')
            updateNowFilename(container, shortName)
            appendLine(container, "progress", "  [$current/$total] $shortName")
        }
        "item_scanned" -> {
            jobItemCount++
            val title = extractJsonField(raw, "title") ?: extractNestedField(raw, "item", "title")
            val poster = extractJsonField(raw, "posterPath") ?: extractNestedField(raw, "item", "posterPath")
            val path = extractNestedField(raw, "item", "path")
            activityCurrentTitle = title
            activityCurrentPoster = poster
            updateNowCard(container, title, poster, path)
            updateOvLabel(container)
            updateActivityChips(container)
            appendLine(container, "scanned", title ?: path?.substringAfterLast('/') ?: "item scanned")
        }
        "file_done" -> {
            val file = extractJsonField(raw, "file") ?: "?"
            val ok = extractJsonField(raw, "ok") ?: "false"
            val msg = extractJsonField(raw, "msg")
            val icon = if (ok == "true") "✓" else "✗"
            val detail = if (msg != null) " — $msg" else ""
            val lineKind = if (ok == "true") "ok" else "bad"
            if (ok == "true") jobDoneCount++ else jobFailCount++
            updateActivityChips(container)
            appendLine(container, lineKind, "$icon ${file.substringAfterLast('/')}$detail")
        }
        "finished" -> {
            val jobId = extractJsonField(raw, "jobId") ?: "?"
            val succeeded = extractJsonField(raw, "succeeded") ?: "?"
            val failed = extractJsonField(raw, "failed") ?: "?"
            hideJobUI(container)
            (container.querySelector("#act-cancel-btn") as? HTMLElement)?.style?.display = "none"
            (container.querySelector("#act-crumb") as? HTMLElement)?.style?.display = "none"
            appendLine(container, "finished", "■ Job $jobId done — $succeeded succeeded, $failed failed")
            appendLine(container, "separator", "─".repeat(60))
        }
        else -> appendLine(container, "raw", raw)
    }
}

private fun showJobUI(container: Element, jobId: String) {
    (container.querySelector("#overall-card") as? HTMLElement)?.style?.display = "block"
    (container.querySelector("#act-columns") as? HTMLElement)?.style?.display = "flex"
    (container.querySelector("#act-idle") as? HTMLElement)?.style?.display = "none"
    (container.querySelector("#ov-bar") as? HTMLElement)?.setAttribute("style", "width:0%")
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

    // Update poster
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

    // Update ops checklist
    val opsEl = container.querySelector("#now-ops") as? HTMLElement
    if (opsEl != null) {
        opsEl.innerHTML = buildString {
            if (title != null) append("""<div><b>$title</b></div>""")
            if (path != null) append("""<div class="muted mono" style="font-size:.7rem">${path.substringAfterLast('/')}</div>""")
            append("""<div style="margin-top:6px;color:var(--hi)">⟳ processing…</div>""")
        }
    }
}

private fun resetNowCard(container: Element) {
    (container.querySelector("#now-filename") as? HTMLElement)?.textContent = ""
    (container.querySelector("#now-poster") as? HTMLElement)?.innerHTML = """<span class="tiny muted">poster</span>"""
    (container.querySelector("#now-ops") as? HTMLElement)?.innerHTML = """<div class="muted">Waiting for next item…</div>"""
    activityCurrentTitle = null
    activityCurrentPoster = null
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

private fun appendLine(container: Element, kind: String, text: String) {
    val console = container.querySelector("#activity-console") ?: return
    console.querySelector(".muted")?.remove()

    val ts = currentTimeString()
    val colorStyle = when (kind) {
        "started"   -> "color:var(--hi)"
        "finished"  -> "color:var(--warn,#f59e0b)"
        "error", "bad" -> "color:var(--bad)"
        "ok"        -> "color:var(--ok)"
        "separator" -> "opacity:.25"
        "system"    -> "opacity:.4"
        else        -> ""
    }

    val div = document.createElement("div")
    if (kind == "separator") {
        div.setAttribute("style", "opacity:.25;white-space:pre")
        div.textContent = text
    } else {
        div.innerHTML = """<span class="ts">$ts</span> <span style="$colorStyle">${text.escapeHtml()}</span>"""
    }
    console.appendChild(div)
    (console as? HTMLElement)?.let { it.scrollTop = it.scrollHeight.toDouble() }
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

/** Extracts a field nested inside an object field, e.g. extractNestedField(raw, "item", "title") */
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
