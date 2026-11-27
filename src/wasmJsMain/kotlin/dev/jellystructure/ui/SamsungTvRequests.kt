@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.api.httpClient
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.shared.tv.SamsungTvAdminList
import dev.jellystructure.shared.tv.SamsungTvDecision
import dev.jellystructure.shared.tv.SamsungTvAdminRequest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLElement

private fun samsungLang(): String {
    val asked = window.location.search.removePrefix("?").split("&").firstOrNull { it.startsWith("lang=") }?.removePrefix("lang=")
    if (!asked.isNullOrBlank()) runCatching { localStorage.setItem("js-admin-lang", asked) }
    return asked?.takeIf { it.isNotBlank() } ?: runCatching { localStorage.getItem("js-admin-lang") }.getOrNull() ?: "en"
}
internal fun samsungText(key: String, vars: Map<String, String> = emptyMap()) = t("samsung_requests.$key", samsungLang(), vars)
private fun samsungDateJs(epochMs: Double): String = js("new Date(epochMs).toLocaleString()")
private fun samsungClipboard(text: String, success: String, failure: String): Unit = js("""(function(){
    var status = document.getElementById('samsung-status');
    if (!navigator.clipboard || !navigator.clipboard.writeText) { if (status) status.textContent = failure; return; }
    navigator.clipboard.writeText(text).then(function(){ if (status) status.textContent = success; },
        function(){ if (status) status.textContent = failure; });
})()""")
private fun samsungDate(epoch: Long?): String {
    if (epoch == null) return "—"
    val epochMs = epoch.toDouble() * 1000.0
    return samsungDateJs(epochMs)
}

/**
 * DEB-5909 (DEB-5905, FR-9, FR-10) — the menu count and the open page follow `samsung_tv_requests` on `/ws`. The count is what the server
 * last said (the list read when the shell mounts, or an event's `requested`); the admin never adds or subtracts on its own.
 */
object SamsungLive {
    private var requested = 0
    private var openPage: (() -> Unit)? = null

    fun seed(count: Int) { requested = count; paintCount() }

    fun event(e: JobEvent.SamsungTvRequestsChanged) {
        seed(e.requested)
        openPage?.invoke()
    }

    /** The open page registers how to re-read itself; a page that has left the DOM drops itself on the next event. */
    fun follow(container: Element, reload: () -> Unit) {
        openPage = { if (document.body?.contains(container) == true) reload() else openPage = null }
    }

    /** FR-10 — hidden at 0. */
    fun paintCount() {
        val el = document.getElementById("samsung-count-badge") as? HTMLElement ?: return
        el.textContent = if (requested > 0) requested.toString() else ""
        el.style.display = if (requested > 0) "" else "none"
    }
}

/** Reads the list for the menu count (the shell mounts and reconnects with this; the page has its own read). */
suspend fun refreshSamsungNav() {
    try {
        val response = httpClient.get("/api/samsung-tv/requests")
        if (response.status == HttpStatusCode.OK) SamsungLive.seed(response.body<SamsungTvAdminList>().requested)
    } catch (e: CancellationException) { throw e } catch (_: Throwable) {}
}

