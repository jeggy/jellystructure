package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.MediaApi
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

fun renderDashboard(container: Element, scope: CoroutineScope) {
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
          <div class="stat"><div class="k">TV episodes</div><div class="v">—</div></div>
          <div class="stat alert"><div class="k">Tracks needing attention</div><div class="v" id="stat-issues">—</div></div>
          <div class="stat"><div class="k">NFO coverage</div><div class="v">—</div></div>
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
            pollDashScanUntilDone(scope)
        }
    }
}

private suspend fun loadDashboardStats() {
    val stats = MediaApi.stats() ?: return
    (document.getElementById("stat-movies") as? HTMLElement)?.textContent = stats.movies.toString()
    (document.getElementById("stat-issues") as? HTMLElement)?.textContent = stats.issues.toString()
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
    pollDashScanUntilDone(scope)
}

private suspend fun pollDashScanUntilDone(scope: CoroutineScope) {
    while (true) {
        delay(2000)
        val status = MediaApi.scanStatus() ?: break
        if (!status.running) {
            setDashScanRunning(false)
            val count = status.lastCount
            val banner = document.getElementById("dash-scan-banner") as? HTMLElement
            banner?.style?.display = "block"
            banner?.innerHTML = """<span class="badge ok">Scan complete${if (count != null) " — $count item${if (count != 1) "s" else ""} found" else ""}.</span>"""
            loadDashboardStats()
            break
        }
    }
}

private fun setDashScanRunning(running: Boolean) {
    val btn = document.getElementById("dash-scan") as? HTMLButtonElement
    val banner = document.getElementById("dash-scan-banner") as? HTMLElement
    if (running) {
        btn?.disabled = true
        btn?.textContent = "Scanning…"
        banner?.style?.display = "block"
        banner?.innerHTML = """<span class="badge">Scan in progress — this may take a moment…</span>"""
    } else {
        btn?.disabled = false
        btn?.textContent = "▶ Scan library"
    }
}
