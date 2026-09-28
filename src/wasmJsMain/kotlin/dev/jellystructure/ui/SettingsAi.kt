package dev.jellystructure.ui

import dev.jellystructure.api.AiApi
import dev.jellystructure.api.AiConfig
import dev.jellystructure.api.AiJobConfig
import dev.jellystructure.api.AiJobStatus
import dev.jellystructure.api.AiModel
import dev.jellystructure.api.AiStatus
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

/*
 * Phase 270 — Settings → AI. One provider (Anthropic) drawn as a choice; a masked key with *Test key*; AI
 * on/off for the installation; one card per job with its model, effort, monthly limit, spend this month,
 * last run and an estimate of a month. The key is never read back (GET /api/config masks it), so the
 * *…abcd* hint, the spend, the runs and the estimates come from GET /api/ai/status (render-never-compute:
 * the page shows the server's numbers and does no arithmetic of its own).
 */

private data class AiJobUi(val key: String, val title: String, val what: String)

private val AI_JOBS = listOf(
    AiJobUi("rerank", "Recommendations re-rank",
        "After the weekly recommendations build, the model reads each viewer's top 100 and keeps the 50 it would pick, in order, with a reason you can see on Users &amp; devices. It never adds a title of its own. If anything goes wrong, the viewer keeps the standard list."),
    AiJobUi("themes", "Theme tags",
        "Once per title, for titles TMDB has no keywords for (often local and regional television): the model reads the synopsis and writes five to eight themes, used by the recommendations like keywords. Again only when the synopsis changes."),
    // Phase 274 (FR-274-5, Q7)
    AiJobUi("clusters", "Suggestion clusters",
        "Once per suggestions build (weekly, with Seerr connected): the model groups the films the household doesn't have into three to eight kinds it actually watches, named for this house. It sees genre and keyword ids only — no viewer, no title, no synopsis — and each group's share of the 20 shown is counted by the server, never by the model. If anything goes wrong, the list keeps the six genre groups and the page says so."),
)

private var aiEnabled = false
private val aiJobEnabled = mutableMapOf("rerank" to false, "themes" to false, "clusters" to false)
private var aiLoaded: AiConfig = AiConfig()
private var aiStatus: AiStatus? = null

internal fun aiSectionHtml(): String = """
            <div class="card set-section" id="sect-ai" data-tab="ai">
              <h3 style="font-size:1rem;margin:0 0 6px">AI</h3>
              <p class="hint" style="margin:0 0 14px">Optional. A language model can improve each viewer's Recommended list and tag titles TMDB knows little about. Off by default; while it is off, nothing is sent anywhere. Each job has its own monthly limit, and a run that could pass it is not sent.</p>
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
                <span style="font-size:.9rem;font-weight:600">Use AI</span>
                <span id="ai-enabled-toggle" class="toggle" style="cursor:pointer"></span>
              </div>
              <div class="field">
                <label>Provider</label>
                <select id="ai-provider" class="input" style="width:220px"><option value="anthropic">Anthropic</option></select>
              </div>
              <div class="field">
                <label>API key <span id="ai-key-hint" class="tiny muted" style="margin-left:6px"></span></label>
                <div style="display:flex;gap:8px;align-items:center">
                  <input id="ai-key" class="input" type="password" placeholder="(unchanged)" autocomplete="off" style="flex:1;min-width:0">
                  <button id="ai-key-test" type="button" class="btn sm ghost" style="flex:none">Test key</button>
                </div>
                <span id="ai-key-result" class="tiny" style="display:block;margin-top:4px"></span>
                <span class="hint">From console.anthropic.com → API keys. Stored in <code>config.toml</code>; never sent to a Ravilo app, never shown again once saved.</span>
              </div>
              ${AI_JOBS.joinToString("") { aiJobCardHtml(it) }}
              <details style="margin-top:6px;font-size:.82rem;color:var(--ink-soft)">
                <summary style="cursor:pointer;user-select:none">What is sent to Anthropic</summary>
                <p style="margin:8px 0 0;line-height:1.55"><b>Re-rank:</b> the titles, years, genres, keywords and a shortened synopsis of each viewer's 100 candidates, and the titles and genres they watched recently. <b>Theme tags:</b> a title's name, year, genres, keywords and synopsis. Never a name, a user id, a device name, an IP address or anything from Jellyfin's user records: a viewer is not named in a request. A kids profile's history is sent under the same rules as anyone's.</p>
              </details>
              <p class="hint" id="ai-prices" style="margin:10px 0 0"></p>
            </div>
"""

