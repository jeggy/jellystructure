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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLElement
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

private val ICONS = mapOf(
    "dashboard" to """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="9" rx="1"/><rect x="14" y="3" width="7" height="5" rx="1"/><rect x="14" y="12" width="7" height="9" rx="1"/><rect x="3" y="16" width="7" height="5" rx="1"/></svg>""",
    "library"   to """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="m16 6 4 14"/><path d="M12 6v14"/><path d="M8 8v12"/><path d="M4 4v16"/></svg>""",
    "triage"    to """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/></svg>""",
    "activity"  to """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>""",
    "language"  to """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/><path d="M2 12h20"/></svg>""",
    "settings"  to """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><line x1="3" y1="8" x2="10" y2="8"/><circle cx="12" cy="8" r="2"/><line x1="14" y1="8" x2="21" y2="8"/><line x1="3" y1="16" x2="7" y2="16"/><circle cx="9" cy="16" r="2"/><line x1="11" y1="16" x2="21" y2="16"/></svg>""",
)

private val NAV: List<NavEntry> = listOf(
    NavLink("/dashboard", "Dashboard", "dashboard"),
    NavLink("/library", "Library", "library"),
    NavLink("/triage", "Triage", "triage", count = null),
    NavLink("/activity", "Activity", "activity"),
    NavGroup("Setup"),
    NavLink("/language", "Language", "language"),
    NavLink("/settings", "Settings", "settings"),
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
                App.navigate(href)
            }
        }
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

    // Inject ambient dock into body (hidden until a scan starts)
    injectDock(body as HTMLElement)

    MainScope().launch {
        // Triage badge count
        val count = MediaApi.getTriageCount()
        val badge = document.getElementById("triage-count-badge") as? HTMLElement
        if (badge != null && count != null && count.total > 0) {
            badge.textContent = count.total.toString()
        } else {
            badge?.remove()
        }
        // Sidebar status dots
        updateSidebarStatus(count?.total ?: 0)
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
    // Hide dock on Activity page, restore it elsewhere if scan is running
    val dock = document.getElementById("ambient-dock") as? HTMLElement ?: return
    if (currentRoute.startsWith("/activity")) {
        dock.style.display = "none"
    } else if (dock.style.display == "none" && dockScanned > 0) {
        // A scan was running before nav — re-show the dock
        dock.style.display = ""
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
        <div class="shell">
          <aside class="app-side">
            <div class="logo"><span class="glyph"></span> Jellystructure</div>
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
          <main class="app-main2 wide" id="page-content"></main>
        </div>
    """.trimIndent()
}
