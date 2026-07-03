package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.callOpenAttentionDock
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
private var dashScanTotal = 0   // Phase 116: real worklist size from the Started event (0 = unknown)

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
        <p class="page-sub">Single source of truth for your media metadata. Jellyfin just reads what Jellystructure writes — you never touch its built-in scraper. <span id="dash-next-run" class="badge" style="margin-left:6px"></span></p>

        <div id="dash-scan-banner" style="margin-bottom:14px"></div>

        <div class="statgrid">
          <div class="stat"><div class="k">Movies</div><div class="v" id="stat-movies">—</div></div>
          <div class="stat"><div class="k">TV episodes</div><div class="v" id="stat-tv">—</div></div>
          <div class="stat alert" style="cursor:pointer" id="stat-issues-cell"><div class="k">Items needing attention</div><div class="v" id="stat-issues">—</div></div>
          <div class="stat"><div class="k">NFO coverage</div><div class="v" id="stat-nfo">—%</div></div>
        </div>

        <div class="row" style="margin-top:18px;align-items:stretch;gap:18px;flex-wrap:wrap">
          <div class="card fill" id="attention-queue" style="min-width:0">
            <div class="row center" style="gap:8px">
              <h3 style="margin:0;font-size:1.1rem">Needs your attention</h3>
              <span class="spacer"></span>
              <span class="badge bad" id="attention-count" style="display:none"></span>
              <button id="reopen-dock" class="btn sm">Show attention dock</button>
              <button id="dash-triage" class="btn sm">Browse all →</button>
            </div>
            <div class="tiny muted" style="margin:6px 0 0">Step through every flagged item from the floating dock, bottom-right — it opens each one's detail page where you fix it. Closed it? <b>Show attention dock</b> brings it back.</div>
            <hr class="dash" style="margin:11px 0">
            <div id="attention-list"><span class="muted tiny">Loading…</span></div>
            <hr class="dash" style="margin:11px 0">
            <div id="attention-breakdown"><span class="muted tiny">Loading…</span></div>
          </div>
          <div class="col dash-sidecol" style="width:320px;flex:none;gap:14px">
            <div class="card">
              <h3 style="margin:0 0 8px;font-size:1.05rem">Recently processed</h3>
              <div id="recent-list" class="tiny" style="line-height:2"><span class="muted">Loading…</span></div>
            </div>
            <div class="card">
              <h3 style="font-size:1rem;margin:0 0 12px">Quick actions</h3>
              <div class="pill-row" style="display:flex;gap:8px;flex-wrap:wrap">
                <button id="qa-triage" class="chip">View items needing attention</button>
                <button id="qa-track-order" class="chip">Manage tracks</button>
                <button id="qa-artwork" class="chip">Re-pull artwork</button>
                <button id="qa-jf-push" class="chip">Sync NFOs to Jellyfin</button>
                <button id="qa-jf-refresh" class="chip" title="Tell Jellyfin to rescan its own library (does not change Jellystructure data)">Jellyfin: rescan its library</button>
                <button id="qa-activity" class="chip">View activity</button>
              </div>
              <div id="qa-feedback" style="margin-top:10px;min-height:20px"></div>
            </div>
          </div>
        </div>
    """.trimIndent()

    document.getElementById("dash-browse")?.addEventListener("click") {
        App.navigate("/library")
    }
    document.getElementById("dash-scan")?.addEventListener("click") {
        scope.launch { triggerDashboardScan(scope, resume = false) }
    }
    document.getElementById("dash-triage")?.addEventListener("click") { App.navigate("/library") }
    document.getElementById("stat-issues-cell")?.addEventListener("click") { App.navigate("/library") }
    document.getElementById("reopen-dock")?.addEventListener("click") { callOpenAttentionDock() }
    document.getElementById("qa-triage")?.addEventListener("click") { App.navigate("/library") }
    document.getElementById("qa-track-order")?.addEventListener("click") { App.navigate("/library") }
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

    document.getElementById("qa-jf-push")?.addEventListener("click") {
        scope.launch {
            setQaFeedback("Writing NFOs and pushing metadata to Jellyfin…", "badge")
            val ok = MediaApi.batchJellyfinPush()
            setQaFeedback(
                if (ok) "NFO push started — Jellyfin will refresh all items ✓" else "Failed — check Jellyfin connection in Settings",
                if (ok) "badge ok" else "badge bad"
            )
        }
    }

    document.getElementById("qa-jf-refresh")?.addEventListener("click") {
        scope.launch {
            setQaFeedback("Sending library scan signal to Jellyfin…", "badge")
            val ok = MediaApi.jellyfinRefreshAll()
            setQaFeedback(
                if (ok) "Jellyfin rescan triggered ✓" else "Failed — check Jellyfin connection in Settings",
                if (ok) "badge ok" else "badge bad"
            )
        }
    }

    scope.launch {
        loadDashboardStats()
        loadAttentionQueue()
        loadAttentionBreakdown()
        loadRecentActivity()
        val status = MediaApi.scanStatus()
        when (status?.status) {
            "RUNNING" -> {
                setDashScanRunning(status.processedCount)
                connectDashScanSocket(scope, baseCount = status.processedCount)
            }
            "CANCELLED" -> setDashScanCancelled(status.processedCount, scope)
            else -> setDashScanIdle()
        }
        // 93e: surface the next scheduled automation run.
        document.getElementById("dash-next-run")?.let { el ->
            val next = status?.nextScheduledRun
            if (next != null) el.textContent = "next run · ${dev.jellystructure.formatStoredTs(next.toString())}"
            else (el as? HTMLElement)?.style?.display = "none"
        }
    }
}

private suspend fun loadAttentionQueue() {
    val page = MediaApi.list(filter = "attention", pageSize = 8) ?: return
    val listEl = document.getElementById("attention-list") as? HTMLElement ?: return
    if (page.total == 0) {
        listEl.innerHTML = """<div class="muted tiny">No items need attention — everything looks good.</div>"""
        return
    }
    (document.getElementById("attention-count") as? HTMLElement)?.let {
        it.textContent = "${page.total} items"
        it.style.display = ""
    }
    val rows = page.items.joinToString("") { item ->
        val badge = when {
            item.languageMix ->
                """<span class="badge warn" style="font-size:.7rem">language mix</span>"""
            item.issueCount > 0 ->
                """<span class="badge bad" style="font-size:.7rem">${item.issueCount} untagged</span>"""
            else -> ""
        }
        val year = item.year?.let { " ($it)" } ?: ""
        val kind = item.kind.name.lowercase().replace('_', ' ')
        val nav = "/media/${item.jellyfinId ?: item.id}"
        """<tr data-nav="$nav" style="cursor:pointer">
             <td style="font-weight:700">${item.title.esc()}$year</td>
             <td>$badge</td>
             <td class="muted tiny">$kind</td>
             <td><button class="btn sm" data-nav="$nav">Open</button></td>
           </tr>"""
    }
    listEl.innerHTML = """<table class="wf-table"><tbody>$rows</tbody></table>"""
    if (page.total > 8) {
        listEl.innerHTML += """<div class="tiny muted" style="margin-top:8px"><a id="dash-see-all" style="cursor:pointer;text-decoration:underline">…${page.total - 8} more — filter Library by "Needs attention" →</a></div>"""
        document.getElementById("dash-see-all")?.addEventListener("click") { App.navigate("/library") }
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
    (document.getElementById("stat-nfo") as? HTMLElement)?.textContent = "${stats.nfoCoverage}%"
    // Phase 117: "Items needing attention" now reads the same triage total the breakdown below sums —
    // it used to be store.totalIssueCount() (an untagged-only SQL sum), a different, smaller number.
}

/** Phase 117: one row per triage issue type — every type, always, including zeros — each linking to
 *  the Library pre-filtered to that issue. Mirrors the Triage dock's phrasing (Shell.kt triageSubline). */
private suspend fun loadAttentionBreakdown() {
    val count = MediaApi.getTriageCount()
    val el = document.getElementById("attention-breakdown") as? HTMLElement ?: return
    if (count == null || count.types.isEmpty()) {
        el.innerHTML = """<span class="muted tiny">Couldn't load the issue breakdown.</span>"""
        return
    }
    (document.getElementById("stat-issues") as? HTMLElement)?.textContent = count.total.toString()
    el.innerHTML = buildString {
        append("""<div class="tiny muted" style="margin-bottom:8px;">Every issue type Jellystructure tracks — click one to see the affected titles in the Library.</div>""")
        for (t in count.types) {
            val zero = t.instances == 0
            val countLabel = if (t.instances != t.titles) "${t.instances} (${t.titles} title${if (t.titles != 1) "s" else ""})" else "${t.instances}"
            val badgeCls = if (zero) "badge" else "badge bad"
            val rowStyle = if (zero) "opacity:.55;" else "cursor:pointer;"
            append("""<div class="row center" style="padding:6px 0;$rowStyle" ${if (!zero) """data-issue-filter="${t.key}"""" else ""}>""")
            append("""<div style="flex:1;min-width:0;"><b style="font-size:.86rem;">${t.label.esc()}</b>""")
            append("""<div class="tiny muted" style="margin-top:1px;">${t.description.esc()}</div></div>""")
            append("""<span class="$badgeCls" style="flex-shrink:0;margin-left:10px;">${if (zero) "✓ 0" else countLabel}</span>""")
            append("</div>")
        }
    }
    el.querySelectorAll("[data-issue-filter]").let { nodes ->
        for (i in 0 until nodes.length) {
            val row = nodes.item(i) as? HTMLElement ?: continue
            row.addEventListener("click") {
                val key = row.getAttribute("data-issue-filter") ?: return@addEventListener
                App.navigate("/library?filter=$key")
            }
        }
    }
}

