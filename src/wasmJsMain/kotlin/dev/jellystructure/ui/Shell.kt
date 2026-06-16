package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.AuthApi
import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.UserProfile
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

private sealed class NavEntry
private data class NavLink(
    val href: String,
    val label: String,
    val icon: String,
    val count: Int? = null,
) : NavEntry()
private data class NavGroup(val label: String) : NavEntry()

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
    body.innerHTML = shellHtml(user)

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

    MainScope().launch {
        val count = MediaApi.getTriageCount()
        val badge = document.getElementById("triage-count-badge") as? HTMLElement
        if (badge != null && count != null && count.total > 0) {
            badge.textContent = count.total.toString()
        } else {
            badge?.remove()
        }
    }
}

fun updateActiveNav(currentRoute: String) {
    val links = document.querySelectorAll(".app-side a")
    for (i in 0 until links.length) {
        val a = links.item(i) as? HTMLElement ?: continue
        val href = a.getAttribute("href") ?: continue
        a.className = if (href == currentRoute) "nav active" else "nav"
    }
}

private fun shellHtml(user: UserProfile): String {
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
              <div class="row"><span class="dot ok"></span> ${user.name}</div>
              <div class="row" style="margin-top:3px">
                <a id="logout-btn" href="#" style="color:var(--ink-soft);font-size:.76rem;text-decoration:none">Sign out</a>
              </div>
            </div>
          </aside>
          <main class="app-main2 wide" id="page-content"></main>
        </div>
    """.trimIndent()
}
