package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.installSystemThemeWatcher
import dev.jellystructure.prefersDark
import dev.jellystructure.api.AuthApi
import dev.jellystructure.api.ConfigApi
import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.UserProfile
import dev.jellystructure.jobs.JobEvent
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event

private sealed class NavEntry
private data class NavLink(
    val href: String,
    val label: String,
    val icon: String,
    val count: Int? = null,
) : NavEntry()
private data class NavGroup(val label: String) : NavEntry()

private val dockJson = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }
private var dockSocket: WebSocket? = null
private var dockScanned = 0
private var dockTotal = 0

// Triage dock state
private var triageDockItems: List<dev.jellystructure.api.TriageItem> = emptyList()
private var triageDockIndex: Int = 0
private const val TRIAGE_DOCK_CLOSED_KEY = "js-attn-dock-closed"
private var triageDockHidden: Boolean = false

/** One-line "what's wrong" summary for the current triage item, mirroring the design dock sub-line. */
private fun triageSubline(item: dev.jellystructure.api.TriageItem): String {
    val parts = mutableListOf<String>()
    val untagged = item.untaggedTracks.size
    if (untagged > 0) parts += "$untagged untagged audio track${if (untagged != 1) "s" else ""}"
    item.cascadeMismatch?.let {
        val actual = it.actualDefaultLang ?: "?"
        parts += "wrong default audio ($actual → ${it.resolvedLanguage})"
    }
    if (item.multiDefault != null) parts += "multiple default audio"
    if (item.languageMix) parts += "mixed-language series"
    val epIssues = item.episodeIssues
    if (epIssues.isNotEmpty()) {
        val first = epIssues.first()
        val epParts = mutableListOf<String>()
        if (first.untaggedTracks.isNotEmpty()) epParts += "untagged tracks"
        if (first.multiDefault != null) epParts += "multiple default audio"
        if (first.missingOverview) epParts += "missing overview"
        val more = if (epIssues.size > 1) " (+${epIssues.size - 1} more)" else ""
        parts += "${first.episodeCode} · ${epParts.joinToString(", ").ifEmpty { "needs attention" }}$more"
    }
    return parts.joinToString(" · ").ifEmpty { "needs attention" }
}

private val ICONS = mapOf(
    "dashboard" to """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="9" rx="1"/><rect x="14" y="3" width="7" height="5" rx="1"/><rect x="14" y="12" width="7" height="9" rx="1"/><rect x="3" y="16" width="7" height="5" rx="1"/></svg>""",
    "library"   to """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="m16 6 4 14"/><path d="M12 6v14"/><path d="M8 8v12"/><path d="M4 4v16"/></svg>""",
    "triage"    to """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/></svg>""",
    "activity"  to """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>""",
    "language"  to """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/><path d="M2 12h20"/></svg>""",
    "metadata"  to """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/><line x1="7" y1="7" x2="7.01" y2="7"/></svg>""",
    "settings"  to """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><line x1="3" y1="8" x2="10" y2="8"/><circle cx="12" cy="8" r="2"/><line x1="14" y1="8" x2="21" y2="8"/><line x1="3" y1="16" x2="7" y2="16"/><circle cx="9" cy="16" r="2"/><line x1="11" y1="16" x2="21" y2="16"/></svg>""",
    "tv"        to """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="5" width="20" height="13" rx="2"/><polyline points="8 21 12 18 16 21"/></svg>""",
)

private val NAV: List<NavEntry> = listOf(
    NavLink("/dashboard", "Dashboard", "dashboard"),
    NavLink("/library", "Library", "library"),
    NavLink("/activity", "Activity", "activity"),
    NavGroup("Setup"),
    NavLink("/metadata", "Metadata", "metadata"),
    NavLink("/settings", "Settings", "settings"),
    NavGroup("Apps"),
    NavLink("/ravilo", "Ravilo TV", "tv"),
)

