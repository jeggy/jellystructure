package dev.jellystructure.ui

import dev.jellystructure.api.BazarrApi
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

// Phase 157 — global Subtitles overview (FR-BZ1-2). Reached only from the Dashboard's summary card,
// deliberately absent from Shell.kt's NAV — see the phase-157 addendum on why that's novel real-app
// plumbing, not an existing pattern.
private var subtitlesWantedStart = 0
private const val SUBTITLES_PAGE_SIZE = 50

fun renderSubtitles(container: Element, scope: CoroutineScope) {
    subtitlesWantedStart = 0
    container.innerHTML = """
        <div class="pagebar">
          <h1>Subtitles</h1>
          <span class="badge info" style="margin-left:8px;">Bazarr</span>
          <span class="spacer"></span>
        </div>
        <p class="page-sub">Live view of your Bazarr instance — search, download and track subtitles without leaving Jellystructure. Nothing here is stored; every number is fetched live.</p>

        <div id="sub-disconnected" style="display:none;" class="card">
          <span class="muted">Bazarr isn't connected. Configure it in <a href="#/settings?tab=downloads">Settings → Download tools</a>.</span>
        </div>

        <div id="sub-content" style="display:none;">
          <div class="statgrid" id="sub-stats" style="margin-bottom:18px;">
            <div class="stat"><div class="k">Wanted movies</div><div class="v" id="stat-wanted-movies">—</div></div>
            <div class="stat"><div class="k">Wanted episodes</div><div class="v" id="stat-wanted-episodes">—</div></div>
            <div class="stat"><div class="k">Providers healthy</div><div class="v" id="stat-providers">—</div></div>
            <div class="stat"><div class="k">Actions</div><div class="v" style="font-size:1rem;padding-top:6px;">
              <button class="btn sm ghost" id="sub-search-all">Search all wanted</button>
              <button class="btn sm ghost" id="sub-scan" style="margin-left:6px;">Run full scan</button>
            </div></div>
          </div>

          <div style="display:flex;gap:18px;align-items:flex-start;flex-wrap:wrap;">
            <div class="card" style="flex:2;min-width:420px;">
              <div class="row center"><h3 style="margin:0;font-size:1.02rem;">Wanted subtitles</h3><span class="tiny muted" id="wanted-total" style="margin-left:8px;"></span></div>
              <div id="wanted-list" style="margin-top:10px;"><span class="muted tiny">Loading…</span></div>
              <div class="row center" style="margin-top:10px;gap:8px;">
                <button class="btn sm ghost" id="wanted-more">Load 50 more</button>
              </div>
            </div>

            <div style="flex:1;min-width:260px;display:flex;flex-direction:column;gap:14px;">
              <div class="card">
                <h4 style="margin:0 0 8px;font-size:.92rem;">Providers</h4>
                <div id="sub-providers"><span class="muted tiny">Loading…</span></div>
              </div>
              <div class="card">
                <h4 style="margin:0 0 8px;font-size:.92rem;">Language profiles</h4>
                <div id="sub-profiles"><span class="muted tiny">Loading…</span></div>
                <div class="tiny muted" style="margin-top:8px;">Read-only mirror — edit in Bazarr.</div>
              </div>
            </div>
          </div>

          <div class="card" style="margin-top:18px;">
            <h3 style="margin:0 0 10px;font-size:1.02rem;">Recent history</h3>
            <div id="sub-history"><span class="muted tiny">Loading…</span></div>
          </div>
        </div>
    """.trimIndent()

    scope.launch { loadSubtitlesPage(scope) }

    document.getElementById("sub-search-all")?.addEventListener("click") { _ ->
        scope.launch { BazarrApi.searchAllWanted(); loadSubtitlesPage(scope) }
    }
    document.getElementById("sub-scan")?.addEventListener("click") { _ ->
        scope.launch { BazarrApi.runFullScan() }
    }
    document.getElementById("wanted-more")?.addEventListener("click") { _ ->
        subtitlesWantedStart += SUBTITLES_PAGE_SIZE
        scope.launch { loadWantedList(append = true) }
    }
}

private suspend fun loadSubtitlesPage(scope: CoroutineScope) {
    val overview = BazarrApi.overview()
    if (overview == null || !overview.connected) {
        (document.getElementById("sub-disconnected") as? HTMLElement)?.style?.display = "block"
        (document.getElementById("sub-content") as? HTMLElement)?.style?.display = "none"
        return
    }
    (document.getElementById("sub-disconnected") as? HTMLElement)?.style?.display = "none"
    (document.getElementById("sub-content") as? HTMLElement)?.style?.display = "block"
    document.getElementById("stat-wanted-movies")?.textContent = overview.wantedMovies.toString()
    document.getElementById("stat-wanted-episodes")?.textContent = overview.wantedEpisodes.toString()
    document.getElementById("stat-providers")?.textContent = "${overview.providersHealthy}/${overview.providersTotal}"

    loadWantedList(append = false)

    val providers = BazarrApi.providers()
    document.getElementById("sub-providers")?.innerHTML = if (providers.isEmpty()) """<span class="muted tiny">No providers configured.</span>"""
        else providers.joinToString("") { p ->
            val ok = p.status.equals("Good", ignoreCase = true)
            """<div class="row center" style="padding:3px 0;"><span class="dot ${if (ok) "ok" else "warn"}"></span><span style="margin-left:6px;flex:1;">${p.name}</span><span class="tiny muted">${if (ok) "OK" else p.status}</span></div>"""
        }

    val profiles = BazarrApi.profiles()
    document.getElementById("sub-profiles")?.innerHTML = if (profiles.isEmpty()) """<span class="muted tiny">No language profiles.</span>"""
        else profiles.joinToString("") { pr -> """<div class="chip" style="margin:2px 4px 2px 0;">${pr.name} <span class="tiny muted">(${pr.items.size})</span></div>""" }

    val history = BazarrApi.history(0, 30)
    document.getElementById("sub-history")?.innerHTML = if (history.isEmpty()) """<span class="muted tiny">No recent activity.</span>"""
        else history.joinToString("") { ev ->
            """<div style="display:flex;gap:10px;padding:5px 0;border-bottom:1px solid var(--border);">
                 <span class="muted tiny" style="width:150px;flex-shrink:0;">${(ev.timestamp ?: "").let { it.take(16) }}</span>
                 <span style="flex:1;">${(ev.language ?: "").uppercase()} ${(ev.provider ?: "")}</span>
               </div>"""
        }
}

private suspend fun loadWantedList(append: Boolean) {
    val page = BazarrApi.wanted(subtitlesWantedStart, SUBTITLES_PAGE_SIZE) ?: return
    document.getElementById("wanted-total")?.textContent = "${page.total} total"
    val rowsHtml = page.items.joinToString("") { row ->
        val missing = row.missing.joinToString(", ") { it.code2.uppercase() }
        val sub = row.subtitle?.let { " · $it" } ?: ""
        """<div style="display:flex;gap:10px;padding:6px 0;border-bottom:1px solid var(--border);align-items:center;">
             <span class="chip" style="font-size:.66rem;">${row.kind}</span>
             <span style="flex:1;min-width:0;">${row.title}$sub<span class="tiny muted" style="display:block;">wanted: $missing</span></span>
           </div>"""
    }
    val listEl = document.getElementById("wanted-list") ?: return
    if (append) listEl.innerHTML = (listEl.innerHTML) + rowsHtml else listEl.innerHTML = rowsHtml.ifBlank { """<span class="muted tiny">Nothing wanted — fully subtitled.</span>""" }
}