private fun aiJobCardHtml(j: AiJobUi): String = """
              <div class="box" style="border:1px solid var(--line);border-radius:8px;padding:14px 16px;margin:14px 0 0">
                <div style="display:flex;align-items:center;justify-content:space-between">
                  <span style="font-size:.92rem;font-weight:600">${j.title}</span>
                  <span id="ai-${j.key}-toggle" class="toggle" style="cursor:pointer;flex-shrink:0;margin-left:12px"></span>
                </div>
                <p class="hint" style="margin:6px 0 10px">${j.what}</p>
                <div style="display:flex;gap:14px;flex-wrap:wrap;align-items:flex-end">
                  <div class="field" style="margin:0"><label>Model</label><select id="ai-${j.key}-model" class="input" style="width:340px;max-width:100%"></select></div>
                  <div class="field" style="margin:0" id="ai-${j.key}-effort-field"><label>Effort</label>
                    <select id="ai-${j.key}-effort" class="input" style="width:120px"><option value="low">Low</option><option value="medium">Medium</option><option value="high">High</option></select></div>
                  <div class="field" style="margin:0"><label>Monthly limit (USD)</label><input id="ai-${j.key}-limit" class="input" type="number" min="0" step="0.5" style="width:110px"></div>
                </div>
                <div class="tiny muted" id="ai-${j.key}-spent" style="margin-top:10px"></div>
                <div class="tiny muted" id="ai-${j.key}-last" style="margin-top:2px"></div>
                <div class="tiny muted" id="ai-${j.key}-estimate" style="margin-top:2px"></div>
              </div>
"""

private fun input(id: String) = document.getElementById(id) as? HTMLInputElement
private fun select(id: String) = document.getElementById(id) as? HTMLSelectElement

private fun toggle(id: String, on: Boolean) {
    (document.getElementById(id) as? HTMLElement)?.className = if (on) "toggle on" else "toggle"
}

private fun usd(micro: Long): String {
    val cents = (micro + 5_000) / 10_000
    return "$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0')
}

private fun price(v: Double): String = if (v == v.toLong().toDouble()) "$" + v.toLong() else "$" + v

/** Fills the tab from the saved config (the key field stays blank: a saved key is only ever the sentinel). */
internal fun populateAi(c: AiConfig) {
    aiLoaded = c
    aiEnabled = c.enabled
    toggle("ai-enabled-toggle", aiEnabled)
    select("ai-provider")?.value = c.provider
    input("ai-key")?.value = ""
    for ((key, job) in listOf("rerank" to c.rerank, "themes" to c.themes, "clusters" to c.clusters)) {
        aiJobEnabled[key] = job.enabled
        toggle("ai-$key-toggle", job.enabled)
        select("ai-$key-effort")?.value = job.effort
        input("ai-$key-limit")?.value = job.monthlyLimitUsd.toString()
        (document.getElementById("ai-$key-model") as? HTMLSelectElement)?.setAttribute("data-want", job.model)
    }
    renderAiStatus()
}

/** What Save sends: the tab's fields; a blank key field keeps the saved key (`##KEEP##`). */
internal fun readAi(): AiConfig {
    fun job(key: String, loaded: AiJobConfig) = AiJobConfig(
        enabled = aiJobEnabled[key] ?: false,
        model = select("ai-$key-model")?.value?.takeIf { it.isNotBlank() } ?: loaded.model,
        effort = select("ai-$key-effort")?.value?.takeIf { it.isNotBlank() } ?: loaded.effort,
        monthlyLimitUsd = input("ai-$key-limit")?.value?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: loaded.monthlyLimitUsd,
    )
    val typed = input("ai-key")?.value?.trim().orEmpty()
    return AiConfig(
        enabled = aiEnabled,
        provider = select("ai-provider")?.value ?: aiLoaded.provider,
        apiKey = typed.ifBlank { if (aiLoaded.apiKey.isBlank()) "" else "##KEEP##" },
        rerank = job("rerank", aiLoaded.rerank),
        themes = job("themes", aiLoaded.themes),
        clusters = job("clusters", aiLoaded.clusters),
    )
}