fun renderShell(user: UserProfile) {
    val body = document.body ?: return

    // Apply persisted theme before rendering HTML to avoid flash
    val savedTheme = window.localStorage.getItem("js-theme") ?: "system"
    val effectiveTheme = resolveTheme(savedTheme)
    document.documentElement?.setAttribute("data-theme", effectiveTheme)

    body.innerHTML = shellHtml(user, savedTheme)

    document.querySelectorAll(".app-side a").let { links ->
        for (i in 0 until links.length) {
            val a = links.item(i) as? HTMLElement ?: continue
            val href = a.getAttribute("href") ?: continue
            a.addEventListener("click") { e ->
                e.preventDefault()
                document.body?.classList?.remove("nav-open")
                App.navigate(href)
            }
        }
    }

    document.getElementById("nav-burger")?.addEventListener("click") { _ ->
        val cl = document.body?.classList ?: return@addEventListener
        if (cl.contains("nav-open")) cl.remove("nav-open") else cl.add("nav-open")
    }
    document.getElementById("nav-backdrop")?.addEventListener("click") { _ ->
        document.body?.classList?.remove("nav-open")
    }

    document.getElementById("logout-btn")?.addEventListener("click") { e ->
        e.preventDefault()
        MainScope().launch {
            AuthApi.logout()
            App.start()
        }
    }

    // Install OS preference watcher (fires only when preference is "system")
    installSystemThemeWatcher()

    // Three-way theme picker
    document.querySelectorAll("#theme-picker span").let { items ->
        for (i in 0 until items.length) {
            val span = items.item(i) as? HTMLElement ?: continue
            span.addEventListener("click") {
                val opt = span.getAttribute("data-theme-opt") ?: return@addEventListener
                document.documentElement?.setAttribute("data-theme", resolveTheme(opt))
                window.localStorage.setItem("js-theme", opt)
                for (j in 0 until items.length) {
                    (items.item(j) as? HTMLElement)?.className = if (j == i) "on" else ""
                }
            }
        }
    }

    // Inject ambient dock and triage dock into body
    triageDockHidden = window.localStorage.getItem(TRIAGE_DOCK_CLOSED_KEY) == "1"
    injectDock(body as HTMLElement)
    injectTriageDock(body)
    injectCommandPalette(body)
    wireGlobalKeyBindings()

    document.getElementById("cmd-search-pill")?.addEventListener("click") { showPalette() }

    MainScope().launch {
        // Triage count → sidebar status dots + the floating Triage dock (Phase 27; there is no
        // Triage page or nav badge any more — the dock is the surface).
        val count = MediaApi.getTriageCount()
        // Sidebar status dots
        updateSidebarStatus(count?.total ?: 0)
        // Load triage dock items
        if ((count?.total ?: 0) > 0) {
            triageDockItems = MediaApi.getTriageItems()
            triageDockIndex = 0
            updateTriageDock()
        }
        // Connection status (non-blocking, best effort)
        try {
            val conn = ConfigApi.testConnections()
            if (conn != null) {
                (document.getElementById("status-jf") as? HTMLElement)?.apply {
                    innerHTML = """<span class="dot ${if (conn.jellyfin) "ok" else "bad"}"></span> Jellyfin ${if (conn.jellyfin) "online" else "offline"}"""
                }
                (document.getElementById("status-tmdb") as? HTMLElement)?.apply {
                    innerHTML = """<span class="dot ${if (conn.tmdb) "ok" else "bad"}"></span> TMDB key ${if (conn.tmdb) "OK" else "invalid"}"""
                }
            }
        } catch (_: Exception) {}

        // Reflect backend scan state — dock count comes from server, not just WS events
        val status = MediaApi.scanStatus()
        if (status?.running == true) {
            dockScanned = status.processedCount
            showDock()
            updateDockCount()
        } else if (status?.status == "CANCELLED") {
            showCancelledBanner(status.processedCount)
        }
        // Always connect dock WS to catch scans started from any page
        connectDockSocket()
    }
}

private data class PaletteCmd(val label: String, val detail: String = "", val action: () -> Unit)
private var paletteSearchJob: Job? = null

