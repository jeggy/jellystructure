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

private fun currentTimeString(): String = js("new Date().toLocaleTimeString()")

private var activitySocket: WebSocket? = null
private var activityScope: CoroutineScope? = null
private var jobItemCount = 0
private var jobDoneCount = 0
private var jobFailCount = 0
private var jobCurrentTitle: String? = null

fun renderActivity(container: Element, scope: CoroutineScope) {
    activityScope = scope
    activitySocket?.close()
    activitySocket = null
    jobItemCount = 0
    jobDoneCount = 0
    jobFailCount = 0
    jobCurrentTitle = null

    container.innerHTML = """
        <div class="pagebar">
          <h1>Activity</h1>
          <span id="act-crumb" style="display:none" class="crumb"></span>
          <span class="spacer"></span>
          <span id="ws-status" class="badge">Connecting…</span>
          <button id="act-cancel-btn" class="btn sm ghost" style="display:none">Pause</button>
          <button id="clear-btn" class="btn sm ghost">Clear</button>
        </div>
        <p class="page-sub">Live job events streamed from the backend WebSocket.</p>

        <div id="act-chips" style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:14px"></div>

        <div id="job-progress-card" class="card" style="display:none;margin-bottom:14px">
          <div class="row center" style="margin-bottom:10px">
            <span style="font-size:.9rem;font-weight:600">Scan in progress</span>
            <span class="spacer"></span>
            <span id="job-id-badge" class="num tiny muted"></span>
          </div>
          <div class="row center" style="gap:16px;margin-bottom:10px">
            <span class="tiny muted">Items found: <b id="job-count">0</b></span>
            <span id="job-current-file" class="tiny muted mono" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;min-width:0"></span>
          </div>
          <div style="width:100%;background:var(--fill-3);border-radius:4px;height:4px">
            <div id="job-progress-bar" style="width:0%;height:4px;border-radius:4px;background:var(--grad,var(--hi));transition:width .3s"></div>
          </div>
        </div>

        <div id="activity-console" class="log" style="font-size:.76rem;line-height:1.6;min-height:300px;max-height:calc(100vh - 22rem);overflow-y:auto;padding:14px 16px;border-radius:10px;border:1px solid var(--border)">
          <div class="muted tiny">Waiting for job events…</div>
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
            showProgressCard(container, jobId)
            (container.querySelector("#act-cancel-btn") as? HTMLElement)?.style?.display = ""
            (container.querySelector("#act-crumb") as? HTMLElement)?.let { it.textContent = "Scanning"; it.style.display = "" }
            updateActivityChips(container)
            appendLine(container, "started", "▶ Job $jobId started — $total files")
        }
        "progress" -> {
            val file = extractJsonField(raw, "file") ?: "?"
            val current = extractJsonField(raw, "current") ?: "?"
            val total = extractJsonField(raw, "total") ?: "?"
            updateProgressFile(container, file.substringAfterLast('/'))
            appendLine(container, "progress", "  [$current/$total] ${file.substringAfterLast('/')}")
        }
        "item_scanned" -> {
            jobItemCount++
            updateProgressCount(container, jobItemCount)
            updateActivityChips(container)
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
            hideProgressCard(container)
            (container.querySelector("#act-cancel-btn") as? HTMLElement)?.style?.display = "none"
            (container.querySelector("#act-crumb") as? HTMLElement)?.style?.display = "none"
            appendLine(container, "finished", "■ Job $jobId done — $succeeded succeeded, $failed failed")
            appendLine(container, "separator", "─".repeat(60))
        }
        else -> appendLine(container, "raw", raw)
    }
}

private fun updateActivityChips(container: Element) {
    val el = container.querySelector("#act-chips") as? HTMLElement ?: return
    if (jobItemCount == 0 && jobDoneCount == 0 && jobFailCount == 0) { el.innerHTML = ""; return }
    el.innerHTML = buildString {
        if (jobItemCount > 0) append("""<span class="chip"><span class="dot ok"></span> $jobItemCount scanned</span>""")
        if (jobDoneCount > 0) append("""<span class="chip"><span class="dot ok"></span> $jobDoneCount done ✓</span>""")
        if (jobFailCount > 0) append("""<span class="chip"><span class="dot bad"></span> $jobFailCount failed</span>""")
    }
}

private fun showProgressCard(container: Element, jobId: String) {
    val card = container.querySelector("#job-progress-card") as? HTMLElement ?: return
    card.style.display = "block"
    (container.querySelector("#job-id-badge") as? HTMLElement)?.textContent = jobId
    (container.querySelector("#job-count") as? HTMLElement)?.textContent = "0"
    (container.querySelector("#job-progress-bar") as? HTMLElement)?.setAttribute("style", "width:0%")
}

private fun hideProgressCard(container: Element) {
    (container.querySelector("#job-progress-card") as? HTMLElement)?.style?.display = "none"
}

private fun updateProgressFile(container: Element, shortName: String) {
    (container.querySelector("#job-current-file") as? HTMLElement)?.textContent = shortName
}

private fun updateProgressCount(container: Element, count: Int) {
    (container.querySelector("#job-count") as? HTMLElement)?.textContent = count.toString()
}

private fun appendLine(container: Element, kind: String, text: String) {
    val console = container.querySelector("#activity-console") ?: return
    console.querySelector(".muted")?.remove()

    val ts = currentTimeString()
    val color = when (kind) {
        "started"   -> "color:var(--hi)"
        "finished"  -> "color:var(--warn,#f59e0b)"
        "error", "bad" -> "color:var(--bad)"
        "ok"        -> "color:var(--ok)"
        "separator" -> "opacity:.25"
        "system"    -> "opacity:.4"
        else        -> ""
    }
    val div = document.createElement("div")
    div.setAttribute("style", "white-space:pre;$color")
    div.textContent = if (kind == "separator") text else "[$ts] $text"
    console.appendChild(div)
    (console as? HTMLElement)?.let { it.scrollTop = it.scrollHeight.toDouble() }
}

private fun extractJsonField(json: String, field: String): String? {
    // Simple regex-free field extraction for known flat JSON shapes
    val key = "\"$field\":"
    val start = json.indexOf(key).takeIf { it >= 0 } ?: return null
    val valueStart = start + key.length
    val trimmed = json.substring(valueStart).trimStart()
    return when {
        trimmed.startsWith('"') -> {
            val end = trimmed.indexOf('"', 1).takeIf { it >= 0 } ?: return null
            trimmed.substring(1, end)
        }
        else -> {
            val end = trimmed.indexOfFirst { it == ',' || it == '}' || it == ']' }
                .takeIf { it >= 0 } ?: trimmed.length
            trimmed.substring(0, end).trim()
        }
    }
}