/** DEB-4842 (FR-5) — all rows and transitions render from GET/POST responses. DEB-5909 (FR-9, FR-11) — it re-reads on the push and groups by viewer. */
fun renderSamsungTvRequests(container: Element, scope: CoroutineScope) {
    var server: SamsungTvAdminList? = null
    lateinit var load: suspend () -> Unit
    fun announce(message: String) { (document.getElementById("samsung-status") as? HTMLElement)?.textContent = message }
    fun draw() {
        val result = server
        val rows = result?.requests.orEmpty()
        val approved = rows.filter { it.state == "APPROVED" || it.state == "READY" }.map { it.duid }.distinct()
        val warnings = result?.certificates?.let { cert -> buildList {
            if (cert.authorDaysLeft <= 60) add(samsungText("cert_warning", mapOf("kind" to samsungText("author"), "date" to cert.authorExpiresOn)))
            if (cert.distributorDaysLeft <= 60) add(samsungText("cert_warning", mapOf("kind" to samsungText("distributor"), "date" to cert.distributorExpiresOn)))
        } }.orEmpty()
        // A push redraws the page: keep what the admin is in the middle of (a typed note, an opened Withdrawn list).
        val notes = rows.mapNotNull { r -> (document.getElementById("samsung-note-${r.id}") as? HTMLInputElement)?.value?.takeIf { it.isNotEmpty() }?.let { r.id to it } }
        val openGroups = rows.map { it.accountId }.distinct().filter { (document.getElementById("samsung-withdrawn-$it") as? HTMLElement)?.hasAttribute("open") == true }
        container.innerHTML = """
            <div class="pagebar"><h1>${samsungText("title").esc()}</h1><span class="spacer"></span><button class="btn sm ghost" id="samsung-refresh">${samsungText("refresh").esc()}</button></div>
            <p class="page-sub">${samsungText("intro").esc()}</p>
            <div id="samsung-status" role="status" aria-live="polite" class="tiny muted"></div>
            ${warnings.joinToString("") { """<div class="notice warning" role="alert">${it.esc()}</div>""" }}
            ${result?.release?.error?.let { """<div class="notice warning" role="alert">${samsungText("release_error").esc()}: ${it.esc()}</div>""" } ?: ""}
            <div class="card" style="padding:16px;margin:16px 0">
              <div class="tiny muted">${samsungText("release").esc()}: ${result?.release?.tag?.esc() ?: "—"} · ${samsungText("checked").esc()}: ${samsungDate(result?.release?.checkedAt).esc()}</div>
              ${if (approved.isNotEmpty()) """<button class="btn sm" data-samsung-copy="${approved.joinToString("\n").esc()}" aria-label="${samsungText("copy_all").esc()}">${samsungText("copy_all").esc()}</button>""" else ""}
            </div>
            <div class="samsung-rows">${if (rows.isEmpty()) """<div class="card" style="padding:20px">${samsungText("empty").esc()}</div>""" else samsungGroups(rows)}</div>
        """.trimIndent()
        for ((id, text) in notes) (document.getElementById("samsung-note-$id") as? HTMLInputElement)?.value = text
        for (account in openGroups) document.getElementById("samsung-withdrawn-$account")?.setAttribute("open", "")
        document.getElementById("samsung-refresh")?.addEventListener("click") { scope.launch { load() } }
        container.querySelectorAll("[data-samsung-decision]").let { buttons ->
            for (i in 0 until buttons.length) {
                val button = buttons.item(i) as? HTMLElement ?: continue
                button.addEventListener("click") {
                    val id = button.getAttribute("data-samsung-id")?.toLongOrNull() ?: return@addEventListener
                    val decision = button.getAttribute("data-samsung-decision") ?: return@addEventListener
                    val note = (document.getElementById("samsung-note-$id") as? HTMLInputElement)?.value?.trim()?.ifEmpty { null }
                    button.setAttribute("disabled", "disabled")
                    scope.launch {
                        try {
                            val response = httpClient.post("/api/samsung-tv/requests/$id/decision") {
                                contentType(ContentType.Application.Json); setBody(SamsungTvDecision(decision, note))
                            }
                            if (response.status == HttpStatusCode.OK) load() else { button.removeAttribute("disabled"); announce(samsungText("save_failed", mapOf("status" to response.status.value.toString()))) }
                        } catch (e: CancellationException) { throw e } catch (_: Throwable) { button.removeAttribute("disabled"); announce(samsungText("load_failed")) }
                    }
                }
            }
        }
        container.querySelectorAll("[data-samsung-copy]").let { buttons ->
            for (i in 0 until buttons.length) {
                val button = buttons.item(i) as? HTMLElement ?: continue
                button.addEventListener("click") {
                    samsungClipboard(button.getAttribute("data-samsung-copy") ?: "", samsungText("copied"), samsungText("copy_failed"))
                }
            }
        }
    }
    load = fetch@{
        try {
            val response = httpClient.get("/api/samsung-tv/requests")
            if (response.status != HttpStatusCode.OK) { announce(samsungText("load_failed")); return@fetch }
            server = response.body()
            draw()
        } catch (e: CancellationException) { throw e } catch (_: Throwable) { announce(samsungText("load_failed")) }
    }
    SamsungLive.follow(container) { scope.launch { load() } }
    scope.launch { load() }
}