private suspend fun loadRecentActivity() {
    val entries = MediaApi.getRecentActivity()
    val el = document.getElementById("recent-list") as? HTMLElement ?: return
    if (entries.isEmpty()) {
        el.innerHTML = """<span class="muted">No activity yet — run a scan to get started.</span>"""
        return
    }
    el.innerHTML = entries.take(6).joinToString("") { entry ->
        val bad = entry.action.contains("fail", ignoreCase = true) ||
                  entry.action.contains("error", ignoreCase = true) ||
                  entry.action.contains("no_match", ignoreCase = true)
        val dot = if (bad) "bad" else "ok"
        """<div class="row center" style="gap:6px"><span class="dot $dot"></span> ${entry.detail.take(52).esc()}</div>"""
    }
}

private suspend fun triggerDashboardScan(scope: CoroutineScope, resume: Boolean) {
    val btn = document.getElementById("dash-scan") as? HTMLButtonElement ?: return
    if (btn.disabled) return

    val started = if (resume) MediaApi.resumeScan() else MediaApi.startScan()
    if (!started) {
        val banner = document.getElementById("dash-scan-banner") as? HTMLElement ?: return
        banner.innerHTML = """<span class="badge bad">Scan failed to start — check server connection.</span>"""
        return
    }
    val status = MediaApi.scanStatus()
    setDashScanRunning(status?.processedCount ?: 0)
    connectDashScanSocket(scope, baseCount = status?.processedCount ?: 0)
}

