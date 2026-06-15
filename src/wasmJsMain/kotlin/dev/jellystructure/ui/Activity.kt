package dev.jellystructure.ui

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event

private fun currentTimeString(): String = js("new Date().toLocaleTimeString()")

private var activitySocket: WebSocket? = null
private var jobItemCount = 0

fun renderActivity(container: Element, scope: CoroutineScope) {
    // Close any existing socket when navigating away
    activitySocket?.close()
    activitySocket = null

    container.innerHTML = """
        <div class="p-6">
          <div class="flex items-center justify-between mb-4">
            <div>
              <h1 class="text-2xl font-bold text-white">Activity</h1>
              <p class="text-sm text-slate-400 mt-1">Live job events streamed from the backend.</p>
            </div>
            <div class="flex items-center gap-3">
              <span id="ws-status" class="text-xs px-2 py-1 rounded-full bg-slate-700 text-slate-400">Connecting…</span>
              <button id="clear-btn" class="text-xs bg-slate-700 hover:bg-slate-600 text-slate-300 px-3 py-1.5 rounded">Clear</button>
            </div>
          </div>

          <div id="job-progress-card" class="bg-slate-800 rounded-lg border border-slate-700 p-4 mb-4" style="display:none">
            <div class="flex items-center justify-between mb-2">
              <span class="text-sm font-semibold text-white">Scan in progress</span>
              <span id="job-id-badge" class="text-xs text-slate-400 font-mono"></span>
            </div>
            <div class="flex items-center gap-6 text-sm text-slate-300 mb-3">
              <span>Items scanned: <strong id="job-count">0</strong></span>
              <span id="job-current-file" class="text-slate-400 text-xs font-mono truncate max-w-xs"></span>
            </div>
            <div class="w-full bg-slate-700 rounded-full h-1.5">
              <div id="job-progress-bar" class="bg-indigo-500 h-1.5 rounded-full transition-all" style="width:0%"></div>
            </div>
          </div>

          <div id="activity-console"
               class="bg-slate-950 rounded-lg border border-slate-700 p-4 font-mono text-xs text-slate-300 h-[calc(100vh-18rem)] overflow-y-auto space-y-0.5">
            <div class="text-slate-600 italic">Waiting for job events…</div>
          </div>
        </div>
    """.trimIndent()

    container.querySelector("#clear-btn")?.addEventListener("click") {
        val console = container.querySelector("#activity-console")
        console?.innerHTML = """<div class="text-slate-600 italic">Console cleared.</div>"""
    }

    connectWebSocket(container)
}

private fun connectWebSocket(container: Element) {
    val proto = if (window.location.protocol == "https:") "wss" else "ws"
    val url = "$proto://${window.location.host}/ws"
    val ws = WebSocket(url)
    activitySocket = ws

    val statusEl = { container.querySelector("#ws-status") as? HTMLElement }

    ws.onopen = { _: Event ->
        statusEl()?.let {
            it.textContent = "● Connected"
            it.className = "text-xs px-2 py-1 rounded-full bg-green-900 text-green-300"
        }
    }

    ws.onclose = { _: Event ->
        statusEl()?.let {
            it.textContent = "○ Disconnected"
            it.className = "text-xs px-2 py-1 rounded-full bg-slate-700 text-slate-400"
        }
        appendLine(container, "system", "WebSocket disconnected.")
    }

    ws.onerror = { _: Event ->
        statusEl()?.let {
            it.textContent = "✗ Error"
            it.className = "text-xs px-2 py-1 rounded-full bg-red-900 text-red-400"
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
            jobItemCount = 0
            showProgressCard(container, jobId)
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
        }
        "file_done" -> {
            val file = extractJsonField(raw, "file") ?: "?"
            val ok = extractJsonField(raw, "ok") ?: "false"
            val msg = extractJsonField(raw, "msg")
            val icon = if (ok == "true") "✓" else "✗"
            val detail = if (msg != null) " — $msg" else ""
            val colorClass = if (ok == "true") "text-green-400" else "text-red-400"
            appendLine(container, "file_done", "$icon ${file.substringAfterLast('/')}$detail", colorClass)
        }
        "finished" -> {
            val jobId = extractJsonField(raw, "jobId") ?: "?"
            val succeeded = extractJsonField(raw, "succeeded") ?: "?"
            val failed = extractJsonField(raw, "failed") ?: "?"
            hideProgressCard(container)
            appendLine(container, "finished", "■ Job $jobId finished — $succeeded succeeded, $failed failed")
            appendLine(container, "separator", "─".repeat(60))
        }
        else -> appendLine(container, "raw", raw)
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

private fun appendLine(
    container: Element,
    kind: String,
    text: String,
    extraClass: String = "",
) {
    val console = container.querySelector("#activity-console") ?: return
    // Remove placeholder on first real message
    console.querySelector(".italic")?.remove()

    val ts = currentTimeString()
    val baseClass = when (kind) {
        "started"   -> "text-blue-400"
        "finished"  -> "text-yellow-400"
        "error"     -> "text-red-400"
        "separator" -> "text-slate-700"
        else        -> "text-slate-300"
    }
    val div = document.createElement("div")
    div.className = "$baseClass $extraClass"
    div.textContent = if (kind == "separator") text else "[$ts] $text"
    console.appendChild(div)
    // Auto-scroll to bottom
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
