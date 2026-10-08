package dev.jellystructure.ui

import dev.jellystructure.api.httpClient
import dev.jellystructure.model.FileFixOverview
import dev.jellystructure.model.FileFixRow
import dev.jellystructure.model.FileFixSettingRequest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * Phase 314 — *Make files play directly* (Settings → Libraries): one card, three rows (stereo · surround · Dolby Vision),
 * each with its switch, its counts, **Show the list** (the dry run's rows, nothing written) and **Apply**. The switches
 * act at once, like the job controls on Activity; Apply only queues — the jobs run one at a time on the media lane.
 */
private object FileFixApi {
    suspend fun overview(): FileFixOverview? = runCatching { httpClient.get("/api/file-fix").body<FileFixOverview>() }.getOrNull()
    suspend fun plan(): Boolean = runCatching { httpClient.post("/api/file-fix/plan").status.value in 200..299 }.getOrDefault(false)
    suspend fun rows(kind: String, state: String?): List<FileFixRow> = runCatching {
        httpClient.get("/api/file-fix/$kind/rows") { state?.let { parameter("state", it) }; parameter("limit", 300) }.body<List<FileFixRow>>()
    }.getOrDefault(emptyList())
    suspend fun setting(kind: String, enabled: Boolean, autoNew: Boolean): Boolean = runCatching {
        httpClient.post("/api/file-fix/$kind/setting") { contentType(ContentType.Application.Json); setBody(FileFixSettingRequest(enabled, autoNew)) }.status.value in 200..299
    }.getOrDefault(false)
    suspend fun apply(kind: String): String = runCatching {
        val r = httpClient.post("/api/file-fix/$kind/apply")
        if (r.status.value in 200..299) "Queued ✓" else "Switch it on first"
    }.getOrDefault("Failed")
}

internal fun fileFixSectionHtml(): String = """
            <div class="card set-section" id="sect-filefix" data-tab="libraries">
              <div class="row center" style="gap:8px;flex-wrap:wrap">
                <h3 style="font-size:1rem;margin:0">Make files play directly</h3>
                <span class="spacer"></span>
                <button id="ff-plan" class="btn sm ghost">Find files</button>
              </div>
              <p class="hint" style="margin:8px 0 12px">Adds what a device needs so it can play a file without converting it. The original tracks and pictures always stay. Nothing is written until a kind is switched on and you press <b>Apply</b>. A file a torrent is still seeding is never changed: its added audio goes beside it as a <span class="mono">.mka</span>.</p>
              <div id="ff-status" class="tiny muted" style="margin-bottom:10px"></div>
              <div id="ff-kinds"><span class="muted tiny">Loading…</span></div>
            </div>
"""

private fun el(id: String): HTMLElement? = document.getElementById(id) as? HTMLElement

private fun gb(bytes: Long): String = when {
    bytes >= 1_000_000_000_000 -> "${(bytes / 100_000_000_000) / 10.0} TB"
    bytes >= 1_000_000_000 -> "${bytes / 1_000_000_000} GB"
    else -> "${bytes / 1_000_000} MB"
}

private fun renderFileFix(o: FileFixOverview) {
    val p = o.plan
    el("ff-status")?.innerHTML = when {
        p.running -> "Looking at every film and episode… ${p.done} of ${p.total}"
        p.finishedAt != null -> "Last looked at ${p.total} files. Read in the last 24 h: ${gb(o.readLastDayBytes)} of ${gb(o.readCapBytes)} a night."
        else -> "Press <b>Find files</b> to see what each kind would add. It only reads."
    }
    el("ff-kinds")?.innerHTML = o.kinds.joinToString("") { k ->
        val c = k.counts
        val eligible = (c["would_add"] ?: 0)
        val sidecar = (c["sidecar"] ?: 0)
        val parts = listOfNotNull(
            "$eligible to add".takeIf { eligible > 0 }, "$sidecar seeded, beside it".takeIf { sidecar > 0 },
            "${c["waiting"]} waiting".takeIf { (c["waiting"] ?: 0) > 0 }, "${c["pending"]} queued".takeIf { (c["pending"] ?: 0) > 0 },
            "${c["running"]} running".takeIf { (c["running"] ?: 0) > 0 }, "${c["done"]} done".takeIf { (c["done"] ?: 0) > 0 },
            "${c["failed"]} failed".takeIf { (c["failed"] ?: 0) > 0 }, "${c["skipped"]} skipped".takeIf { (c["skipped"] ?: 0) > 0 },
        ).ifEmpty { listOf("nothing found yet") }
        """<div class="ff-kind" style="border-top:1px solid var(--line);padding:12px 0">
             <div class="row center" style="gap:10px;flex-wrap:wrap">
               <label class="row center" style="gap:8px;cursor:pointer"><input type="checkbox" class="ff-on" data-k="${k.kind}"${if (k.enabled) " checked" else ""}${if (!k.available) " disabled" else ""}><b style="font-size:.9rem">${k.label.esc()}</b></label>
               ${if (!k.available) """<span class="badge warn">unavailable · ${k.unavailableReason?.esc() ?: ""}</span>""" else ""}
               <span class="spacer"></span>
               <span class="btn sm ghost ff-list" data-k="${k.kind}">Show the list</span>
               <button class="btn sm ff-apply" data-k="${k.kind}"${if (!k.enabled || eligible + sidecar == 0) " disabled" else ""}>Apply</button>
             </div>
             <div class="tiny muted" style="margin-top:4px">${k.sentence.esc()}</div>
             <div class="tiny" style="margin-top:4px">${parts.joinToString(" · ")}${if (k.eligibleBytes > 0) " · ~${gb(k.eligibleBytes)} to write" else ""}</div>
             <label class="tiny muted row center" style="gap:6px;margin-top:6px"><input type="checkbox" class="ff-auto" data-k="${k.kind}"${if (k.autoNew) " checked" else ""}${if (!k.enabled) " disabled" else ""}>Apply automatically to new files</label>
             <div class="ff-rows" id="ff-rows-${k.kind}" style="display:none;margin-top:8px"></div>
           </div>"""
    }
}

private fun rowsHtml(rows: List<FileFixRow>): String =
    if (rows.isEmpty()) """<span class="tiny muted">Nothing in this list.</span>"""
    else """<table class="tbl tiny" style="width:100%"><thead><tr><th>Title</th><th>State</th><th>What</th></tr></thead><tbody>""" +
        rows.joinToString("") { r ->
            """<tr><td><a href="#/media/${r.mediaId.esc()}">${r.label.esc()}</a><div class="muted mono" style="font-size:.7rem">${r.path.substringAfterLast('/').esc()}</div></td>""" +
                """<td>${r.state.replace('_', ' ').esc()}</td><td>${r.detail.esc()}</td></tr>"""
        } + "</tbody></table>" + if (rows.size >= 300) """<div class="tiny muted">The first 300.</div>""" else ""

internal fun wireFileFix(scope: CoroutineScope) {
    suspend fun reload() { FileFixApi.overview()?.let { renderFileFix(it) } }
    scope.launch { reload() }
    el("ff-plan")?.addEventListener("click", {
        scope.launch {
            FileFixApi.plan()
            repeat(600) { delay(2000); val o = FileFixApi.overview() ?: return@launch; renderFileFix(o); if (!o.plan.running) return@launch }
        }
    })
    el("ff-kinds")?.addEventListener("click", { ev ->
        val t = ev.target as? HTMLElement ?: return@addEventListener
        val k = t.getAttribute("data-k") ?: return@addEventListener
        when {
            t.classList.contains("ff-list") -> scope.launch {
                val box = el("ff-rows-$k") ?: return@launch
                if (box.style.display == "none") { box.innerHTML = """<span class="tiny muted">Loading…</span>"""; box.style.display = "block"; box.innerHTML = rowsHtml(FileFixApi.rows(k, null)) }
                else box.style.display = "none"
            }
            t.classList.contains("ff-apply") -> scope.launch { t.textContent = "…"; t.textContent = FileFixApi.apply(k); delay(1200); reload() }
        }
    })
    el("ff-kinds")?.addEventListener("change", { ev ->
        val t = ev.target as? HTMLInputElement ?: return@addEventListener
        val k = t.getAttribute("data-k") ?: return@addEventListener
        val on = (document.querySelector(".ff-on[data-k='$k']") as? HTMLInputElement)?.checked == true
        val auto = (document.querySelector(".ff-auto[data-k='$k']") as? HTMLInputElement)?.checked == true
        scope.launch { FileFixApi.setting(k, on, auto && on); reload() }
    })
}