private fun buildPaletteCommands(): List<PaletteCmd> = listOf(
    PaletteCmd("Go to Library", "Browse all media") { App.navigate("/library") },
    PaletteCmd("Go to Settings", "Connections, scan & metadata options") { App.navigate("/settings") },
    PaletteCmd("Go to Activity", "Scan log and workers") { App.navigate("/activity") },
    PaletteCmd("Go to Metadata", "Studios, networks, genres & tags") { App.navigate("/metadata") },
    PaletteCmd("Triage: first item", "Items needing attention") {
        if (triageDockItems.isNotEmpty()) {
            triageDockIndex = 0
            updateTriageDock()
            navigateToTriageItem(triageDockItems[0])
        }
    },
    PaletteCmd("Go to Dashboard", "Overview and stats") { App.navigate("/") },
    PaletteCmd("Start full scan", "Re-scan all Jellyfin items") {
        MainScope().launch { MediaApi.startScan() }
    },
    PaletteCmd("Triage: next item", "n key") {
        if (triageDockItems.isNotEmpty()) {
            triageDockIndex = (triageDockIndex + 1) % triageDockItems.size
            updateTriageDock()
            navigateToTriageItem(triageDockItems[triageDockIndex])
        }
    },
    PaletteCmd("Triage: previous item", "p key") {
        if (triageDockItems.isNotEmpty()) {
            triageDockIndex = (triageDockIndex - 1 + triageDockItems.size) % triageDockItems.size
            updateTriageDock()
            navigateToTriageItem(triageDockItems[triageDockIndex])
        }
    },
)

private fun injectCommandPalette(body: HTMLElement) {
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "cmd-palette-overlay"
    overlay.setAttribute("style", "display:none;position:fixed;inset:0;background:rgba(0,0,0,.55);z-index:9000;align-items:flex-start;justify-content:center;padding-top:100px;")
    overlay.innerHTML = """
        <div style="background:var(--fill);border:1px solid var(--line-2);border-radius:var(--radius);width:100%;max-width:540px;box-shadow:var(--shadow);overflow:hidden;">
          <div style="display:flex;align-items:center;gap:8px;padding:12px 16px;border-bottom:1px solid var(--line);">
            <span style="color:var(--ink-soft);font-size:.9rem;">⌘</span>
            <input id="cmd-palette-input" autocomplete="off" spellcheck="false" placeholder="Search commands…"
              style="flex:1;border:none;outline:none;background:transparent;font-size:.95rem;color:var(--ink);">
          </div>
          <div id="cmd-palette-list" style="max-height:320px;overflow-y:auto;padding:6px 0;"></div>
        </div>"""
    body.appendChild(overlay)

    overlay.addEventListener("click") { e ->
        if ((e.target as? HTMLElement) == overlay) hidePalette()
    }
}

private fun hidePalette() {
    val overlay = document.getElementById("cmd-palette-overlay") as? HTMLElement ?: return
    overlay.style.display = "none"
    (document.getElementById("cmd-palette-input") as? HTMLInputElement)?.value = ""
}

private fun showPalette() {
    val overlay = document.getElementById("cmd-palette-overlay") as? HTMLElement ?: return
    overlay.style.display = "flex"
    val input = document.getElementById("cmd-palette-input") as? HTMLInputElement ?: return
    input.focus()
    renderPaletteList("")
    input.addEventListener("input") {
        renderPaletteList((document.getElementById("cmd-palette-input") as? HTMLInputElement)?.value ?: "")
    }
    input.addEventListener("keydown") { e ->
        val kev = (e as? org.w3c.dom.events.KeyboardEvent) ?: return@addEventListener
        when (kev.key) {
            "Escape" -> hidePalette()
            "Enter" -> {
                val sel = document.querySelector("#cmd-palette-list .cmdp-item.sel") as? HTMLElement
                    ?: document.querySelector("#cmd-palette-list .cmdp-item") as? HTMLElement
                sel?.click()
            }
            "ArrowDown", "ArrowUp" -> {
                kev.preventDefault()
                val items = document.querySelectorAll("#cmd-palette-list .cmdp-item")
                if (items.length == 0) return@addEventListener
                val curEl = document.querySelector("#cmd-palette-list .cmdp-item.sel")
                curEl?.classList?.remove("sel")
                val curIdx = if (curEl != null) (0 until items.length).indexOfFirst { items.item(it) == curEl } else -1
                val nextIdx = when {
                    kev.key == "ArrowDown" -> if (curIdx < 0) 0 else (curIdx + 1) % items.length
                    curIdx < 0 -> items.length - 1
                    else -> (curIdx - 1 + items.length) % items.length
                }
                (items.item(nextIdx) as? HTMLElement)?.classList?.add("sel")
            }
        }
    }
}