private val SAMSUNG_STATE_ORDER = listOf("REQUESTED", "APPROVED", "READY", "REJECTED", "WITHDRAWN")

private fun samsungCount(state: String, n: Int): String =
    if (state in SAMSUNG_STATE_ORDER) samsungText("count_${state.lowercase()}", mapOf("n" to n.toString()))
    else samsungText("count_other", mapOf("n" to n.toString(), "state" to state.lowercase()))

/** DEB-5909 (FR-11) — one block per viewer (`account_id`), blocks by their newest row, rows newest first, Withdrawn closed in its block. */
private fun samsungGroups(rows: List<SamsungTvAdminRequest>): String {
    val newestFirst = compareByDescending<SamsungTvAdminRequest> { it.createdAt }.thenByDescending { it.id }
    return rows.groupBy { it.accountId }.values
        .map { group -> group.sortedWith(newestFirst) }
        .sortedWith(compareByDescending<List<SamsungTvAdminRequest>> { it.first().createdAt }.thenByDescending { it.first().id })
        .joinToString("") { group ->
            val head = group.first()
            val byState = group.groupingBy { it.state }.eachCount()
            val counts = (SAMSUNG_STATE_ORDER + (byState.keys - SAMSUNG_STATE_ORDER.toSet())).filter { it in byState }
                .joinToString("") { st -> """<span class="badge">${samsungCount(st, byState.getValue(st)).esc()}</span>""" }
            val (withdrawn, live) = group.partition { it.state == "WITHDRAWN" }
            """
            <section class="samsung-viewer" data-samsung-viewer="${head.accountId.esc()}" style="margin-bottom:24px">
              <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:8px"><h2 style="margin:0">${head.requesterName.esc()}</h2>$counts</div>
              ${live.joinToString("") { samsungRow(it) }}
              ${if (withdrawn.isEmpty()) "" else """<details id="samsung-withdrawn-${head.accountId.esc()}"><summary class="tiny muted" style="cursor:pointer;margin-bottom:8px">${samsungText("withdrawn_group", mapOf("n" to withdrawn.size.toString())).esc()}</summary>${withdrawn.joinToString("") { samsungRow(it) }}</details>"""}
            </section>
            """.trimIndent()
        }
}

private fun samsungRow(row: SamsungTvAdminRequest): String = """
  <div class="card" style="padding:16px;margin-bottom:12px" data-samsung-row="${row.id}">
    <div style="display:flex;gap:12px;align-items:center;flex-wrap:wrap"><strong>${row.name.esc()}</strong><span class="badge info">${samsungText("state_${row.state.lowercase()}").esc()}</span><span class="spacer"></span><span class="tiny muted">${samsungDate(row.createdAt).esc()}</span></div>
    <div class="tiny muted">${samsungText("requester").esc()}: ${row.requesterName.esc()} · ${samsungText("model").esc()}: ${row.model.esc()} · Tizen ${row.tizenVersion.esc()}</div>
    <div style="display:flex;gap:8px;align-items:center;margin-top:8px"><code>${row.duid.esc()}</code><button class="btn sm ghost" data-samsung-copy="${row.duid.esc()}" aria-label="${samsungText("copy_duid").esc()}">${samsungText("copy").esc()}</button></div>
    ${row.decidedAt?.let { """<div class="tiny muted">${samsungText("decided").esc()}: ${samsungDate(it).esc()} · ${row.decidedBy?.esc() ?: "—"}</div>""" } ?: ""}
    ${row.decisionNote?.let { """<div class="tiny muted">${samsungText("note").esc()}: ${it.esc()}</div>""" } ?: ""}
    ${row.readyFromRelease?.let { """<div class="tiny muted">${samsungText("ready_release").esc()}: ${it.esc()}</div>""" } ?: ""}
    ${if (row.state == "REQUESTED") """<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:12px"><input id="samsung-note-${row.id}" type="text" maxlength="500" placeholder="${samsungText("note_optional").esc()}" aria-label="${samsungText("note_optional").esc()}"><button class="btn sm" data-samsung-id="${row.id}" data-samsung-decision="APPROVE">${samsungText("approve").esc()}</button><button class="btn sm ghost" data-samsung-id="${row.id}" data-samsung-decision="REJECT">${samsungText("reject").esc()}</button></div>""" else ""}
  </div>
""".trimIndent()
