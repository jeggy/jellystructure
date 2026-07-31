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
private fun nowMs(): Double = js("Date.now()")

private var activitySocket: WebSocket? = null
private var activityScope: CoroutineScope? = null
private var jobItemCount = 0
private var jobDoneCount = 0
private var jobFailCount = 0
private var jobTotalCount = 0    // Phase 116: real worklist size from the Started event (0 = unknown)
private var jobProgressCount = 0 // Phase 116: attempted-so-far (success+failure) from FileProgress events
private var jobStartMs = 0.0     // Phase 116: for the rolling items/sec → "~T remaining" estimate
private var scanRunning = false
private var activeLogCategory: String = ""
private var errorsOnlyFilter: Boolean = false
private var activeRunFilter: String? = null   // 93g: scope the log to one scan/pipeline run
private var jobsPollActive = false   // Phase 109: true while the "Jobs & workers" segment is showing

// Phase 135 — the whole ordered step plan for the active/last run, which step is active, and a
// one-line result summary per finished step (all reset per renderActivity()/on "started").
private var stepPlan: List<String> = emptyList()
private var activeStepName: String? = null
private val stepSummaries = mutableMapOf<String, String>()
private var runTrigger: String? = null
private var runScope: String? = null
private var runType: String? = null
private var activeStepFilter: String? = null   // FR-135-3 item 7: scope the log to one pipeline step

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
    val scope: String = "library", val type: String? = null,
)