private fun renderPaletteList(query: String) {
    val listEl = document.getElementById("cmd-palette-list") as? HTMLElement ?: return

    paletteSearchJob?.cancel()
    paletteSearchJob = null

    val cmds = buildPaletteCommands()
    val filtered = if (query.isBlank()) cmds else cmds.filter {
        it.label.contains(query, ignoreCase = true) || it.detail.contains(query, ignoreCase = true)
    }

    val sectionHd = """<div style="padding:4px 12px 2px;font-size:.65rem;text-transform:uppercase;letter-spacing:.07em;color:var(--ink-soft);font-weight:600;">"""

    val cmdsHtml = buildString {
        if (filtered.isNotEmpty()) {
            if (query.length >= 2) append("${sectionHd}Commands</div>")
            filtered.forEachIndexed { i, cmd ->
                append("""<div class="cmdp-item" data-ci="$i">""")
                append("""<span class="cmdp-ico">→</span>""")
                append("""<span style="flex:1;min-width:0;"><span class="cmdp-label">${cmd.label.esc()}</span>""")
                if (cmd.detail.isNotBlank()) append("""<br><span class="cmdp-sub">${cmd.detail.esc()}</span>""")
                append("""</span></div>""")
            }
        } else if (query.isNotBlank() && query.length < 2) {
            append("""<div class="cmdp-empty">Keep typing…</div>""")
        } else if (query.isNotBlank()) {
            append("""<div class="cmdp-empty">No commands match</div>""")
        }
        if (query.length >= 2) {
            append("""<div id="cmd-palette-media"><div class="cmdp-empty" style="padding:8px 16px;">Searching library…</div></div>""")
        }
    }

    listEl.innerHTML = cmdsHtml

    // Wire command item clicks
    val cmdItems = listEl.querySelectorAll(".cmdp-item[data-ci]")
    for (i in 0 until cmdItems.length) {
        val el = cmdItems.item(i) as? HTMLElement ?: continue
        val idx = el.getAttribute("data-ci")?.toIntOrNull() ?: continue
        el.addEventListener("click") { hidePalette(); filtered.getOrNull(idx)?.action?.invoke() }
    }

    if (query.length < 2) return

    // Debounced media search
    paletteSearchJob = MainScope().launch {
        delay(200)
        val mediaSection = document.getElementById("cmd-palette-media") as? HTMLElement ?: return@launch
        val items = MediaApi.list(search = query, pageSize = 6)?.items ?: emptyList()
        if (items.isEmpty()) {
            mediaSection.innerHTML = """<div class="cmdp-empty" style="padding:8px 16px;">No titles found</div>"""
            return@launch
        }
        val sep = if (filtered.isNotEmpty()) """<hr style="border:none;border-top:1px solid var(--line);margin:4px 0 0;">""" else ""
        mediaSection.innerHTML = sep + "${sectionHd}Library</div>" + items.joinToString("") { item ->
            val isTv = item.kind == dev.jellystructure.model.MediaKind.TV_SHOW
            val kindLabel = if (isTv) "TV" else "MOVIE"
            val yearStr = if (item.year != null) " (${item.year})" else ""
            val ico = if (isTv) "📺" else "🎬"
            """<div class="cmdp-item" data-mid="${item.id.esc()}">
               <span class="cmdp-ico">$ico</span>
               <span style="flex:1;min-width:0;"><span class="cmdp-label">${item.title.esc()}$yearStr</span></span>
               <span class="cmdp-kind">$kindLabel</span>
             </div>"""
        }
        // Wire media item clicks
        val mediaItems = mediaSection.querySelectorAll(".cmdp-item[data-mid]")
        for (i in 0 until mediaItems.length) {
            val el = mediaItems.item(i) as? HTMLElement ?: continue
            val mid = el.getAttribute("data-mid") ?: continue
            el.addEventListener("click") { hidePalette(); App.navigate("/media/$mid") }
        }
    }
}

