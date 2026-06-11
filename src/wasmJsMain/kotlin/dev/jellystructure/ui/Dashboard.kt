package dev.jellystructure.ui

import org.w3c.dom.Element

fun renderDashboard(container: Element) {
    container.innerHTML = """
        <div class="pagebar">
          <h1>Dashboard</h1>
          <span class="spacer"></span>
          <a class="btn sm ghost" href="#/library">⌕ Browse library</a>
          <span class="btn primary">▶ Scan library</span>
        </div>
        <p class="page-sub">Single source of truth for your media metadata. Jellyfin just reads what Jellystructure writes — you never touch its built-in scraper.</p>

        <div class="statgrid">
          <div class="stat"><div class="k">Movies</div><div class="v">—</div></div>
          <div class="stat"><div class="k">TV episodes</div><div class="v">—</div></div>
          <div class="stat alert"><div class="k">Tracks needing attention</div><div class="v">—</div></div>
          <div class="stat"><div class="k">NFO coverage</div><div class="v">—</div></div>
        </div>

        <div class="card" style="margin-top:18px">
          <p class="muted" style="margin:0">Scan your library to populate this dashboard. Phase 2 will connect live data here.</p>
        </div>
    """.trimIndent()
}