fun renderActivity(container: Element, scope: CoroutineScope, query: Map<String, String> = emptyMap()) {
    activityScope = scope
    activitySocket?.close()
    activitySocket = null
    jobItemCount = 0
    jobDoneCount = 0
    jobFailCount = 0
    scanRunning = false
    activeLogCategory = ""
    errorsOnlyFilter = false
    stepPlan = emptyList()
    activeStepName = null
    stepSummaries.clear()
    runTrigger = null; runScope = null; runType = null
    activeStepFilter = null

    container.innerHTML = """
        <div class="pagebar">
          <h1>Activity</h1>
          <span id="act-crumb" style="display:none" class="crumb"></span>
          <span id="run-badge" style="display:none" class="badge"></span>
          <span class="spacer"></span>
          <span id="ws-status" class="badge">Connecting…</span>
          <button id="act-cancel-btn" class="btn sm ghost" style="display:none">Pause</button>
        </div>
        <p class="page-sub">Full activity log: scan events, NFO writes, artwork downloads, and track operations. Streams live over WebSocket; history is loaded from disk on page open.</p>

        <div class="row center" style="margin-bottom:14px;"><span class="seg viewseg" id="viewseg"><span class="on" data-view="console">Scan console</span><span data-view="jobs">Jobs &amp; workers <span class="jobs-count" id="jobs-count-badge" style="display:none;">0</span></span></span></div>

        <div id="view-jobs" style="display:none;">
          <div class="note blue" style="margin-bottom:14px;display:flex;gap:11px;align-items:flex-start;">
            <span style="flex:none;">ℹ</span>
            <div class="tiny" style="line-height:1.6;">Heavy media edits — an audio <b>re-order</b> is an <span class="mono">ffmpeg</span> remux (a full stream copy, 4K included) — are queued through a <b>single media worker</b> and run <b>one at a time</b>. Clicking <b>Apply</b> on several titles, or two admins re-ordering at once, can't saturate CPU/disk or stall the API. Scanning keeps its own parallel workers.</div>
          </div>
          <div class="card" style="margin-bottom:14px;">
            <div class="row center" style="gap:10px;flex-wrap:wrap;" id="jobs-worker-line">
              <span class="muted tiny">Loading…</span>
            </div>
          </div>
          <div class="card" id="jobs-running-card" style="margin-bottom:14px;display:none;">
            <div class="row center"><h4 style="margin:0;">Running now</h4><span class="spacer"></span><span class="mono tiny" id="jobs-running-title"></span></div>
            <hr class="dash" style="margin:10px 0;">
            <div id="jobs-running-body"></div>
          </div>
          <div class="card" style="margin-bottom:14px;">
            <div class="row center"><h4 style="margin:0;">Queue</h4><span class="chip" style="margin-left:8px;"><b id="jobs-q-count">0</b> waiting</span><span class="spacer"></span><span class="tiny muted">processed in order (FIFO)</span></div>
            <hr class="dash" style="margin:10px 0 4px;">
            <div id="jobqueue"><span class="muted tiny">Queue is empty.</span></div>
          </div>
          <div class="card">
            <div class="row center"><h4 style="margin:0;">Recent</h4></div>
            <hr class="dash" style="margin:10px 0 4px;">
            <div id="jobrecent"><span class="muted tiny">Nothing yet.</span></div>
          </div>
        </div>

        <div id="view-console">
        <div id="overall-card" class="card" style="display:none;margin-bottom:14px">
          <div id="step-chips" style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px"></div>
          <div class="row center">
            <b id="ov-step-label">Overall</b>
            <span class="spacer"></span>
            <span class="mono tiny" id="ov-label">0 items</span>
          </div>
          <div class="bar" style="margin-top:8px"><i id="ov-bar" style="width:0%"></i></div>
          <div id="act-chips" style="display:flex;gap:10px;flex-wrap:wrap;margin-top:10px"></div>
        </div>

        <div id="act-columns" class="row" style="display:none;align-items:stretch;gap:14px;margin-bottom:14px">
          <div class="card fill" id="workers-card" style="min-width:0">
            <div class="row center">
              <h4 style="margin:0">Workers</h4>
              <span class="spacer"></span>
              <span class="mono tiny muted" id="workers-count"></span>
            </div>
            <hr class="dash" style="margin:10px 0">
            <div id="workers-list" class="tiny" style="display:flex;flex-direction:column;gap:6px;height:150px;overflow-y:auto">
              <div class="muted">No active workers.</div>
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
            <select id="step-filter" class="input" style="height:26px;padding:0 6px;font-size:.78rem;display:none" title="Scope the log to one pipeline step">
              <option value="">All steps</option>
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
        </div><!-- /view-console -->
    """.trimIndent()

    container.querySelector("#act-cancel-btn")?.addEventListener("click") {
        scope.launch { MediaApi.cancelScan() }
    }

    // Phase 109 — "Jobs & workers" segmented view (design/app/activity.html #viewseg): the media-worker
    // queue, polled independently of the scan console's own WS-pushed log — its own JobEvent variant is
    // a small, low-frequency stream that doesn't need the log console's heavier text-based dispatch.
    jobsPollActive = false
    container.querySelector("#viewseg")?.addEventListener("click") { e ->
        val target = (e.target as? HTMLElement)?.closest("[data-view]") as? HTMLElement ?: return@addEventListener
        val jobs = target.getAttribute("data-view") == "jobs"
        val segOpts = container.querySelectorAll("#viewseg [data-view]")
        for (i in 0 until segOpts.length) {
            val opt = segOpts.item(i) as? HTMLElement ?: continue
            if (opt == target) opt.classList.add("on") else opt.classList.remove("on")
        }
        (container.querySelector("#view-console") as? HTMLElement)?.style?.display = if (jobs) "none" else ""
        (container.querySelector("#view-jobs") as? HTMLElement)?.style?.display = if (jobs) "" else "none"
        jobsPollActive = jobs
        if (jobs) scope.launch { pollJobsPanel(container) }
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

    // Phase 135 (FR-135-3 item 7) — step picker: scope the log to one pipeline step.
    (container.querySelector("#step-filter") as? HTMLSelectElement)?.addEventListener("change") { ev ->
        activeStepFilter = (ev.target as? HTMLSelectElement)?.value?.ifBlank { null }
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
            applyScanStatus(container, st)   // Phase 135: seed step chips + trigger/scope/type badge
            updateActivityChips(container)
            updateOvLabel(container)
            pollWorkers(container)
        } else {
            hideJobUI(container)
        }
    }
    wireLogResize(container)
}

/** Phase 135 — apply the step plan/active step/run descriptors from a [MediaApi.ScanStatus] poll. Used
 *  both as the source of truth for a page that (re)loads mid-run (before any WS event arrives) and as a
 *  periodic supplement in [pollWorkers], since a plain (non-pipeline) `/scan` run never broadcasts a
 *  `pipeline_plan`/`step_started` WS event — polling is the only way its chip/badge ever populate. */
