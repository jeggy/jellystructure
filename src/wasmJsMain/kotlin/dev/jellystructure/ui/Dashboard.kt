package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.MediaApi
import dev.jellystructure.jobs.JobEvent
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event

private val dashJson = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }
private var dashScanSocket: WebSocket? = null

fun renderDashboard(container: Element, scope: CoroutineScope) {
    dashScanSocket?.close()
    dashScanSocket = null

    container.innerHTML = """
        <div class="pagebar">
          <h1>Dashboard</h1>
          <span class="spacer"></span>
          <button id="dash-browse" class="btn sm ghost">Browse library</button>
          <button id="dash-scan" class="btn primary">▶ Scan library</button>
        </div>
        <p class="page-sub">Single source of truth for your media metadata. Jellyfin just reads what Jellystructure writes — you never touch its built-in scraper.</p>

        <div id="dash-scan-banner" style="display:none;margin-bottom:14px"></div>

        <div class="statgrid">
          <div class="stat"><div class="k">Movies</div><div class="v" id="stat-movies">—</div></div>
          <div class="stat"><div class="k">TV Series</div><div class="v" id="stat-tv">—</div></div>
          <div class="stat alert"><div class="k">Tracks needing attention</div><div class="v" id="stat-issues">—</div></div>
          <div class="stat"><div class="k">NFO coverage</div><div class="v" id="stat-nfo">—</div></div>
        </div>
    """.trimIndent()

    document.getElementById("dash-browse")?.addEventListener("click") {
        App.navigate("/library")
    }
    document.getElementById("dash-scan")?.addEventListener("click") {
        scope.launch { triggerDashboardScan(scope) }
    }

    scope.launch {
        loadDashboardStats()
        val status = MediaApi.scanStatus()
        if (status?.running == true) {
            setDashScanRunning(true)
            connectDashScanSocket(scope)
        }
    }
}

private suspend fun loadDashboardStats() {
    val stats = MediaApi.stats() ?: return
    (document.getElementById("stat-movies") as? HTMLElement)?.textContent = stats.movies.toString()
    (document.getElementById("stat-tv") as? HTMLElement)?.textContent = stats.tvShows.toString()
    (document.getElementById("stat-issues") as? HTMLElement)?.textContent = stats.issues.toString()
    (document.getElementById("stat-nfo") as? HTMLElement)?.textContent = stats.nfoCoverage.toString()
}

private suspend fun triggerDashboardScan(scope: CoroutineScope) {
    val btn = document.getElementById("dash-scan") as? HTMLButtonElement ?: return
    if (btn.disabled) return

    val started = MediaApi.startScan()
    if (!started) {
        val banner = document.getElementById("dash-scan-banner") as? HTMLElement ?: return
        banner.style.display = "block"
        banner.innerHTML = """<span class="badge bad">Scan is already running or failed to start.</span>"""
        return
    }
    setDashScanRunning(true)
    connectDashScanSocket(scope)
}

private fun connectDashScanSocket(scope: CoroutineScope) {
    val proto = if (window.location.protocol == "https:") "wss" else "ws"
    val ws = WebSocket("$proto://${window.location.host}/ws")
    dashScanSocket = ws

    var scannedCount = 0

    ws.onmessage = { ev ->
        val text = ev.data.toString()
        runCatching {
            val event = dashJson.decodeFromString<JobEvent>(text)
            when (event) {
                is JobEvent.ItemScanned -> {
                    scannedCount++
                    val banner = document.getElementById("dash-scan-banner") as? HTMLElement
                    banner?.innerHTML = """<span class="badge">Scanning — $scannedCount item${if (scannedCount != 1) "s" else ""} found so far…</span>"""
                }
                is JobEvent.Finished -> {
                    ws.close()
                    dashScanSocket = null
                    setDashScanRunning(false)
                    val n = event.succeeded
                    val banner = document.getElementById("dash-scan-banner") as? HTMLElement
                    banner?.style?.display = "block"
                    banner?.innerHTML = """<span class="badge ok">Scan complete — $n item${if (n != 1) "s" else ""} found.</span>"""
                    scope.launch { loadDashboardStats() }
                }
                else -> {}
            }
        }
    }

    ws.onclose = { _: Event ->
        if (dashScanSocket == ws) dashScanSocket = null
    }

    // Wire cancel button — it's injected dynamically into the banner by setDashScanRunning
    fun wireCancelBtn() {
        document.getElementById("cancel-scan-btn")?.addEventListener("click") {
            scope.launch { MediaApi.cancelScan() }
        }
    }
    wireCancelBtn()
}

private fun setDashScanRunning(running: Boolean) {
    val btn = document.getElementById("dash-scan") as? HTMLButtonElement
    val banner = document.getElementById("dash-scan-banner") as? HTMLElement
    if (running) {
        btn?.disabled = true
        btn?.textContent = "Scanning…"
        banner?.style?.display = "block"
        banner?.innerHTML = """<span class="badge">Scanning — items appear in Library as they are processed.</span> <button id="cancel-scan-btn" class="btn sm ghost" style="margin-left:8px">Cancel</button>"""
    } else {
        btn?.disabled = false
        btn?.textContent = "▶ Scan library"
    }
}