private fun connectDashScanSocket(scope: CoroutineScope, baseCount: Int = 0) {
    val proto = if (window.location.protocol == "https:") "wss" else "ws"
    val ws = WebSocket("$proto://${window.location.host}/ws")
    dashScanSocket = ws

    var scannedCount = baseCount
    dashScanTotal = 0

    ws.onmessage = { ev ->
        val text = ev.data.toString()
        runCatching {
            when (val event = dashJson.decodeFromString<JobEvent>(text)) {
                is JobEvent.Started -> {
                    // Phase 116: the scan now reports its real worklist size up front.
                    if (event.total > 0) dashScanTotal = event.total
                }
                is JobEvent.ItemScanned -> {
                    scannedCount++
                    val banner = document.getElementById("dash-scan-banner") as? HTMLElement
                    val countNote = if (dashScanTotal > 0) "Scanning — $scannedCount of $dashScanTotal items…"
                        else "Scanning — $scannedCount item${if (scannedCount != 1) "s" else ""} found so far…"
                    banner?.innerHTML = """<span class="badge">$countNote</span> <button id="cancel-scan-btn" class="btn sm ghost" style="margin-left:8px">Cancel</button>"""
                    wireCancelBtn(scope)
                }
                is JobEvent.Finished -> {
                    ws.close()
                    dashScanSocket = null
                    setDashScanIdle()
                    val n = event.succeeded
                    val banner = document.getElementById("dash-scan-banner") as? HTMLElement
                    banner?.innerHTML = """<span class="badge ok">Scan complete — $n new item${if (n != 1) "s" else ""} processed.</span>"""
                    scope.launch { loadDashboardStats() }
                }
                else -> {}
            }
        }
    }

    ws.onclose = { _: Event ->
        if (dashScanSocket == ws) dashScanSocket = null
    }

    wireCancelBtn(scope)
}