private fun applyScanStatus(container: Element, st: dev.jellystructure.api.ScanStatus) {
    if (st.stepPlan.isNotEmpty() && stepPlan != st.stepPlan) {
        stepPlan = st.stepPlan
        renderStepChips(container)
    }
    if (st.activeStep != null && st.activeStep != activeStepName) {
        activeStepName = st.activeStep
        (container.querySelector("#ov-step-label") as? HTMLElement)?.textContent = stepLabel(st.activeStep)
        renderStepChips(container)
    }
    if (st.trigger != runTrigger || st.scope != runScope || st.type != runType) {
        runTrigger = st.trigger; runScope = st.scope; runType = st.type
        renderRunBadge(container)
    }
}

/** Phase 135 (FR-135-4) — badge the run's trigger/scope/type in the Activity header, e.g.
 *  "Scheduled · Full pipeline · ignoring freshness" or "Manual · Library scan". */
private fun renderRunBadge(container: Element) {
    val el = container.querySelector("#run-badge") as? HTMLElement ?: return
    val trigger = runTrigger
    if (trigger == null) { el.style.display = "none"; return }
    val triggerLabel = when (trigger) {
        "manual" -> "Manual"; "scheduled" -> "Scheduled"; "startup" -> "Startup"; "ingest" -> "Realtime ingest"
        else -> trigger.replaceFirstChar { it.uppercase() }
    }
    val scopeLabel = if (runScope == "pipeline") "Full pipeline" else "Library scan"
    val typeSuffix = if (runType == "full") " · ignoring freshness" else ""
    el.textContent = "$triggerLabel · $scopeLabel$typeSuffix"
    el.style.display = ""
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
            // Phase 135 (FR-135-4) — badge scope/type alongside trigger in the run picker too.
            val scopeLabel = if (r.scope == "pipeline") "pipeline" else "scan"
            val typeSuffix = if (r.type == "full") " (full)" else ""
            sb.append("""<option value="${r.runId.escapeHtml()}">${r.trigger.escapeHtml()} · $scopeLabel$typeSuffix · $whenStr · $status</option>""")
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
        val stepOk = activeStepFilter == null || line.getAttribute("data-step") == activeStepFilter
        line.style.display = if (catOk && lvlOk && runOk && stepOk) "" else "none"
    }
}

private suspend fun loadLogHistory(container: Element) {
    runCatching {
        val page: ActivityLogPageDto = httpClient.get("/api/activity/log") {
            parameter("pageSize", "200")
            activeRunFilter?.let { parameter("run", it) }   // 93g: server-side scope to one run
            activeStepFilter?.let { parameter("step", it) } // Phase 135: server-side scope to one step
        }.body()
        val console = container.querySelector("#activity-console") ?: return
        if (page.entries.isEmpty()) {
            console.innerHTML = """<div class="muted tiny">No activity entries${if (activeRunFilter != null || activeStepFilter != null) " for this filter" else " yet"}.</div>"""
            return
        }
        console.innerHTML = ""
        page.entries.forEach { entry ->
            appendLogEntry(container, entry.level, entry.category, entry.message, ts = entry.ts, runId = entry.runId, stepTag = entry.step)
        }
        (console as? HTMLElement)?.let { it.scrollTop = it.scrollHeight.toDouble() }
    }.onFailure {
        val console = container.querySelector("#activity-console")
        console?.innerHTML = """<div class="muted tiny">Could not load log history.</div>"""
    }
}

// Phase 109 — polls GET /api/jobs while the "Jobs & workers" segment is visible and re-renders the
// worker line, running card, queue and recent lists. Mirrors design/app/activity.html #view-jobs.
private suspend fun pollJobsPanel(container: Element) {
    while (jobsPollActive) {
        refreshJobsPanel(container)
        delay(2000)
    }
}

private suspend fun refreshJobsPanel(container: Element) {
    val summary = MediaApi.getJobsSummary()
    if (summary != null) renderJobsPanel(container, summary)
}

private fun jobTypeLabel(type: String): String = when (type) {
    "reorder" -> "audio/subtitle re-order"
    "remove" -> "track removal"
    "bulk_reorder" -> "bulk re-order"
    else -> type
}

