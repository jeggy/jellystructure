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
          <span class="split" id="dash-scan-split">
            <button id="dash-scan" class="btn primary" title="Skips items not due for a recheck yet (Settings ▸ scan_files' cooldown), same as a scheduled run">▶ Scan library</button>
            <span class="btn primary split-caret menu-btn"><span class="caret">▾</span></span>
            <div class="menu">
              <div class="menu-item" id="dash-scan-full"><span class="mi-ic">⟳</span><span>Scan library (full rescan)<span class="mi-sub">No freshness filter — every item is reprocessed</span></span></div>
            </div>
          </span>
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
            <div class="tiny muted" style="margin:6px 0 0">Every flagged item, grouped by issue type. Click a type to open Library filtered to just those; or step through them one by one from the floating dock, bottom-right. Closed the dock? <b>Show attention dock</b> brings it back.</div>
            <hr class="dash" style="margin:11px 0">
            <div id="attention-breakdown"><span class="muted tiny">Loading…</span></div>
            <div class="tiny muted" style="margin-top:10px"><a id="dash-browse-all-footer" style="cursor:pointer;text-decoration:underline">Browse all flagged items in Library →</a></div>
          </div>
          <div class="col dash-sidecol" style="width:320px;flex:none;gap:14px">
            <div class="card">
              <h3 style="margin:0 0 8px;font-size:1.05rem">Recently processed</h3>
              <div id="recent-list" class="tiny" style="line-height:2"><span class="muted">Loading…</span></div>
            </div>
            <div class="card" id="dash-subtitles-card" style="display:none">
              <div class="row center"><h3 style="margin:0;font-size:1.05rem">Subtitles</h3><span class="badge info" style="margin-left:8px;font-size:.66rem;">Bazarr</span></div>
              <div id="dash-subtitles-body" class="tiny" style="line-height:1.8;margin-top:6px"><span class="muted">Loading…</span></div>
              <a href="#/subtitles" class="tiny" style="display:inline-block;margin-top:6px;">Open Subtitles →</a>
            </div>
            <div class="card">
              <h3 style="font-size:1rem;margin:0 0 12px">Quick actions</h3>
              <div class="pill-row" style="display:flex;gap:8px;flex-wrap:wrap">
                <button id="qa-triage" class="chip">View items needing attention</button>
                <button id="qa-track-order" class="chip">Manage tracks</button>
                <button id="qa-artwork" class="chip">Re-pull artwork</button>
                <button id="qa-artwork-repair" class="chip" title="One-time sweep: removes any on-disk artwork file that isn't actually a valid image (e.g. a CDN error page saved before download validation existed), so the next artwork fetch can replace it">Repair corrupt artwork</button>
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
    document.getElementById("dash-scan-full")?.addEventListener("click") { e ->
        if ((e.currentTarget as? HTMLElement)?.hasAttribute("disabled") == true) return@addEventListener
        (document.getElementById("dash-scan-split") as? HTMLElement)?.classList?.remove("open")
        scope.launch { triggerDashboardScan(scope, resume = false, full = true) }
    }
    (document.getElementById("dash-scan-split") as? HTMLElement)?.querySelector(".menu-btn")?.let { caret ->
        (caret as? HTMLElement)?.addEventListener("click") { e ->
            e.stopPropagation()
            (document.getElementById("dash-scan-split") as? HTMLElement)?.classList?.toggle("open")
        }
    }
    document.addEventListener("click") { (document.getElementById("dash-scan-split") as? HTMLElement)?.classList?.remove("open") }
    document.getElementById("dash-triage")?.addEventListener("click") { App.navigate("/library?filter=attention") }
    document.getElementById("dash-browse-all-footer")?.addEventListener("click") { App.navigate("/library?filter=attention") }
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

    document.getElementById("qa-artwork-repair")?.addEventListener("click") {
        scope.launch {
            setQaFeedback("Checking on-disk artwork for corrupt files…", "badge")
            val ok = MediaApi.batchArtworkRepair()
            setQaFeedback(
                if (ok) "Artwork repair sweep started — check Activity for a per-item History entry on anything removed ✓" else "Failed to start the artwork repair sweep",
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
        loadAttentionBreakdown()
        loadRecentActivity()
        loadDashSubtitlesCard()
        val status = MediaApi.scanStatus()
        when (status?.status) {
            "RUNNING" -> {
                setDashScanRunning(status.processedCount)
                // Bug fix (2026-09-05) — see renderDashDeferredBanner's doc: a page load after a run
                // already deferred must show the paused-for-TV banner immediately (overwriting the
                // generic one setDashScanRunning just wrote), not wait for a live JobEvent.Deferred that
                // already fired before this page connected.
                if (status.deferred) renderDashDeferredBanner(scope, status.deferredDevices, status.jobId ?: "")
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

private suspend fun loadDashboardStats() {
    val stats = MediaApi.stats() ?: return
    (document.getElementById("stat-movies") as? HTMLElement)?.textContent = stats.movies.toString()
    (document.getElementById("stat-tv") as? HTMLElement)?.textContent = stats.tvEpisodes.toString()
    (document.getElementById("stat-nfo") as? HTMLElement)?.textContent = "${stats.nfoCoverage}%"
    // Phase 117: "Items needing attention" now reads the same triage total the breakdown below sums —
    // it used to be store.totalIssueCount() (an untagged-only SQL sum), a different, smaller number.
}

/** Phase 117, redesigned Phase 146: a two-column grid, one cell per triage issue type — every type,
 *  always, including zeros — each cell fully clickable through to the Library pre-filtered to that
 *  issue. Zero-count cells are dimmed but stay clickable (an empty filtered Library is still a valid,
 *  honest result). Mirrors the Triage dock's phrasing (Shell.kt triageSubline). */
private suspend fun loadAttentionBreakdown() {
    val count = MediaApi.getTriageCount()
    val el = document.getElementById("attention-breakdown") as? HTMLElement ?: return
    if (count == null || count.types.isEmpty()) {
        el.innerHTML = """<span class="muted tiny">Couldn't load the issue breakdown.</span>"""
        return
    }
    (document.getElementById("stat-issues") as? HTMLElement)?.textContent = count.total.toString()
    (document.getElementById("attention-count") as? HTMLElement)?.let {
        it.textContent = "${count.total} items"
        it.style.display = ""
    }
    val byKey = count.types.associateBy { it.key }
    // Phase 146: fixed, severity-grouped order + a bad/warn severity per type — neither exists on
    // TriageTypeCount, so both are authored here. Any type not in this list (forward-compat with a
    // future triage type) renders after these, defaulting to "bad".
    val ordered = ATTENTION_ROW_ORDER.mapNotNull { (key, sev) -> byKey[key]?.let { it to sev } } +
        count.types.filter { it.key !in ATTENTION_ROW_ORDER.map { o -> o.first } }.map { it to "bad" }
    el.innerHTML = buildString {
        append("""<div class="attn-breakdown">""")
        for ((t, severity) in ordered) {
            val zero = t.instances == 0
            val countLabel = if (t.instances != t.titles) "${t.instances} (${t.titles} title${if (t.titles != 1) "s" else ""})" else "${t.instances}"
            val badgeCls = if (zero) "badge" else "badge $severity"
            append("""<div class="abk${if (zero) " zero" else ""}" data-issue-filter="${t.key}">""")
            append("""<span class="abk-l"><b>${t.label.esc()}</b><span class="d">${t.description.esc()}</span></span>""")
            append("""<span class="$badgeCls">${if (zero) "✓ 0" else countLabel}</span>""")
            append("</div>")
        }
        append("</div>")
    }
    el.querySelectorAll("[data-issue-filter]").let { nodes ->
        for (i in 0 until nodes.length) {
            val row = nodes.item(i) as? HTMLElement ?: continue
            row.addEventListener("click") {
                val key = row.getAttribute("data-issue-filter") ?: return@addEventListener
                // Phase 163: these two now open the segment editor's cross-library sheet, not Library —
                // Library has nothing to show for them (no per-episode row, no bulk action).
                when (key) {
                    "segments_lowconf" -> App.navigate("/segments?filter=lowconf")
                    "no_segments" -> App.navigate("/segments?filter=none")
                    else -> App.navigate("/library?filter=$key")
                }
            }
        }
    }
}

// Phase 146 §A4: fixed, severity-grouped row order for the attention breakdown (not alphabetical, not
// count-sorted — a stable position lets an operator build muscle memory). `missing_artwork` is the 10th
// triage type the design mockup omitted; kept as a bad-severity cell next to `missing_still` (same class
// of "missing visual asset" issue) per the dev-review addendum's recommendation.
private val ATTENTION_ROW_ORDER = listOf(
    "untagged" to "bad",
    "missing_still" to "bad",
    "missing_artwork" to "bad",
    "cascade_mismatch" to "warn",
    "language_mix" to "warn",
    "multi_default" to "warn",
    "cover_as_video" to "bad",
    "segments_lowconf" to "warn",
    "no_segments" to "warn",
    "zero_audio" to "bad",
    "duplicate" to "bad",
    // Bug fix (Ravilo auto-play-next loop): duplicate episode FILES — same severity class as a duplicate
    // library entry, and kept next to it: both are "one thing exists twice" problems.
    "duplicate_episode" to "bad",
    "missing_from_source" to "bad",
)

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

private suspend fun triggerDashboardScan(scope: CoroutineScope, resume: Boolean, full: Boolean = false) {
    val btn = document.getElementById("dash-scan") as? HTMLButtonElement ?: return
    if (btn.disabled) return

    val started = if (resume) MediaApi.resumeScan() else MediaApi.startScan(full)
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
                // Phase 178 §FR-178-2/FR-178-4 — a scheduled/event-driven run is waiting for a TV to
                // stop playing before its heavy steps proceed. "Run anyway" is a one-run override —
                // nothing is written to config.
                is JobEvent.Deferred -> renderDashDeferredBanner(scope, event.devices, event.jobId)
                is JobEvent.Resumed -> {
                    val banner = document.getElementById("dash-scan-banner") as? HTMLElement
                    banner?.innerHTML = """<span class="badge">Resumed — scanning…</span>"""
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

/** The split button's caret + "full rescan" menu item aren't `<button>`s, so [HTMLButtonElement.disabled]
 *  doesn't reach them — set/clear the `disabled` attribute by hand so the click handlers' own
 *  `hasAttribute("disabled")` guard (matching the pipe-run split button's pattern) actually blocks them
 *  while a scan is running. */
private fun setDashScanSplitDisabled(disabled: Boolean) {
    val menuBtn = (document.getElementById("dash-scan-split") as? HTMLElement)?.querySelector(".menu-btn") as? HTMLElement
    val fullItem = document.getElementById("dash-scan-full") as? HTMLElement
    for (el in listOfNotNull(menuBtn, fullItem)) {
        if (disabled) el.setAttribute("disabled", "") else el.removeAttribute("disabled")
    }
}

private fun setDashScanIdle() {
    val btn = document.getElementById("dash-scan") as? HTMLButtonElement ?: return
    btn.disabled = false
    btn.textContent = "▶ Scan library"
    btn.onclick = null
    setDashScanSplitDisabled(false)
}

private fun setDashScanRunning(processedCount: Int) {
    val btn = document.getElementById("dash-scan") as? HTMLButtonElement ?: return
    btn.disabled = true
    btn.textContent = "Scanning…"
    setDashScanSplitDisabled(true)
    val banner = document.getElementById("dash-scan-banner") as? HTMLElement ?: return
    val countNote = if (processedCount > 0) "Scanning — $processedCount item${if (processedCount != 1) "s" else ""} processed so far…" else "Scanning — items appear in Library as they are processed."
    banner.innerHTML = """<span class="badge">$countNote</span> <button id="cancel-scan-btn" class="btn sm ghost" style="margin-left:8px">Cancel</button>"""
}

/** Phase 178 §FR-178-2/FR-178-4 — shared by the live WS `JobEvent.Deferred` handler and the page-load
 *  hydration path below. Bug fix (2026-09-05): hydration used to call [setDashScanRunning] unconditionally
 *  whenever `status == RUNNING`, so a page load/reload *after* the moment a run deferred (the common case
 *  — nobody has the Dashboard open continuously) showed a plain "Scanning — items appear as processed…"
 *  banner forever, with no way to discover the run was just waiting on a TV or that "Run anyway" existed.
 *  A live incident held a scheduled scan deferred for 3+ hours straight on exactly this household. */
private fun renderDashDeferredBanner(scope: CoroutineScope, devices: List<String>, jobId: String) {
    val who = devices.joinToString(", ").ifBlank { "a device" }.esc()
    val banner = document.getElementById("dash-scan-banner") as? HTMLElement ?: return
    banner.innerHTML = """<span class="badge warn">Paused — TV is watching ($who)</span> <button id="run-anyway-btn" class="btn sm ghost" style="margin-left:8px">Run anyway</button>"""
    document.getElementById("run-anyway-btn")?.addEventListener("click") {
        scope.launch { MediaApi.runPipelineAnyway(jobId) }
    }
}

private fun setDashScanCancelled(processedCount: Int, scope: CoroutineScope) {
    val btn = document.getElementById("dash-scan") as? HTMLButtonElement ?: return
    btn.disabled = false
    btn.textContent = "▶ New scan"
    btn.onclick = null
    setDashScanSplitDisabled(false)
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

// Phase 157 (FR-BZ1-3) — first external-service status card on the Dashboard (no prior Radarr/Sonarr/
// Seerr card existed here, per the addendum). Hidden entirely when Bazarr is off, same as every other
// subtitle surface.
private suspend fun loadDashSubtitlesCard() {
    val card = document.getElementById("dash-subtitles-card") as? HTMLElement ?: return
    val overview = dev.jellystructure.api.BazarrApi.overview()
    if (overview == null || !overview.connected) { card.style.display = "none"; return }
    card.style.display = "block"
    val latest = dev.jellystructure.api.BazarrApi.history(0, 1).firstOrNull()
    val latestLine = latest?.let { "Latest: ${(it.language ?: "").uppercase()} ${(it.provider ?: "")}" } ?: "No recent activity"
    document.getElementById("dash-subtitles-body")?.innerHTML = """
        <div>Wanted: <b>${overview.wantedMovies + overview.wantedEpisodes}</b></div>
        <div>Providers: <b>${overview.providersHealthy}/${overview.providersTotal}</b> healthy</div>
        <div class="muted">${latestLine}</div>
    """.trimIndent()
}
