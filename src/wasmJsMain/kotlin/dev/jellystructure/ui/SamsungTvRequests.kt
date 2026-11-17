@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.api.httpClient
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

/** DEB-4842 (FR-5) — all rows and transitions render from GET/POST responses. */
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
            <div class="samsung-rows">${if (rows.isEmpty()) """<div class="card" style="padding:20px">${samsungText("empty").esc()}</div>""" else rows.joinToString("") { samsungRow(it) }}</div>
        """.trimIndent()
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
    scope.launch { load() }
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