private fun wireGlobalKeyBindings() {
    document.addEventListener("keydown") { e ->
        val kev = e as? org.w3c.dom.events.KeyboardEvent ?: return@addEventListener
        val target = e.target as? HTMLElement
        val inInput = target?.tagName in setOf("INPUT", "TEXTAREA") || target?.isContentEditable == true
        // ⌘K / Ctrl+K — open command palette
        if (kev.key == "k" && (kev.ctrlKey || kev.metaKey)) {
            e.preventDefault()
            val overlay = document.getElementById("cmd-palette-overlay") as? HTMLElement
            if (overlay?.style?.display == "none" || overlay?.style?.display == "") showPalette() else hidePalette()
            return@addEventListener
        }
        if (inInput) return@addEventListener
        // Triage dock keyboard shortcuts
        when (kev.key) {
            "n" -> {
                if (triageDockItems.isNotEmpty()) {
                    triageDockIndex = (triageDockIndex + 1) % triageDockItems.size
                    updateTriageDock()
                    navigateToTriageItem(triageDockItems[triageDockIndex])
                }
            }
            "p" -> {
                if (triageDockItems.isNotEmpty()) {
                    triageDockIndex = (triageDockIndex - 1 + triageDockItems.size) % triageDockItems.size
                    updateTriageDock()
                    navigateToTriageItem(triageDockItems[triageDockIndex])
                }
            }
            "o" -> {
                if (triageDockItems.isNotEmpty()) {
                    navigateToTriageItem(triageDockItems[triageDockIndex])
                }
            }
        }
    }
}

private fun navigateToTriageItem(item: dev.jellystructure.api.TriageItem) {
    val tab = if (item.kind == "tv") "episodes" else "overview"
    dev.jellystructure.Router.navigate("/media/${item.mediaId}", mapOf("tab" to tab))
}

private fun injectDock(body: HTMLElement) {
    val el = document.createElement("div") as HTMLElement
    el.id = "ambient-dock"
    el.className = "dock"
    el.style.display = "none"
    el.innerHTML = """
        <div class="dock-head" id="dock-head">
          <span class="dot ok" id="dock-dot"></span>
          <b id="dock-title">Scanning</b>
          <span class="spacer"></span>
          <span class="tiny mono" id="dock-count"></span>
          <span class="kbd toggle-dock" id="dock-toggle" style="cursor:pointer;padding:0 4px">⌄</span>
        </div>
        <div class="dock-body">
          <div class="bar"><i id="dock-bar" style="width:0%"></i></div>
          <div class="mono tiny" id="dock-file" style="margin-top:9px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap"></div>
          <div class="row center" style="gap:7px;margin-top:11px">
            <span class="chip" style="font-size:.68rem" id="dock-chip-scanned"><span class="dot ok"></span> 0</span>
            <span class="spacer"></span>
            <a href="#" id="dock-activity-link" class="tiny">open console ↗</a>
          </div>
        </div>
    """.trimIndent()
    body.appendChild(el)

    el.querySelector("#dock-toggle")?.addEventListener("click") { e ->
        e.stopPropagation()
        val dock = document.getElementById("ambient-dock") as? HTMLElement ?: return@addEventListener
        val collapsed = dock.className.contains("collapsed")
        dock.className = if (collapsed) "dock" else "dock collapsed"
        (el.querySelector("#dock-toggle") as? HTMLElement)?.textContent = if (collapsed) "⌄" else "⌃"
    }

    el.querySelector("#dock-activity-link")?.addEventListener("click") { e ->
        e.preventDefault()
        App.navigate("/activity")
    }
}

