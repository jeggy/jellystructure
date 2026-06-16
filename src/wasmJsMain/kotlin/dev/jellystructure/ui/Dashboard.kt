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
          <div class="stat"><div class="k">TV episodes</div><div class="v" id="stat-tv">—</div></div>
          <div class="stat alert" style="cursor:pointer" id="stat-issues-cell"><div class="k">Tracks needing attention</div><div class="v" id="stat-issues">—</div></div>
          <div class="stat"><div class="k">NFO coverage</div><div class="v" id="stat-nfo">—%</div></div>
        </div>

        <div id="attention-queue" style="display:none;margin-top:18px">
          <div class="row center" style="margin-bottom:10px;gap:8px">
            <h3 style="margin:0;font-size:1rem">Needs attention</h3>
            <span class="spacer"></span>
            <button id="dash-triage" class="btn sm ghost">Go to triage →</button>
          </div>
          <div id="attention-list"></div>
        </div>

        <div class="row" style="margin-top:18px;gap:18px;flex-wrap:wrap;align-items:flex-start;">
          <div class="card fill" style="min-width:240px">
            <h3 style="font-size:1rem;margin:0 0 12px">Quick actions</h3>
            <div class="pill-row" style="display:flex;gap:8px;flex-wrap:wrap;">
              <button id="qa-triage" class="chip">Triage untagged tracks</button>
              <button id="qa-track-order" class="chip">Set track defaults</button>
              <button id="qa-artwork" class="chip">Re-pull artwork</button>
              <button id="qa-jf-refresh" class="chip">Tell Jellyfin to refresh</button>
              <button id="qa-activity" class="chip">View activity</button>
            </div>
            <div id="qa-feedback" style="margin-top:10px;min-height:20px"></div>
          </div>
          <div class="card" style="min-width:240px;flex:1">
            <h3 style="font-size:1rem;margin:0 0 12px">Recently processed</h3>
            <div id="recent-list"><span class="muted tiny">Loading…</span></div>
          </div>
        </div>
    """.trimIndent()

    document.getElementById("dash-browse")?.addEventListener("click") {
        App.navigate("/library")
    }
    document.getElementById("dash-scan")?.addEventListener("click") {
        scope.launch { triggerDashboardScan(scope) }
    }
    document.getElementById("dash-triage")?.addEventListener("click") { App.navigate("/triage") }
    document.getElementById("stat-issues-cell")?.addEventListener("click") { App.navigate("/triage") }
    document.getElementById("qa-triage")?.addEventListener("click") { App.navigate("/triage") }
    document.getElementById("qa-track-order")?.addEventListener("click") { App.navigate("/track-order") }
    document.getElementById("qa-activity")?.addEventListener("click") { App.navigate("/activity") }

    document.getElementById("qa-artwork")?.addEventListener("click") {
        scope.launch {
            setQaFeedback("Fetching artwork for all items…", "badge")
            val ok = MediaApi.batchFetchArtwork()
            setQaFeedback(
                if (ok) "Artwork fetch started in background ✓" else "Failed to start artwork fetch",
                if (ok) "badge ok" else "badge bad"
            )
        }
    }

    document.getElementById("qa-jf-refresh")?.addEventListener("click") {
        scope.launch {
            setQaFeedback("Sending refresh signal to Jellyfin…", "badge")
            val ok = MediaApi.jellyfinRefreshAll()
            setQaFeedback(
                if (ok) "Jellyfin library refresh triggered ✓" else "Failed — check Jellyfin connection in Settings",
                if (ok) "badge ok" else "badge bad"
            )
        }
    }

    scope.launch {
        loadDashboardStats()
        loadAttentionQueue()
        loadRecentActivity()
        val status = MediaApi.scanStatus()
        if (status?.running == true) {
            setDashScanRunning(true)
            connectDashScanSocket(scope)
        }
    }
}

private suspend fun loadAttentionQueue() {
    val page = MediaApi.list(filter = "attention", pageSize = 8) ?: return
    val queueEl = document.getElementById("attention-queue") as? HTMLElement ?: return
    val listEl = document.getElementById("attention-list") as? HTMLElement ?: return
    if (page.total == 0) {
        queueEl.style.display = "none"
        return
    }
    queueEl.style.display = "block"
    listEl.innerHTML = page.items.joinToString("") { item ->
        val badge = when {
            item.languageMix ->
                """<span class="badge warn" style="font-size:.7rem">language mix</span>"""
            item.issueCount > 0 ->
                """<span class="badge bad" style="font-size:.7rem">${item.issueCount} untagged</span>"""
            else -> ""
        }
        val year = item.year?.let { " ($it)" } ?: ""
        val kind = item.kind.name.lowercase().replace('_', ' ')
        """<div class="row center" style="padding:7px 0;border-bottom:1px solid var(--border);gap:8px;cursor:pointer"
              data-nav="/media/${item.id}">
             <span style="flex:1;font-size:.9rem">${item.title.esc()}$year</span>
             <span class="muted tiny">$kind</span>
             $badge
           </div>"""
    }
    if (page.total > 8) {
        listEl.innerHTML += """<div class="muted tiny" style="padding-top:6px">Showing 8 of ${page.total} items — <span style="cursor:pointer;text-decoration:underline" id="dash-see-all">see all in triage</span></div>"""
        document.getElementById("dash-see-all")?.addEventListener("click") { App.navigate("/triage") }
    }
    listEl.querySelectorAll("[data-nav]").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            val nav = el.getAttribute("data-nav") ?: continue
            el.addEventListener("click") { App.navigate(nav) }
        }
    }
}

private suspend fun loadDashboardStats() {
    val stats = MediaApi.stats() ?: return
    (document.getElementById("stat-movies") as? HTMLElement)?.textContent = stats.movies.toString()
    (document.getElementById("stat-tv") as? HTMLElement)?.textContent = stats.tvEpisodes.toString()
    (document.getElementById("stat-issues") as? HTMLElement)?.textContent = stats.issues.toString()
    (document.getElementById("stat-nfo") as? HTMLElement)?.textContent = "${stats.nfoCoverage}%"
}

private suspend fun loadRecentActivity() {
    val entries = MediaApi.getRecentActivity()
    val el = document.getElementById("recent-list") as? HTMLElement ?: return
    if (entries.isEmpty()) {
        el.innerHTML = """<span class="muted tiny">No activity yet — run a scan to get started.</span>"""
        return
    }
    el.innerHTML = entries.take(8).joinToString("") { entry ->
        val actionLabel = entry.action.replace('_', ' ')
        """<div style="display:flex;align-items:baseline;gap:6px;padding:4px 0;border-bottom:1px solid var(--border);">
             <span style="font-size:.82rem;flex:1">${entry.detail.take(54).esc()}</span>
             <span class="badge" style="font-size:.65rem;flex-shrink:0">$actionLabel</span>
           </div>"""
    }
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

private fun setQaFeedback(msg: String, cls: String = "badge") {
    (document.getElementById("qa-feedback") as? HTMLElement)?.innerHTML =
        """<span class="$cls" style="font-size:.75rem">$msg</span>"""
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
