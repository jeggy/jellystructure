package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.MediaItem
import dev.jellystructure.api.MediaKind
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

private const val TMDB_IMG = "https://image.tmdb.org/t/p/w342"

private var libPage = 1
private var libKind: MediaKind? = null
private var libFilter: String? = null

fun renderLibrary(container: Element, scope: CoroutineScope) {
    libPage = 1; libKind = null; libFilter = null

    container.innerHTML = """
        <div class="pagebar">
          <h1>Library</h1>
          <span class="spacer"></span>
          <button id="scan-btn" class="btn primary">▶ Scan library</button>
        </div>
        <p class="page-sub">Everything Jellystructure manages. A red badge means untagged tracks — click any title to open its detail page.</p>

        <div id="scan-banner" style="display:none;margin-bottom:14px"></div>

        <div class="row center" style="margin-bottom:16px;gap:10px;flex-wrap:wrap;">
          <span class="muted tiny">filter:</span>
          <button id="f-all" class="chip active-chip">All</button>
          <button id="f-attention" class="chip">Needs attention</button>
          <button id="f-artwork" class="chip">Missing artwork</button>
          <span class="spacer" style="flex:1"></span>
          <div class="seg">
            <button id="k-all" class="on">All</button>
            <button id="k-movie">Movies</button>
            <button id="k-tv">TV</button>
          </div>
          <span class="muted tiny" id="lib-total"></span>
        </div>

        <div id="poster-grid" class="poster-grid"></div>
        <div class="row center" style="margin-top:18px;" id="lib-pager"></div>
    """.trimIndent()

    attachLibraryListeners(scope)

    scope.launch {
        val status = MediaApi.scanStatus()
        if (status?.running == true) {
            setScanRunning(true)
            loadLibraryPage(scope)
            pollScanUntilDone(scope)
        } else {
            loadLibraryPage(scope)
        }
    }
}

private fun attachLibraryListeners(scope: CoroutineScope) {
    fun reload() { libPage = 1; scope.launch { loadLibraryPage(scope) } }

    document.getElementById("scan-btn")?.addEventListener("click") {
        scope.launch { triggerScan(scope) }
    }

    mapOf(
        "f-all" to { libFilter = null },
        "f-attention" to { libFilter = "attention" },
        "f-artwork" to { libFilter = "missing_artwork" },
    ).forEach { (id, setter) ->
        document.getElementById(id)?.addEventListener("click") { setter(); reload() }
    }

    mapOf(
        "k-all" to { libKind = null },
        "k-movie" to { libKind = MediaKind.MOVIE },
        "k-tv" to { libKind = MediaKind.TV_SHOW },
    ).forEach { (id, setter) ->
        document.getElementById(id)?.addEventListener("click") { setter(); reload() }
    }
}

private suspend fun triggerScan(scope: CoroutineScope) {
    val btn = document.getElementById("scan-btn") as? HTMLButtonElement ?: return
    if (btn.disabled) return

    val started = MediaApi.startScan()
    if (!started) {
        val banner = document.getElementById("scan-banner") as? HTMLElement ?: return
        banner.style.display = "block"
        banner.innerHTML = """<span class="badge bad">Scan is already running or failed to start.</span>"""
        return
    }

    setScanRunning(true)
    pollScanUntilDone(scope)
}

private suspend fun pollScanUntilDone(scope: CoroutineScope) {
    while (true) {
        delay(2000)
        val status = MediaApi.scanStatus() ?: break
        if (!status.running) {
            setScanRunning(false)
            val count = status.lastCount
            val banner = document.getElementById("scan-banner") as? HTMLElement
            if (banner != null) {
                banner.style.display = "block"
                banner.innerHTML = """<span class="badge ok">Scan complete${if (count != null) " — $count item${if (count != 1) "s" else ""} found" else ""}.</span>"""
            }
            loadLibraryPage(scope)
            break
        }
    }
}

private fun setScanRunning(running: Boolean) {
    val btn = document.getElementById("scan-btn") as? HTMLButtonElement
    val banner = document.getElementById("scan-banner") as? HTMLElement
    if (running) {
        btn?.disabled = true
        btn?.textContent = "Scanning…"
        banner?.style?.display = "block"
        banner?.innerHTML = """<span class="badge">Scan in progress — results will appear when complete…</span>"""
    } else {
        btn?.disabled = false
        btn?.textContent = "▶ Scan library"
    }
}

private suspend fun loadLibraryPage(scope: CoroutineScope) {
    val grid = document.getElementById("poster-grid") ?: return
    grid.innerHTML = """<span class="muted" style="padding:24px;display:block;">Loading…</span>"""

    val page = MediaApi.list(libKind, libFilter, libPage, 20)
    if (page == null) {
        grid.innerHTML = """<span class="muted" style="padding:24px;display:block;">Failed to load library.</span>"""
        return
    }

    document.getElementById("lib-total")?.textContent = "${page.total} item${if (page.total != 1) "s" else ""}"

    grid.innerHTML = if (page.items.isEmpty()) {
        """<span class="muted" style="padding:24px;display:block;">No items found. Run a scan to populate the library.</span>"""
    } else {
        page.items.joinToString("") { posterCardHtml(it) }
    }

    grid.querySelectorAll(".poster[data-id]").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            val id = el.getAttribute("data-id") ?: continue
            el.addEventListener("click") { App.navigate("/media/$id") }
        }
    }

    renderLibraryPager(page.total, page.page, page.pageSize, scope)
}

private fun posterCardHtml(item: MediaItem): String {
    val badge = when {
        item.issueCount > 0 ->
            """<span class="badge bad" style="font-size:.62rem;">${item.issueCount} issue${if (item.issueCount != 1) "s" else ""}</span>"""
        else ->
            """<span class="badge ok" style="font-size:.62rem;">ok</span>"""
    }
    val imgContent = if (item.posterPath != null) {
        """<img src="$TMDB_IMG${item.posterPath}" alt="${item.title.esc()}"
             style="width:100%;height:100%;object-fit:cover;border-radius:4px 4px 0 0;">"""
    } else {
        """<div class="x"></div><span>${item.title.esc()}</span>"""
    }
    return """
        <div class="poster" data-id="${item.id}" style="cursor:pointer;">
          <div class="imgslot">$imgContent</div>
          <div class="ttl">${item.title.esc()}</div>
          <div class="yr">${item.year ?: "—"} · $badge</div>
        </div>"""
}

private fun renderLibraryPager(total: Int, page: Int, pageSize: Int, scope: CoroutineScope) {
    val pager = document.getElementById("lib-pager") ?: return
    val totalPages = if (pageSize > 0) (total + pageSize - 1) / pageSize else 1
    if (totalPages <= 1) { pager.innerHTML = ""; return }
    pager.innerHTML = """
        <span class="muted tiny">Page $page of $totalPages</span>
        <span class="spacer" style="flex:1"></span>
        ${if (page > 1) """<button id="pg-prev" class="btn sm ghost">‹ prev</button>""" else ""}
        ${if (page < totalPages) """<button id="pg-next" class="btn sm">next ›</button>""" else ""}
    """.trimIndent()
    document.getElementById("pg-prev")?.addEventListener("click") {
        libPage--; scope.launch { loadLibraryPage(scope) }
    }
    document.getElementById("pg-next")?.addEventListener("click") {
        libPage++; scope.launch { loadLibraryPage(scope) }
    }
}

internal fun String.esc(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