private fun injectTriageDock(body: HTMLElement) {
    val el = document.createElement("div") as HTMLElement
    el.id = "triage-dock"
    el.className = "dock"
    el.style.display = "none"
    el.style.bottom = "72px"
    el.innerHTML = """
        <div class="dock-head" id="triage-dock-head">
          <span class="dot warn" style="background:var(--warn,#f59e0b);"></span>
          <b>Needs attention</b>
          <span class="spacer"></span>
          <span class="tiny mono" id="triage-dock-pos"></span>
          <span class="kbd toggle-dock" id="triage-dock-toggle" style="cursor:pointer;padding:0 4px" title="Collapse">⌄</span>
          <span class="kbd" id="triage-dock-close" style="cursor:pointer;padding:0 4px" title="Hide">✕</span>
        </div>
        <div class="dock-body">
          <div style="margin-top:2px;">
            <div id="triage-dock-title" style="font-size:.86rem;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;"></div>
            <div id="triage-dock-sub" class="tiny muted" style="margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;"></div>
          </div>
          <div class="row center" style="gap:6px;margin-top:10px;">
            <button class="btn sm ghost" id="triage-dock-prev" style="font-size:.75rem;padding:2px 8px;">‹ Prev</button>
            <button class="btn sm primary" id="triage-dock-open" style="font-size:.75rem;padding:2px 8px;flex:1;justify-content:center;">Open &amp; fix →</button>
            <button class="btn sm ghost" id="triage-dock-next" style="font-size:.75rem;padding:2px 8px;">Next ›</button>
          </div>
        </div>
    """.trimIndent()
    body.appendChild(el)

    el.querySelector("#triage-dock-close")?.addEventListener("click") { e ->
        e.stopPropagation()
        triageDockHidden = true
        window.localStorage.setItem(TRIAGE_DOCK_CLOSED_KEY, "1")
        el.style.display = "none"
    }

    el.querySelector("#triage-dock-open")?.addEventListener("click") { e ->
        e.stopPropagation()
        if (triageDockItems.isEmpty()) return@addEventListener
        navigateToTriageItem(triageDockItems[triageDockIndex])
    }

    el.querySelector("#triage-dock-toggle")?.addEventListener("click") { e ->
        e.stopPropagation()
        val dock = document.getElementById("triage-dock") as? HTMLElement ?: return@addEventListener
        val collapsed = dock.className.contains("collapsed")
        dock.className = if (collapsed) "dock" else "dock collapsed"
        dock.style.bottom = if (collapsed) "72px" else "72px"
        (el.querySelector("#triage-dock-toggle") as? HTMLElement)?.textContent = if (collapsed) "⌄" else "⌃"
    }

    el.querySelector("#triage-dock-prev")?.addEventListener("click") { e ->
        e.stopPropagation()
        if (triageDockItems.isEmpty()) return@addEventListener
        triageDockIndex = (triageDockIndex - 1 + triageDockItems.size) % triageDockItems.size
        updateTriageDock()
        navigateToTriageItem(triageDockItems[triageDockIndex])
    }

    el.querySelector("#triage-dock-next")?.addEventListener("click") { e ->
        e.stopPropagation()
        if (triageDockItems.isEmpty()) return@addEventListener
        triageDockIndex = (triageDockIndex + 1) % triageDockItems.size
        updateTriageDock()
        navigateToTriageItem(triageDockItems[triageDockIndex])
    }
}

internal fun updateTriageDock() {
    val el = document.getElementById("triage-dock") as? HTMLElement ?: return
    if (triageDockItems.isEmpty()) {
        el.style.display = "none"
        return
    }
    if (triageDockHidden) { el.style.display = "none"; return }
    el.style.display = ""
    val total = triageDockItems.size
    val pos = triageDockIndex + 1
    val item = triageDockItems.getOrNull(triageDockIndex)
    (document.getElementById("triage-dock-title") as? HTMLElement)?.textContent = item?.title ?: "—"
    (document.getElementById("triage-dock-sub") as? HTMLElement)?.textContent =
        item?.let { triageSubline(it) } ?: ""
    (document.getElementById("triage-dock-pos") as? HTMLElement)?.textContent = "$pos / $total"
}

