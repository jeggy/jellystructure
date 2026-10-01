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
          <div class="stat alert" style="cursor:pointer" id="stat-issues-cell"><div class="k">Rows needing you</div><div class="v" id="stat-issues">—</div></div>
          <div class="stat"><div class="k">NFO coverage</div><div class="v" id="stat-nfo">—%</div></div>
          <div class="stat" id="stat-music-cell" style="cursor:pointer;display:none" title="Open the Music kind"><div class="k">♪ Music</div><div class="v" id="stat-music">—</div><div class="tiny muted" id="stat-music-sub"></div></div>
          <div class="stat" id="stat-audiobooks-cell" style="cursor:pointer;display:none" title="Open the Audiobooks kind"><div class="k">Audiobooks</div><div class="v" id="stat-audiobooks">—</div><div class="tiny muted" id="stat-audiobooks-sub"></div></div>
        </div>
        <!-- Phase 285 — one overview of what could be fixed: the headline, the domain chips, since your last visit, the list. -->
        <div class="ov-top" id="ov-head"></div>
        <div id="ov-since"></div>
        <div id="ov-body"><div class="muted tiny" style="margin-top:14px">Loading…</div></div>
    """.trimIndent()

    document.getElementById("dash-browse")?.addEventListener("click") {
        App.navigate("/library")
    }
    // Phase 265 (FR-265-2) — both faces open the pre-run dialog. Resume stays dialog-free: it continues the
    // cancelled run with that run's own plan.
    document.getElementById("dash-scan")?.addEventListener("click") {
        if ((document.getElementById("dash-scan") as? HTMLButtonElement)?.disabled == true) return@addEventListener
        openPipelineRunDialog(scope, "Scan library", full = false) { skip -> triggerDashboardScan(scope, resume = false, skipSteps = skip) }
    }
    document.getElementById("dash-scan-full")?.addEventListener("click") { e ->
        if ((e.currentTarget as? HTMLElement)?.hasAttribute("disabled") == true) return@addEventListener
        (document.getElementById("dash-scan-split") as? HTMLElement)?.classList?.remove("open")
        openPipelineRunDialog(scope, "Scan library (full rescan)", full = true) { skip -> triggerDashboardScan(scope, resume = false, full = true, skipSteps = skip) }
    }
    (document.getElementById("dash-scan-split") as? HTMLElement)?.querySelector(".menu-btn")?.let { caret ->
        (caret as? HTMLElement)?.addEventListener("click") { e ->
            e.stopPropagation()
            (document.getElementById("dash-scan-split") as? HTMLElement)?.classList?.toggle("open")
        }
    }
    document.addEventListener("click") { (document.getElementById("dash-scan-split") as? HTMLElement)?.classList?.remove("open") }
    document.getElementById("stat-issues-cell")?.addEventListener("click") { (document.getElementById("ov-body") as? HTMLElement)?.scrollIntoView() }

    scope.launch {
        loadDashboardStats()
        loadOverview(scope)
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
/**
 * Phase 257 — the Jellyfin settings advisor on the page an admin actually lands on. Every finding the
 * Settings advisor shows (FR-257-1), through the same [advisorFindingHtml]; nothing at all when there is
 * nothing to say or Jellyfin is unreachable (FR-257-3); Re-check wired by the same function as Settings
 * (FR-257-4). FR-257-2 (amended 2026-09-24): critical → warning → info, server-wide before per-library,
 * each library's rows together — then folded to the first finding, the rest behind one *+N more*.
 * [keepOpen] is only ever true for a Re-check reload, so the list does not close under the admin.
 */
/** Phase 278 (FR-278-11) — the Music tile, only when a music library is mapped. */
private suspend fun loadMusicTile() {
    val st = dev.jellystructure.api.MusicApi.status() ?: return
    val h = st.health ?: return
    if (!st.mapped) return
    val cell = document.getElementById("stat-music-cell") as? HTMLElement ?: return
    cell.style.display = ""
    (document.getElementById("stat-music") as? HTMLElement)?.textContent = "${h.albums} albums"
    (document.getElementById("stat-music-sub") as? HTMLElement)?.textContent =
        "${h.matched} matched · ${h.albums - h.coversMissing} covers" + if (h.needsYou > 0) " · ${h.needsYou} need you" else ""
    cell.onclick = { App.navigate("/library?kind=music") }
}

/** Phase 280 (FR-280-7) — the Audiobooks tile, only when an audiobook library is mapped and holds a book. */
private suspend fun loadAudiobooksTile() {
    val h = dev.jellystructure.api.AudiobooksApi.status() ?: return
    if (h.books == 0) return
    val cell = document.getElementById("stat-audiobooks-cell") as? HTMLElement ?: return
    cell.style.display = ""
    (document.getElementById("stat-audiobooks") as? HTMLElement)?.textContent = "${h.books} ${if (h.books == 1) "book" else "books"}"
    (document.getElementById("stat-audiobooks-sub") as? HTMLElement)?.textContent = "${h.parts} parts · ${muTotal(h.durationMs)}"
    cell.onclick = { App.navigate("/library?kind=audiobooks") }
}

private suspend fun loadDashboardStats() {
    loadMusicTile()
    loadAudiobooksTile()
    val stats = MediaApi.stats() ?: return
    (document.getElementById("stat-movies") as? HTMLElement)?.textContent = stats.movies.toString()
    (document.getElementById("stat-tv") as? HTMLElement)?.textContent = stats.tvEpisodes.toString()
    (document.getElementById("stat-nfo") as? HTMLElement)?.textContent = "${stats.nfoCoverage}%"
    // Phase 117: "Items needing attention" now reads the same triage total the breakdown below sums —
    // it used to be store.totalIssueCount() (an untagged-only SQL sum), a different, smaller number.
}
// Phase 146 §A4: fixed, severity-grouped row order for the attention breakdown (not alphabetical, not
// count-sorted — a stable position lets an operator build muscle memory). `missing_artwork` is the 10th
// triage type the design mockup omitted; kept as a bad-severity cell next to `missing_still` (same class
// of "missing visual asset" issue) per the dev-review addendum's recommendation.
private suspend fun triggerDashboardScan(scope: CoroutineScope, resume: Boolean, full: Boolean = false, skipSteps: List<String> = emptyList()) {
    val btn = document.getElementById("dash-scan") as? HTMLButtonElement ?: return
    if (btn.disabled) return

    val started = if (resume) MediaApi.resumeScan() else MediaApi.startScan(full, skipSteps)
    if (!started) {
        val banner = document.getElementById("dash-scan-banner") as? HTMLElement ?: return
        banner.innerHTML = """<span class="badge bad">Scan failed to start — check server connection.</span>"""
        return
    }
    if (!resume) showPipelineToast((if (full) "Full scan started" else "Scan started") + skippedSuffix(skipSteps))
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
                    banner?.innerHTML = """<span class="badge">$countNote</span> <button id="cancel-scan-btn" class="btn sm bad" style="margin-left:8px">Stop scan</button>"""
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

    // Phase 178 amendment (2026-09-26) — a manual run defers within a millisecond of POST /scan, before
    // this socket is listening, and JobEvent.Deferred is broadcast only once. Catch up from the polled
    // status the moment the socket opens, so the page that started the run shows "Paused" and Run anyway.
    ws.onopen = { _: Event ->
        scope.launch {
            val st = MediaApi.scanStatus()
            if (dashScanSocket == ws && st?.status == "RUNNING" && st.deferred) renderDashDeferredBanner(scope, st.deferredDevices, st.jobId ?: "")
        }
    }

    ws.onclose = { _: Event ->
        if (dashScanSocket == ws) dashScanSocket = null
    }

    wireCancelBtn(scope)
}

private fun wireCancelBtn(scope: CoroutineScope) {
    document.getElementById("cancel-scan-btn")?.addEventListener("click") {
        scope.launch {
            val result = MediaApi.cancelScan()
            // Phase 214 (FR-214-3) — say what stopping cannot reach, in the same short form the
            // dashboard's other scan-status badges already use; the full sentence lives on Activity.
            if (result.ok && result.subtitlesStillRunning > 0) {
                val banner = document.getElementById("dash-scan-banner") as? HTMLElement
                val n = result.subtitlesStillRunning
                banner?.innerHTML = """<span class="badge warn">Stopped — Jellyfin still finishing $n subtitle extraction${if (n == 1) "" else "s"} it already started</span>"""
            }
        }
    }
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
    banner.innerHTML = """<span class="badge">$countNote</span> <button id="cancel-scan-btn" class="btn sm bad" style="margin-left:8px">Stop scan</button>"""
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
/** Phase 273 (FR-273-16/20) — *Do it* / *Leave it* on a waiting proposal; shared by the Dashboard and the title page. */
internal fun wireSubtitleDecisions(root: HTMLElement, scope: CoroutineScope, reload: suspend () -> Unit) {
    val oks = root.querySelectorAll("[data-subok]")
    for (i in 0 until oks.length) {
        val b = oks.item(i) as? HTMLElement ?: continue
        b.addEventListener("click", {
            val id = b.getAttribute("data-subok")?.toLongOrNull() ?: return@addEventListener
            b.textContent = "Doing it\u2026"
            scope.launch { dev.jellystructure.api.SubtitleCheckApi.approve(id); reload() }
        })
    }
    val nos = root.querySelectorAll("[data-subno]")
    for (i in 0 until nos.length) {
        val b = nos.item(i) as? HTMLElement ?: continue
        b.addEventListener("click", {
            val id = b.getAttribute("data-subno")?.toLongOrNull() ?: return@addEventListener
            scope.launch { dev.jellystructure.api.SubtitleCheckApi.dismiss(id); reload() }
        })
    }
}


// ── Phase 285 — the overview ─────────────────────────────────────────────────────────────────────────────────

private const val DASH_SHOWALL_KEY = "js-dash-showall"
private const val DASH_CHIP_KEY = "js-dash-chip"
private var ovDto: dev.jellystructure.model.DashboardDto? = null
private var ovChip: String = "all"
private var ovShowAll = false
private val ovOpen = HashSet<String>()

/** Reads the one endpoint, renders it, and marks this visit so the next open measures *Since your last visit* from now. */
private suspend fun loadOverview(scope: CoroutineScope) {
    ovChip = runCatching { window.localStorage.getItem(DASH_CHIP_KEY) }.getOrNull() ?: "all"
    ovShowAll = runCatching { window.localStorage.getItem(DASH_SHOWALL_KEY) }.getOrNull() == "1"
    val d = dev.jellystructure.api.DashboardApi.get()
    ovDto = d
    if (d == null) {
        (document.getElementById("ov-body") as? HTMLElement)?.innerHTML = """<div class="note warn" style="margin-top:14px"><span class="tiny">Couldn’t load the overview.</span></div>"""
        return
    }
    renderOverview(scope)
    dev.jellystructure.api.DashboardApi.seen()
}

private fun ovFmt(n: Int): String = if (n >= 1000) n.toString().reversed().chunked(3).joinToString(" ").reversed() else n.toString()
private fun ovPlural(unit: String, n: Int) = when { n == 1 -> unit; unit == "series" -> "series"; unit == "library" -> "libraries"; else -> unit + "s" }

private fun renderOverview(scope: CoroutineScope) {
    val d = ovDto ?: return
    val domains = d.domains
    if (ovChip != "all" && domains.none { it.id == ovChip }) ovChip = "all"
    (document.getElementById("stat-issues") as? HTMLElement)?.textContent = d.headline.rows.toString()
    // FR-285-6 — the headline: the critical and warning counts in words.
    val head = document.getElementById("ov-head") as? HTMLElement
    head?.innerHTML = when {
        d.firstRun -> """<span class="ov-big">Nothing scanned yet</span>"""
        d.rows.isEmpty() -> """<span class="ov-big">Nothing needs you</span>"""
        else -> buildString {
            val parts = listOfNotNull(
                d.headline.critical.takeIf { it > 0 }?.let { "$it critical" },
                d.headline.warnings.takeIf { it > 0 }?.let { "$it ${if (it == 1) "warning" else "warnings"}" },
                d.headline.info.takeIf { it > 0 }?.let { "$it for information" },
            )
            append("""<span class="ov-big">${parts.joinToString(" · ").ifBlank { "Nothing needs you" }}</span>""")
            if (d.headline.things > 0) append("""<span class="tiny muted">${ovFmt(d.headline.things)} things could be fixed</span>""")
            append("""<span class="spacer" style="flex:1"></span><button class="btn sm ghost" id="ov-all">${if (ovShowAll) "Fold every group" else "Show all"}</button>""")
        }
    }
    // FR-285-8 — since your last visit
    val since = document.getElementById("ov-since") as? HTMLElement
    since?.innerHTML = d.since?.let { s ->
        val when_ = s.since?.let { dev.jellystructure.formatWeekdayClock(it.toString()) } ?: "first visit"
        val items = s.items.joinToString("") { it -> """<span class="chip"><b>${(domains.firstOrNull { g -> g.id == it.domain }?.label ?: dev.jellystructure.model.DashboardDto::class.simpleName.orEmpty()).esc()}</b> ${it.text.esc()}</span>""" }
        """<div class="ov-since"><span class="lb">Since your last visit</span><span class="tiny muted">${when_.esc()}</span>${items.ifEmpty { """<span class="tiny">Nothing new since then.</span>""" }}<a class="tiny" href="#/activity" style="margin-left:auto">the full log is on Activity →</a></div>"""
    } ?: ""
    val body = document.getElementById("ov-body") as? HTMLElement ?: return
    if (d.firstRun) {
        body.innerHTML = """<div class="card ov-empty"><h3>Nothing scanned yet</h3><p>The first scan reads every library, then this page fills in, group by group. It takes a while on a large library; you can leave this page.</p></div>"""
        return
    }
    if (d.rows.isEmpty()) {
        body.innerHTML = """<div class="card ov-empty"><h3>Nothing needs you.</h3><p>Every library, every setting jellystructure checks, is as it should be.</p></div>"""
        return
    }
    val rows = if (ovChip == "all") d.rows else d.rows.filter { it.domain == ovChip }
    val chips = buildString {
        append("""<div class="ov-chips"><button class="chip${if (ovChip == "all") " on" else ""}" data-chip="all">All · ${d.rows.size}</button>""")
        for (g in domains) append("""<button class="chip${if (ovChip == g.id) " on" else ""}" data-chip="${g.id}">${g.label.esc()} · ${g.rows}</button>""")
        append("</div>")
    }
    val bands = listOf("critical" to "Critical", "warning" to "To fix", "info" to "For information")
    val list = buildString {
        append("""<div class="ov-list">""")
        for ((sev, title) in bands) {
            val rs = rows.filter { it.severity == sev }
            if (rs.isEmpty()) continue
            val key = "b-$sev"
            val open = ovShowAll || key in ovOpen
            val shown = if (open) rs else rs.take(3)   // FR-285-4 — three per band, then +N more
            append("""<section class="ov-band w-$sev"><div class="ov-sh"><h3>$title</h3><span class="ov-size">${rs.size} ${if (rs.size == 1) "row" else "rows"}</span></div>""")
            shown.forEach { append(ovRowHtml(it, domains)) }
            val more = rs.size - shown.size
            if (more > 0) append("""<button class="ov-more" data-more="$key">+$more more</button>""")
            else if (key in ovOpen && !ovShowAll) append("""<button class="ov-more" data-less="$key">Show fewer</button>""")
            append("</section>")
        }
        append("</div>")
    }
    val down = if (!d.jellyfinReachable) """<div class="ov-down"><span class="dot bad"></span><b>Jellyfin can’t be reached</b><span class="tiny muted">— its settings and this server’s can’t be checked until it answers.</span></div>""" else ""
    body.innerHTML = chips + down + list
    body.onclick = { ev -> ovClick(ev.target as? Element, scope) }
    (document.getElementById("ov-all") as? HTMLElement)?.onclick = {
        ovShowAll = !ovShowAll; ovOpen.clear()
        runCatching { window.localStorage.setItem(DASH_SHOWALL_KEY, if (ovShowAll) "1" else "0") }
        renderOverview(scope)
    }
    // Advisor rows keep their own Re-check / Apply in Bazarr, wired exactly as Settings wires them.
    val findings = rows.filter { it.findingId != null && it.actionKind != null }.map { r ->
        dev.jellystructure.api.AdvisorFinding(id = r.findingId!!, summary = r.label, currentValue = r.now.orEmpty(), costHere = "", navigationPath = r.path.orEmpty(),
            fieldLabel = "", recommendation = r.recommendation.orEmpty(), tradeoff = r.tradeoff.orEmpty(), severity = r.severity, action = r.actionKind)
    }
    wireAdvisorActions(findings, scope) { loadOverview(scope) }
}

/** FR-285-2 — one row grammar: severity · label + sentence · count · domain chip · what fixing means. */
private fun ovRowHtml(r: dev.jellystructure.model.DashboardRow, domains: List<dev.jellystructure.model.DashboardDomain>): String {
    val dom = domains.firstOrNull { it.id == r.domain }?.label ?: r.domain
    val href = r.href?.let { "#$it" }
    val label = if (href != null) """<a href="$href">${r.label.esc()}</a>""" else """<a>${r.label.esc()}</a>"""
    val count = when {
        r.count != null && r.unit != null -> """<span class="ov-n"><b>${ovFmt(r.count)}</b> ${ovPlural(r.unit, r.count).esc()}</span>"""
        r.now != null -> """<span class="ov-now" title="the value now">${r.now.esc()}</span>"""
        else -> ""
    }
    val fix = when (r.fix) {
        "here" -> """<span class="ov-fix k-here">One click here</span>""" + (r.actionId?.let { """<span class="btn sm" data-act="$it" data-row="${r.id.esc()}">${(r.action ?: "Fix").esc()}</span>""" }
            ?: r.findingId?.takeIf { r.actionKind != null }?.let { """<button class="btn sm" id="advisor-action-${it.esc()}">${(r.action ?: "Apply").esc()}</button><span id="advisor-action-out-${it.esc()}" class="tiny" style="margin-left:6px"></span>""" } ?: "") +
            // Phase 292 (FR-292-15) — the quieter second action, by hand only.
            (r.action2Id?.let { """<span class="btn sm ghost" data-act="$it" data-row="${r.id.esc()}">${(r.action2 ?: "More").esc()}</span>""" } ?: "") +
            (if (r.actionId != null && r.href != null) """<a class="btn sm ghost" href="#${r.href}">Open →</a>""" else "")
        "open" -> """<span class="ov-fix k-open">Open the item</span>""" + (href?.let { """<a class="btn sm ghost" href="$it">${(r.action ?: "Open").esc()} →</a>""" } ?: "")
        "elsewhere" -> """<span class="ov-fix k-else">Change ${if (r.where == "the host") "on the host" else "in " + (r.where ?: "").esc()}</span>""" +
            (r.findingId?.takeIf { r.actionKind != null }?.let { """<button class="btn sm ghost" id="advisor-action-${it.esc()}">${(r.action ?: "Re-check").esc()}</button><span id="advisor-action-out-${it.esc()}" class="tiny" style="margin-left:6px"></span>""" } ?: "")
        else -> """<span class="ov-fix k-info">For information</span>""" + (href?.takeIf { r.action != null }?.let { """<a class="btn sm ghost" href="$it">${r.action!!.esc()} →</a>""" } ?: "")
    }
    val detail = buildString {
        if (r.sentence.isNotBlank()) append("""<div class="ov-s">${r.sentence.esc()}</div>""")
        if (!r.path.isNullOrBlank()) append("""<div class="ov-path">${(r.where ?: "").esc()} › ${r.path.esc()}</div>""")
        if (!r.recommendation.isNullOrBlank()) append("""<div class="ov-s"><b>Set to:</b> ${r.recommendation.esc()}${if (!r.tradeoff.isNullOrBlank() && r.tradeoff != "n/a") " · <b>You lose:</b> " + r.tradeoff.esc() else ""}</div>""")
    }
    return """<div class="ov-row sev-${r.severity}"><span class="ov-sev" title="${r.severity}"></span><div class="ov-main"><div class="ov-l"><span class="ov-dom">${dom.esc()}</span>$label</div>$detail</div>$count<div class="ov-act">$fix</div></div>"""
}

private fun ovClick(t: Element?, scope: CoroutineScope) {
    t ?: return
    t.closest("[data-more]")?.let { ovOpen += it.getAttribute("data-more")!!; renderOverview(scope); return }
    t.closest("[data-less]")?.let { ovOpen -= it.getAttribute("data-less")!!; renderOverview(scope); return }
    t.closest("[data-chip]")?.let { ovChip = it.getAttribute("data-chip")!!; runCatching { window.localStorage.setItem(DASH_CHIP_KEY, ovChip) }; renderOverview(scope); return }
    t.closest("[data-act]")?.let { b ->
        val act = b.getAttribute("data-act") ?: return
        val out = { msg: String -> (b as? HTMLElement)?.textContent = msg }
        // FR-285-10 — the quick actions live on the rows they serve.
        scope.launch {
            when (act) {
                "fetch_artwork" -> { out("Fetching…"); out(if (MediaApi.batchFetchArtwork()) "Started ✓" else "Failed") }
                "artwork_repair" -> { out("Checking…"); out(if (MediaApi.batchArtworkRepair()) "Started ✓" else "Failed") }
                "jf_push" -> { out("Writing…"); out(if (MediaApi.batchJellyfinPush()) "Started ✓" else "Failed") }
                "jf_refresh" -> { out("Sending…"); out(if (MediaApi.jellyfinRefreshAll()) "Triggered ✓" else "Failed") }
                // Phase 292 (FR-292-15) — never run on their own; each is this button.
                "music_lyrics_remove" -> {
                    out("Removing…")
                    val s = dev.jellystructure.api.MusicApi.removeInstrumentalLyrics()
                    out(if (s != null) "Done ✓" else "Failed")
                    s?.let { muToast(it) }
                    if (s != null) loadOverview(scope)
                }
                "music_lyrics_lrclib" -> {
                    out("Sending…")
                    val s = dev.jellystructure.api.MusicApi.tellLrclibInstrumental()
                    out(if (s != null) "Started ✓" else "Failed")
                    s?.let { muToast(it) }
                }
            }
        }
    }
}
