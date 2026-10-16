package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.Router
import dev.jellystructure.api.AiPageApi
import dev.jellystructure.api.ServerLink
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/**
 * DEB-1253 (FR-2..FR-7) — the admin's AI page: its own header, a tab strip (`.tk-tabs`) in the server's order, and one
 * panel per tab. Suggestions is the only tab built today; Support and Fetchers each add a tab here later. Styles:
 * `design/app/app.css` (`.tk-tabs`, `.ai-offl`) and `design/app/suggestions.css` (`.sg-tb`).
 */
private val AI_TAB_LABELS = mapOf("support" to "Support", "fetchers" to "Fetchers", "suggestions" to "Suggestions")

private var aiTabs: List<String> = emptyList()
private var aiLost = false
private var aiProbe: Job? = null

/** `#/suggestions`, and `#/suggestions?view=dismissed`: the live home is asked (FR-4) before anything is drawn. */
fun renderSuggestionsRoute(container: Element, scope: CoroutineScope, query: Map<String, String>) {
    container.innerHTML = """<span class="muted tiny">Loading…</span>"""
    val route = Router.current()
    scope.launch {
        val tabs = AiPageApi.tabs()
        if (Router.current() != route) return@launch
        if (tabs != null && "suggestions" in tabs) App.redirect("/ai?tab=suggestions" + (if (query["view"] == "dismissed") "&view=dismissed" else ""))
        else renderSuggestions(container, scope, query)
    }
}

fun renderAiPage(container: Element, scope: CoroutineScope, query: Map<String, String>) {
    leaveAiPage()
    container.innerHTML = """<span class="muted tiny">Loading…</span>"""
    val route = Router.current()
    scope.launch {
        val tabs = (AiPageApi.ask() as? AiPageApi.Answer.Tabs)?.tabs
        if (Router.current() != route) return@launch
        if (tabs == null) {
            // FR-4 — no AI page: the Suggestions address lands on the page it is without a key, anything else on the Dashboard.
            App.redirect(if (query["tab"] == "suggestions") "/suggestions" + (if (query["view"] == "dismissed") "?view=dismissed" else "") else "/dashboard")
            return@launch
        }
        mountAiPage(container, scope, tabs, query)
    }
}

/** Called on every route that is not the AI page, and before it is drawn again: the strip, the probe and the listener go. */
fun leaveAiPage() {
    aiProbe?.cancel(); aiProbe = null
    aiLost = false
    ServerLink.onNoResponse = null
    document.getElementById("page-content")?.classList?.remove("ws-lost")
}

private fun mountAiPage(container: Element, scope: CoroutineScope, tabs: List<String>, query: Map<String, String>) {
    aiTabs = tabs
    // FR-5.3 — an absent or unknown tab opens the first present one, and the URL says so.
    val tab = query["tab"]?.takeIf { it in tabs } ?: tabs.first()
    if (query["tab"] != tab) Router.updateQuery(mapOf("tab" to tab), replace = true)
    container.innerHTML = """
        <div id="ai-root">
        <div class="pagebar">
          <h1>AI</h1>
          <span class="spacer"></span>
          <a class="btn sm ghost" href="#/settings?tab=ai">Key &amp; model → Settings</a>
        </div>
        <div class="ai-offl" id="ai-offl" role="status"><span class="dot"></span><span><b>Connection to this server lost · reconnecting.</b> What you see is what it last sent. Nothing is sent until it’s back — Download, No thanks and Rebuild wait.</span></div>
        <div class="tk-tabs" id="ai-tabs">${tabs.joinToString("") { """<span data-tab="$it">${AI_TAB_LABELS[it] ?: it}</span>""" }}</div>
        ${tabs.joinToString("") { """<div id="ai-${it}" data-ai-panel="$it" style="display:none"></div>""" }}
        </div>
    """.trimIndent()
    document.getElementById("ai-tabs")?.addEventListener("click", { ev ->
        val t = (ev.target as? Element)?.closest("[data-tab]")?.getAttribute("data-tab") ?: return@addEventListener
        if (t == Router.currentQuery()["tab"]) return@addEventListener
        // FR-5.2 — a tab switch is a replaceState: no hashchange, no history entry, only the panel toggles.
        Router.updateQuery(mapOf("tab" to t, "view" to null), replace = true)
        showAiTab(scope, t)
    })
    ServerLink.onNoResponse = { aiServerLost(scope) }
    showAiTab(scope, tab)
}

private fun showAiTab(scope: CoroutineScope, tab: String) {
    for (t in aiTabs) {
        document.querySelector("#ai-tabs [data-tab=\"$t\"]")?.classList?.toggle("on", t == tab)
        (document.getElementById("ai-$t") as? HTMLElement)?.style?.display = if (t == tab) "" else "none"
    }
    val panel = document.getElementById("ai-$tab") ?: return
    when (tab) {
        "suggestions" -> renderSuggestions(panel, scope, Router.currentQuery(), inTab = true)
    }
}

/** FR-7 — a request got no HTTP response: the strip shows, and the page asks /api/ai/page every 5 s until it is answered. */
private fun aiServerLost(scope: CoroutineScope) {
    if (aiLost) return
    aiLost = true
    document.getElementById("page-content")?.classList?.add("ws-lost")
    aiProbe = scope.launch {
        while (true) {
            delay(5_000)
            if (Router.currentPath() != "/ai") return@launch
            val a = AiPageApi.ask()
            if (a is AiPageApi.Answer.NoResponse) continue
            aiLost = false
            document.getElementById("page-content")?.classList?.remove("ws-lost")
            val query = Router.currentQuery()
            if (a is AiPageApi.Answer.Tabs) {
                aiTabs = a.tabs
                // The first answer reloads the open tab.
                showAiTab(scope, query["tab"]?.takeIf { it in a.tabs } ?: a.tabs.first())
            } else App.redirect(if (query["tab"] == "suggestions") "/suggestions" + (if (query["view"] == "dismissed") "?view=dismissed" else "") else "/dashboard")
            return@launch
        }
    }
}