internal fun refreshTriageDockCount() {
    MainScope().launch {
        val count = MediaApi.getTriageCount()
        val total = count?.total ?: 0
        if (total == 0) {
            triageDockItems = emptyList()
            triageDockIndex = 0
            updateTriageDock()
        } else {
            triageDockItems = MediaApi.getTriageItems()
            if (triageDockIndex >= triageDockItems.size) triageDockIndex = 0
            updateTriageDock()
        }
        updateSidebarStatus(total)
    }
}

private fun connectDockSocket() {
    val proto = if (window.location.protocol == "https:") "wss" else "ws"
    val ws = WebSocket("$proto://${window.location.host}/ws")
    dockSocket = ws

    ws.onmessage = { ev ->
        val text = ev.data.toString()
        runCatching {
            when (val event = dockJson.decodeFromString<JobEvent>(text)) {
                is JobEvent.Started -> {
                    // Only reset to 0 for a fresh scan; resume keeps the existing dockScanned base
                    if (dockScanned == 0) dockTotal = event.total
                    showDock()
                    updateDockCount()
                }
                is JobEvent.ItemScanned -> {
                    dockScanned++
                    updateDockCount()
                }
                is JobEvent.FileProgress -> {
                    val short = event.file.substringAfterLast('/')
                    (document.getElementById("dock-file") as? HTMLElement)?.textContent = short
                    if (event.total > 0) {
                        val pct = (event.current * 100 / event.total).coerceIn(0, 100)
                        (document.getElementById("dock-bar") as? HTMLElement)?.setAttribute("style", "width:${pct}%")
                    }
                }
                is JobEvent.Finished -> {
                    hideDock()
                    dockScanned = 0
                    hideCancelledBanner()
                }
                else -> {}
            }
        }
    }

    ws.onclose = { _: Event ->
        if (dockSocket == ws) dockSocket = null
    }
}

private fun showCancelledBanner(processedCount: Int) {
    val statusEl = document.getElementById("status-triage") as? HTMLElement ?: return
    val existing = document.getElementById("scan-cancelled-banner")
    if (existing != null) return
    val banner = document.createElement("div") as HTMLElement
    banner.id = "scan-cancelled-banner"
    banner.setAttribute(
        "style",
        "margin-top:6px;padding:6px 8px;background:color-mix(in srgb,var(--warn) 12%,transparent);border:1px solid color-mix(in srgb,var(--warn) 40%,transparent);border-radius:6px;font-size:.73rem;line-height:1.4;",
    )
    banner.innerHTML = """
        <div style="color:var(--warn);font-weight:600;margin-bottom:2px;">Scan paused</div>
        <div style="color:var(--ink-soft);">$processedCount item${if (processedCount != 1) "s" else ""} done — <a href="#/dashboard" style="color:var(--hi);text-decoration:none;">resume from Dashboard →</a></div>
    """.trimIndent()
    statusEl.parentElement?.insertBefore(banner, statusEl)
}

private fun hideCancelledBanner() {
    document.getElementById("scan-cancelled-banner")?.remove()
}

private fun showDock() {
    // Don't show dock on the Activity page
    val currentRoute = window.location.hash.removePrefix("#")
    if (currentRoute.startsWith("/activity")) return
    (document.getElementById("ambient-dock") as? HTMLElement)?.style?.display = ""
}

private fun hideDock() {
    (document.getElementById("ambient-dock") as? HTMLElement)?.style?.display = "none"
}

private fun updateDockCount() {
    (document.getElementById("dock-count") as? HTMLElement)?.textContent =
        if (dockTotal > 0) "$dockScanned/$dockTotal" else "$dockScanned"
    (document.getElementById("dock-chip-scanned") as? HTMLElement)?.innerHTML =
        """<span class="dot ok"></span> $dockScanned"""
}