internal fun wireAi(scope: CoroutineScope, onChange: () -> Unit) {
    document.getElementById("ai-enabled-toggle")?.addEventListener("click") {
        aiEnabled = !aiEnabled; toggle("ai-enabled-toggle", aiEnabled); onChange()
    }
    for (key in listOf("rerank", "themes", "clusters")) {
        document.getElementById("ai-$key-toggle")?.addEventListener("click") {
            aiJobEnabled[key] = !(aiJobEnabled[key] ?: false); toggle("ai-$key-toggle", aiJobEnabled[key] ?: false); onChange()
        }
        select("ai-$key-model")?.addEventListener("change") { renderAiJob(key); onChange() }
        select("ai-$key-effort")?.addEventListener("change") { onChange() }
        input("ai-$key-limit")?.addEventListener("input") { renderAiJob(key); onChange() }
    }
    document.getElementById("ai-key-test")?.addEventListener("click") {
        val out = document.getElementById("ai-key-result") as? HTMLElement
        out?.textContent = "Testing…"; out?.style?.color = "var(--ink-soft)"
        scope.launch {
            val r = AiApi.testKey(input("ai-key")?.value?.trim().orEmpty())
            out?.textContent = r?.detail ?: "Couldn't reach the server"
            out?.style?.color = when (r?.result) { "valid" -> "var(--ok)"; "invalid" -> "var(--bad)"; else -> "var(--warn)" }
        }
    }
    scope.launch { aiStatus = AiApi.status(); renderAiStatus() }
}

private fun renderAiStatus() {
    val s = aiStatus
    (document.getElementById("ai-key-hint") as? HTMLElement)?.textContent = s?.keyHint?.let { "saved: $it" } ?: ""
    (document.getElementById("ai-prices") as? HTMLElement)?.textContent =
        if (s == null) "" else "Prices as of ${s.pricesAsOf}, at batch rates (half the standard price): every job runs in the background through the Message Batches API."
    for (key in listOf("rerank", "themes", "clusters")) {
        val sel = select("ai-$key-model") ?: continue
        if (s != null && sel.options.length == 0) {
            val want = sel.getAttribute("data-want") ?: s.models.firstOrNull()?.id
            sel.innerHTML = s.models.joinToString("") { m ->
                """<option value="${m.id}"${if (m.id == want) " selected" else ""}>${m.label} · ${price(m.inputPerMTok)} / ${price(m.outputPerMTok)} per M tokens</option>"""
            }
        }
        renderAiJob(key)
    }
}

private fun renderAiJob(key: String) {
    val s = aiStatus ?: return
    val job: AiJobStatus = when (key) { "rerank" -> s.rerank; "clusters" -> s.clusters; else -> s.themes }
    val modelId = select("ai-$key-model")?.value ?: return
    val model: AiModel? = s.models.firstOrNull { it.id == modelId }
    // FR-270-3 — effort only for a model that takes it (Haiku 4.5 rejects the parameter).
    (document.getElementById("ai-$key-effort-field") as? HTMLElement)?.style?.display = if (model?.effort == false) "none" else ""
    val limit = input("ai-$key-limit")?.value?.toDoubleOrNull()
    (document.getElementById("ai-$key-spent") as? HTMLElement)?.textContent =
        "Spent this month: ${usd(job.spentThisMonthMicroUsd)}" + (limit?.let { " of ${usd((it * 1_000_000).toLong())}" } ?: "") +
            (if (job.pending) " · a batch is out, waiting for Anthropic" else "") +
            // Phase 272 — what waits (and why, if the limit held it back); the Activity card has the rest.
            (if (job.waiting > 0) " · ${job.waiting} waiting (Activity ▸ Jobs & workers)" else "") +
            (job.heldBack?.let { " · $it" } ?: "")
    // Phase 272 (FR-272-17) — the time is the browser's own; the server sends the instant, not a clock string.
    (document.getElementById("ai-$key-last") as? HTMLElement)?.textContent =
        "Last run: " + (job.lastRunAt?.let { dev.jellystructure.formatClock(it.toString()) + " · " } ?: "") + (job.lastRun ?: "never")
    (document.getElementById("ai-$key-estimate") as? HTMLElement)?.textContent =
        job.estimateMicroUsd[modelId]?.let { "A month at these settings: about ${usd(it)} (${job.estimateBasis})" } ?: ""
}

/** The TOML preview's `[ai]` block, the key never echoed. */
internal fun aiTomlPreview(c: AiConfig): String = buildString {
    appendLine()
    appendLine("[ai]")
    appendLine("enabled = ${c.enabled}")
    appendLine("provider = \"${c.provider}\"")
    appendLine("api_key = \"${if (c.apiKey.isBlank()) "" else "••••"}\"")
    for ((name, j) in listOf("rerank" to c.rerank, "themes" to c.themes, "clusters" to c.clusters)) {
        appendLine()
        appendLine("[ai.$name]")
        appendLine("enabled = ${j.enabled}")
        appendLine("model = \"${j.model}\"")
        appendLine("effort = \"${j.effort}\"")
        appendLine("monthly_limit_usd = ${j.monthlyLimitUsd}")
    }
}