private fun wireCancelBtn(scope: CoroutineScope) {
    document.getElementById("cancel-scan-btn")?.addEventListener("click") {
        scope.launch { MediaApi.cancelScan() }
    }
}

private fun setQaFeedback(msg: String, cls: String = "badge") {
    (document.getElementById("qa-feedback") as? HTMLElement)?.innerHTML =
        """<span class="$cls" style="font-size:.75rem">$msg</span>"""
}

private fun setDashScanIdle() {
    val btn = document.getElementById("dash-scan") as? HTMLButtonElement ?: return
    btn.disabled = false
    btn.textContent = "▶ Scan library"
    btn.onclick = null
}

private fun setDashScanRunning(processedCount: Int) {
    val btn = document.getElementById("dash-scan") as? HTMLButtonElement ?: return
    btn.disabled = true
    btn.textContent = "Scanning…"
    val banner = document.getElementById("dash-scan-banner") as? HTMLElement ?: return
    val countNote = if (processedCount > 0) "Scanning — $processedCount item${if (processedCount != 1) "s" else ""} processed so far…" else "Scanning — items appear in Library as they are processed."
    banner.innerHTML = """<span class="badge">$countNote</span> <button id="cancel-scan-btn" class="btn sm ghost" style="margin-left:8px">Cancel</button>"""
}

private fun setDashScanCancelled(processedCount: Int, scope: CoroutineScope) {
    val btn = document.getElementById("dash-scan") as? HTMLButtonElement ?: return
    btn.disabled = false
    btn.textContent = "▶ New scan"
    btn.onclick = null
    val banner = document.getElementById("dash-scan-banner") as? HTMLElement ?: return
    banner.innerHTML = """
        <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;">
          <span class="badge warn">Scan paused — $processedCount item${if (processedCount != 1) "s" else ""} already processed</span>
          <button id="resume-scan-btn" class="btn sm primary">↻ Continue scan</button>
          <button id="new-scan-btn" class="btn sm ghost">Start new scan</button>
        </div>""".trimIndent()
    document.getElementById("resume-scan-btn")?.addEventListener("click") {
        scope.launch { triggerDashboardScan(scope, resume = true) }
    }
    document.getElementById("new-scan-btn")?.addEventListener("click") {
        scope.launch { triggerDashboardScan(scope, resume = false) }
    }
}