private fun updateSidebarStatus(triageCount: Int) {
    (document.getElementById("status-triage") as? HTMLElement)?.apply {
        innerHTML = """<span class="dot ${if (triageCount > 0) "warn" else "ok"}"></span> $triageCount to triage"""
    }
}

fun updateActiveNav(currentRoute: String) {
    val links = document.querySelectorAll(".app-side a")
    for (i in 0 until links.length) {
        val a = links.item(i) as? HTMLElement ?: continue
        val href = a.getAttribute("href") ?: continue
        a.className = if (href == currentRoute) "nav active" else "nav"
    }
    // Hide scan dock on Activity page, restore it elsewhere if scan is running
    val dock = document.getElementById("ambient-dock") as? HTMLElement ?: return
    if (currentRoute.startsWith("/activity")) {
        dock.style.display = "none"
    } else if (dock.style.display == "none" && dockScanned > 0) {
        dock.style.display = ""
    }
    // Sync triage dock index when navigating to a media item
    if (currentRoute.startsWith("/media/")) {
        val mediaId = currentRoute.removePrefix("/media/").substringBefore('?')
        val idx = triageDockItems.indexOfFirst { it.mediaId == mediaId }
        if (idx >= 0) triageDockIndex = idx
        updateTriageDock()
    }
}

private fun resolveTheme(pref: String): String = when (pref) {
    "system" -> if (prefersDark()) "dark" else "light"
    else -> pref
}

private fun shellHtml(user: UserProfile, savedPref: String = "system"): String {
    val navHtml = NAV.joinToString("") { entry ->
        when (entry) {
            is NavGroup ->
                """<div class="group-label">${entry.label}</div>"""
            is NavLink -> {
                val countHtml = when {
                    entry.href == "/triage" -> """<span class="count" id="triage-count-badge"></span>"""
                    entry.count != null -> """<span class="count">${entry.count}</span>"""
                    else -> ""
                }
                """<a class="nav" href="${entry.href}"><span class="l"><span class="ico">${ICONS[entry.icon] ?: entry.icon}</span>${entry.label}</span>$countHtml</a>"""
            }
        }
    }
    return """
        <div class="nav-backdrop" id="nav-backdrop"></div>
        <div class="shell">
          <aside class="app-side">
            <div class="logo"><span class="glyph"></span> Jellystructure</div>
            <button class="cmdk-pill" id="cmd-search-pill"><span>⌕ Search…</span><span class="kbd">⌘K</span></button>
            $navHtml
            <div class="grow"></div>
            <div class="status">
              <div class="row" id="status-jf"><span class="dot ok"></span> Jellyfin…</div>
              <div class="row" id="status-tmdb" style="margin-top:2px"><span class="dot ok"></span> TMDB…</div>
              <div class="row" id="status-triage" style="margin-top:2px"><span class="dot ok"></span> — to triage</div>
              <div class="row" style="margin-top:6px">
                <span class="dot ok"></span> ${user.name}
              </div>
              <div class="row" style="margin-top:3px">
                <a id="logout-btn" href="#" style="color:var(--ink-soft);font-size:.76rem;text-decoration:none">Sign out</a>
              </div>
            </div>
            <div style="padding:8px 0 4px">
              <div class="seg" id="theme-picker" style="width:100%;font-size:.75rem;">
                <span class="${"on".takeIf { savedPref == "light" } ?: ""}" data-theme-opt="light">Light</span>
                <span class="${"on".takeIf { savedPref == "dark" } ?: ""}" data-theme-opt="dark">Dark</span>
                <span class="${"on".takeIf { savedPref == "system" || (savedPref != "light" && savedPref != "dark") } ?: ""}" data-theme-opt="system">System</span>
              </div>
            </div>
          </aside>
          <div class="app-main-col">
            <header class="app-topbar">
              <button class="burger" id="nav-burger" aria-label="Open navigation">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
              </button>
              <div class="tb-logo"><span class="glyph"></span> Jellystructure</div>
            </header>
            <main class="app-main2 wide" id="page-content"></main>
          </div>
        </div>
    """.trimIndent()
}