private fun renderJobsPanel(container: Element, s: dev.jellystructure.api.JobsSummary) {
    (container.querySelector("#jobs-count-badge") as? HTMLElement)?.let {
        val n = s.queued.size + (if (s.running != null) 1 else 0)
        it.textContent = n.toString()
        it.style.display = if (n > 0) "" else "none"
    }

    (container.querySelector("#jobs-worker-line") as? HTMLElement)?.innerHTML = """
        <span class="wk-dot ${if (s.busy) "busy" else "idle"}"></span><b>Media worker</b>
        <span class="badge ${if (s.busy) "warn" else ""}">${if (s.busy) "busy" else "idle"}</span>
        <span class="muted tiny">concurrency 1 · FIFO queue · remux runs at low I/O priority</span>
        <span class="spacer"></span>
        <span class="chip"><b>${if (s.running != null) 1 else 0}</b> running</span>
        <span class="chip"><b>${s.queued.size}</b> queued</span>
        <span class="chip ok" style="background:var(--ok-soft);">${s.doneToday} done today</span>"""

    val runningCard = container.querySelector("#jobs-running-card") as? HTMLElement
    if (s.running != null) {
        val r = s.running
        runningCard?.style?.display = ""
        (container.querySelector("#jobs-running-title") as? HTMLElement)?.textContent = r.label
        val startedStr = r.startedAt?.let { dev.jellystructure.formatStoredTs(it.toString()) } ?: "?"
        (container.querySelector("#jobs-running-body") as? HTMLElement)?.innerHTML = """
            <div class="row center" style="gap:8px;flex-wrap:wrap;">
              <span class="badge warn">${jobTypeLabel(r.type).esc()}</span>
              <span class="muted tiny mono">ffmpeg -map 0 -c copy (remux)</span>
              <span class="spacer"></span>
              <span class="tiny muted">queued by <b>${r.enqueuedBy.esc()}</b> · started $startedStr</span>
              <span class="btn sm bad" id="jobs-cancel-running" data-job-id="${r.id}">Cancel</span>
            </div>
            <div class="bar" style="margin-top:10px;"><i style="width:${r.pct.coerceIn(0.0, 100.0)}%"></i></div>
            <div class="tiny muted mono" style="margin-top:6px;">${if (r.speed != null) "speed=${r.speed.esc()} · " else ""}${r.pct.toInt()}%${if (r.fileCount > 1) " · file ${r.filesDone + 1} of ${r.fileCount}" else ""}${r.etaSeconds?.let { " · ~${formatRemaining(it * 1000.0)} left" } ?: ""}</div>"""
    } else {
        runningCard?.style?.display = "none"
    }

    (container.querySelector("#jobs-q-count") as? HTMLElement)?.textContent = s.queued.size.toString()
    val queueEl = container.querySelector("#jobqueue") as? HTMLElement
    queueEl?.innerHTML = if (s.queued.isEmpty()) """<span class="muted tiny">Queue is empty.</span>""" else
        s.queued.mapIndexed { idx, j ->
            """<div class="jobrow" data-job="${j.id}">
                 <span class="jq-pos">${idx + 1}</span>
                 <div class="jq-main"><div class="jq-title">${j.label.esc()}</div><div class="jq-sub">${jobTypeLabel(j.type).esc()} · queued by ${j.enqueuedBy.esc()} · ${dev.jellystructure.formatStoredTs(j.createdAt.toString())}</div></div>
                 <span class="badge">queued</span>
                 <span class="btn sm ghost jq-cancel" data-job-id="${j.id}">Cancel</span>
               </div>"""
        }.joinToString("")

    val recentEl = container.querySelector("#jobrecent") as? HTMLElement
    recentEl?.innerHTML = if (s.recent.isEmpty()) """<span class="muted tiny">Nothing yet.</span>""" else
        s.recent.joinToString("") { j ->
            val ic = if (j.state == "done") """<span class="jq-ic ok">✓</span>""" else """<span class="jq-ic bad">✗</span>"""
            val took = if (j.startedAt != null && j.finishedAt != null) "took ${(j.finishedAt - j.startedAt).coerceAtLeast(0)}s" else ""
            val sub = when (j.state) {
                "done" -> "${jobTypeLabel(j.type)} · $took · by ${j.enqueuedBy}"
                "cancelled" -> "cancelled"
                else -> "failed · ${j.error ?: "unknown error"}"
            }
            val badgeCls = if (j.state == "done") "badge ok" else "badge bad"
            val retryBtn = if (j.state == "failed" || j.state == "cancelled")
                """<span class="btn sm ghost jq-retry" data-job-id="${j.id}">Retry</span>""" else ""
            """<div class="jobrow">
                 $ic
                 <div class="jq-main"><div class="jq-title">${j.label.esc()}</div><div class="jq-sub">${sub.esc()}</div></div>
                 <span class="$badgeCls">${j.state.esc()}</span>
                 $retryBtn
               </div>"""
        }

    // Wire cancel/retry buttons fresh each render (innerHTML was just replaced).
    container.querySelectorAll(".jq-cancel, #jobs-cancel-running").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") {
                val jobId = btn.getAttribute("data-job-id") ?: return@addEventListener
                activityScope?.launch { MediaApi.cancelJob(jobId); refreshJobsPanel(container) }
            }
        }
    }
    container.querySelectorAll(".jq-retry").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") {
                val jobId = btn.getAttribute("data-job-id") ?: return@addEventListener
                activityScope?.launch { MediaApi.retryJob(jobId); refreshJobsPanel(container) }
            }
        }
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
            jobTotalCount = total.toIntOrNull()?.coerceAtLeast(0) ?: 0
            jobProgressCount = 0
            jobStartMs = nowMs()
            scanRunning = true
            showJobUI(container)
            (container.querySelector("#ov-bar") as? HTMLElement)?.style?.width = "0%"
            (container.querySelector("#act-cancel-btn") as? HTMLElement)?.style?.display = ""
            (container.querySelector("#act-crumb") as? HTMLElement)?.let { it.textContent = "Scanning"; it.style.display = "" }
            updateActivityChips(container)
            updateOvLabel(container)
            appendLogEntry(container, "info", "scan", "▶ Job $jobId started${if (jobTotalCount > 0) " — $jobTotalCount item${if (jobTotalCount != 1) "s" else ""}" else ""}")
            activityScope?.launch { pollWorkers(container) }
        }
        "progress" -> {
            val current = extractJsonField(raw, "current") ?: "?"
            val total = extractJsonField(raw, "total") ?: "?"
            // Phase 116: the scanner precomputes its worklist up front, so `total` here is exact —
            // drives both the overall bar and (via jobTotalCount from Started) the ETA label.
            val cur = current.toIntOrNull(); val tot = total.toIntOrNull()
            if (cur != null && tot != null && tot > 0) {
                val pct = (cur * 100 / tot).coerceIn(0, 100)
                (container.querySelector("#ov-bar") as? HTMLElement)?.style?.width = "$pct%"
                if (jobTotalCount <= 0) jobTotalCount = tot
                jobProgressCount = cur
                updateOvLabel(container)
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
            (container.querySelector("#run-badge") as? HTMLElement)?.style?.display = "none"
            (container.querySelector("#workers-chip") as? HTMLElement)?.style?.display = "none"
            appendLogEntry(container, "info", "scan", "■ Job $jobId done — $succeeded succeeded, $failed failed")
            activityScope?.launch { loadRuns(container) }   // 93g: surface the just-finished run in the picker
        }
        // Phase 135 — step-aware pipeline progress. scan_files keeps the events above untouched
        // (started/progress/item_scanned/file_done); every step gets these three instead.
        "pipeline_plan" -> {
            stepPlan = extractJsonStringArray(raw, "steps")
            activeStepName = "scan_files"
            stepSummaries.clear()
            renderStepChips(container)
        }
        "step_started" -> {
            val step = extractJsonField(raw, "step") ?: "?"
            val total = extractJsonField(raw, "total")?.toIntOrNull() ?: 0
            activeStepName = step
            stepSummaries.remove(step)
            jobTotalCount = total
            jobProgressCount = 0
            jobStartMs = nowMs()
            (container.querySelector("#ov-step-label") as? HTMLElement)?.textContent = stepLabel(step)
            (container.querySelector("#ov-bar") as? HTMLElement)?.style?.width = "0%"
            renderStepChips(container)
            updateOvLabel(container)
            appendLogEntry(container, "info", "scan", "▶ ${stepLabel(step)} started" + (if (total > 0) " — $total item${if (total != 1) "s" else ""}" else ""), stepTag = step)
        }
        "step_progress" -> {
            val step = extractJsonField(raw, "step") ?: "?"
            val current = extractJsonField(raw, "current")?.toIntOrNull()
            val total = extractJsonField(raw, "total")?.toIntOrNull()
            activeStepName = step
            if (current != null && total != null && total > 0) {
                val pct = (current * 100 / total).coerceIn(0, 100)
                (container.querySelector("#ov-bar") as? HTMLElement)?.style?.width = "$pct%"
                jobTotalCount = total
                jobProgressCount = current
                updateOvLabel(container)
            }
        }
        "step_finished" -> {
            val step = extractJsonField(raw, "step") ?: "?"
            val summary = extractJsonField(raw, "summary") ?: ""
            stepSummaries[step] = summary
            renderStepChips(container)
            appendLogEntry(container, "info", "scan", "■ ${stepLabel(step)} done — $summary", stepTag = step)
        }
        "log_line" -> {
            val level = extractJsonField(raw, "level") ?: "info"
            val category = extractJsonField(raw, "category") ?: "system"
            val message = extractJsonField(raw, "message") ?: ""
            val runId = extractJsonField(raw, "runId")
            val step = extractJsonField(raw, "step")
            appendLogEntry(container, level, category, message, runId = runId, stepTag = step)
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
            renderWorkersList(container, status.activeItems)
            applyScanStatus(container, status)
        }
        delay(2000)
    }
}

