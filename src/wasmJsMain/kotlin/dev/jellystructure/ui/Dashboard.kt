package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.MediaApi
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

fun renderDashboard(container: Element, scope: CoroutineScope) {
    container.innerHTML = """
        <div class="pagebar">
          <h1>Dashboard</h1>
          <span class="spacer"></span>
          <button id="dash-browse" class="btn sm ghost">Browse library</button>
          <button id="dash-scan" class="btn primary">▶ Scan library</button>
        </div>
        <p class="page-sub">Single source of truth for your media metadata. Jellyfin just reads what Jellystructure writes — you never touch its built-in scraper.</p>

        <div class="statgrid">
          <div class="stat"><div class="k">Movies</div><div class="v" id="stat-movies">—</div></div>
          <div class="stat"><div class="k">TV episodes</div><div class="v">—</div></div>
          <div class="stat alert"><div class="k">Tracks needing attention</div><div class="v" id="stat-issues">—</div></div>
          <div class="stat"><div class="k">NFO coverage</div><div class="v">—</div></div>
        </div>

        <div id="dash-scan-status" style="display:none;margin-top:14px;"></div>
    """.trimIndent()

    scope.launch { loadDashboardStats() }

    document.getElementById("dash-browse")?.addEventListener("click") {
        App.navigate("/library")
    }
    document.getElementById("dash-scan")?.addEventListener("click") {
        scope.launch { dashboardScan(scope) }
    }
}

private suspend fun loadDashboardStats() {
    val stats = MediaApi.stats() ?: return
    (document.getElementById("stat-movies") as? HTMLElement)?.textContent = stats.movies.toString()
    (document.getElementById("stat-issues") as? HTMLElement)?.textContent = stats.issues.toString()
}

private suspend fun dashboardScan(scope: CoroutineScope) {
    val btn = document.getElementById("dash-scan") as? HTMLButtonElement ?: return
    val status = document.getElementById("dash-scan-status") as? HTMLElement
    btn.disabled = true
    btn.textContent = "Scanning…"
    status?.style?.display = "block"
    status?.innerHTML = """<span class="badge">Scan in progress…</span>"""
    val count = MediaApi.scan()
    loadDashboardStats()
    btn.disabled = false
    btn.textContent = "▶ Scan library"
    status?.innerHTML = """<span class="badge ok">Scan complete — $count item${if (count != 1) "s" else ""} found.</span>"""
}