/** Shows what each concurrent worker is currently doing (not just the "N/M" count) — polled alongside
 *  the rest of [pollWorkers] since a worker mid-item never fires a discrete WS event of its own; this is
 *  the only way a long-running item (e.g. one fpcalc call in a 200+-episode detect_segments fingerprint
 *  pass) is visible while it's still in flight, rather than only once it finishes or gets stuck long
 *  enough to warrant a log line. */
private fun renderWorkersList(container: Element, items: List<dev.jellystructure.api.ActiveScanItem>) {
    val listEl = container.querySelector("#workers-list") as? HTMLElement ?: return
    val countEl = container.querySelector("#workers-count") as? HTMLElement
    countEl?.textContent = if (items.isEmpty()) "" else "${items.size} active"
    if (items.isEmpty()) {
        listEl.innerHTML = """<div class="muted">No active workers.</div>"""
        return
    }
    val nowSec = (nowMs() / 1000.0).toLong()
    listEl.innerHTML = items.joinToString("") { item ->
        val elapsedSec = (nowSec - item.startedAt).coerceAtLeast(0)
        val stuck = elapsedSec >= 20
        val timeColor = if (stuck) "var(--bad)" else "var(--muted)"
        val detailHtml = item.detail?.let { """<div class="muted" style="font-size:.72rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${it.escapeHtml()}</div>""" } ?: ""
        """<div style="display:flex;flex-direction:column;gap:2px">
             <div class="row center" style="gap:8px">
               <span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${item.label.escapeHtml()}</span>
               <span class="mono tiny" style="color:$timeColor">${formatRemaining(elapsedSec * 1000.0)}</span>
             </div>
             $detailHtml
           </div>"""
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
    renderWorkersList(container, emptyList())
}

/** Phase 116: "N of M · ~T remaining" once a real total is known (jobTotalCount > 0), else the old
 *  open-ended "N items scanned" (unknown-total jobs, e.g. legacy/never-started-total edge cases). */
private fun updateOvLabel(container: Element) {
    val el = container.querySelector("#ov-label") as? HTMLElement ?: return
    if (jobTotalCount <= 0) {
        el.textContent = "$jobItemCount item${if (jobItemCount != 1) "s" else ""} scanned"
        return
    }
    val current = maxOf(jobProgressCount, jobItemCount).coerceAtMost(jobTotalCount)
    val elapsedMs = nowMs() - jobStartMs
    val etaText = if (current in 1 until jobTotalCount && elapsedMs > 1000) {
        val perItemMs = elapsedMs / current
        val remainingMs = perItemMs * (jobTotalCount - current)
        " · ~${formatRemaining(remainingMs)} remaining"
    } else ""
    el.textContent = "$current of $jobTotalCount$etaText"
}

private fun formatRemaining(ms: Double): String {
    val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
    return when {
        totalSec < 60 -> "${totalSec}s"
        totalSec < 3600 -> "${totalSec / 60}m"
        else -> "${totalSec / 3600}h ${(totalSec % 3600) / 60}m"
    }
}

/** Phase 135 — a short display label for a pipeline step id; falls back to the raw id for anything
 *  unrecognized so a future step added server-side never renders blank. */
private fun stepLabel(step: String): String = when (step) {
    "scan_files" -> "Scan"
    "pull_tmdb" -> "TMDB"
    "fetch_artwork" -> "Artwork"
    "sync_imdb_ratings" -> "IMDb"
    "rescan_arr" -> "*arr"
    "write_nfo" -> "NFO"
    "sync_jellyfin" -> "Jellyfin"
    "detect_drift" -> "Drift"
    // Phase 154 (FR-PIPE1-8): was missing, so this fell through to the raw step id in the step chips —
    // the one step most likely to be looked for, now that it can be skipped per run.
    "detect_segments" -> "Segments"
    "wait" -> "Wait"
    "notify" -> "Notify"
    else -> step
}

/** Phase 135 (FR-135-3 item 6) — one chip per step in [stepPlan]: highlights the active phase,
 *  checks off finished ones with their result summary as a tooltip, per FR-135-3 item 6. */
private fun renderStepChips(container: Element) {
    val el = container.querySelector("#step-chips") as? HTMLElement ?: return
    if (stepPlan.isEmpty()) { el.innerHTML = ""; return }
    el.innerHTML = stepPlan.joinToString("") { step ->
        val summary = stepSummaries[step]
        val done = summary != null
        val active = step == activeStepName && !done
        val cls = if (active) "chip act" else "chip"
        val styleAttr = if (done) " style=\"background:var(--ok-soft)\"" else ""
        val icon = if (done) "✓ " else if (active) "⟳ " else ""
        val titleAttr = summary?.let { """ title="${it.escapeHtml()}"""" } ?: ""
        """<span class="$cls"$styleAttr$titleAttr>$icon${stepLabel(step).escapeHtml()}</span>"""
    }
}

/** Phase 135 (FR-135-3 item 7) — lazily add a step to the `#step-filter` dropdown the first time a log
 *  line tagged with it is seen, so the filter always offers exactly the steps this run actually used. */
private fun registerStepFilterOption(container: Element, step: String) {
    val sel = container.querySelector("#step-filter") as? HTMLSelectElement ?: return
    val exists = (0 until sel.options.length).any { i -> (sel.options.item(i) as? HTMLElement)?.getAttribute("value") == step }
    if (!exists) {
        val opt = document.createElement("option")
        opt.setAttribute("value", step)
        opt.textContent = stepLabel(step)
        sel.appendChild(opt)
    }
    sel.style.display = if (sel.options.length > 1) "" else "none"
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

private fun appendLogEntry(container: Element, level: String, category: String, text: String, ts: Long? = null, runId: String? = null, stepTag: String? = null) {
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
    // Phase 135 (FR-135-3 item 7) — a small step badge alongside the category chip when the line
    // belongs to a pipeline step; also registers the step in the filter dropdown on first sight.
    val stepLabelHtml = if (stepTag != null) {
        registerStepFilterOption(container, stepTag)
        "<span class='chip' style='font-size:.6rem;padding:0 4px;background:var(--fill-3)'>${stepLabel(stepTag).escapeHtml()}</span> "
    } else ""
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
    if (stepTag != null) div.setAttribute("data-step", stepTag)
    div.innerHTML = """<span class="ts">$tsStr</span> $catLabel$stepLabelHtml<span style="$colorStyle">${text.escapeHtml()}</span>"""

    val catOk = activeLogCategory.isEmpty() || category == activeLogCategory
    val lvlOk = !errorsOnlyFilter || level == "error" || level == "warn"
    val runOk = activeRunFilter == null || runId == activeRunFilter
    val stepOk = activeStepFilter == null || stepTag == activeStepFilter
    if (!catOk || !lvlOk || !runOk || !stepOk) (div as? HTMLElement)?.style?.display = "none"

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

/** Phase 135 — a `List<String>` field (e.g. `PipelinePlan.steps`): step ids are plain identifiers with
 *  no commas/quotes, so a simple split is safe (unlike the general case a full JSON parser would need). */
private fun extractJsonStringArray(json: String, field: String): List<String> {
    val key = "\"$field\":"
    val start = json.indexOf(key).takeIf { it >= 0 } ?: return emptyList()
    val arrStart = json.indexOf('[', start).takeIf { it >= 0 } ?: return emptyList()
    val arrEnd = json.indexOf(']', arrStart).takeIf { it >= 0 } ?: return emptyList()
    val body = json.substring(arrStart + 1, arrEnd).trim()
    if (body.isEmpty()) return emptyList()
    return body.split(',').map { it.trim().trim('"') }
}
