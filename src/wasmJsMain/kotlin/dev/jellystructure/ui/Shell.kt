package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.AuthApi
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

private val NAV: List<NavEntry> = listOf(
    NavLink("/dashboard", "Dashboard", "▦"),
    NavLink("/library", "Library", "▤"),
    NavLink("/triage", "Triage", "!", count = 214),
    NavLink("/activity", "Activity", "◷"),
    NavGroup("Setup"),
    NavLink("/cascade", "Cascade rules", "≣"),
    NavLink("/settings", "Settings", "⚙"),
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
}

fun updateActiveNav(currentRoute: String) {
    val links = document.querySelectorAll(".app-side a")
    for (i in 0 until links.length) {
        val a = links.item(i) as? HTMLElement ?: continue
        val href = a.getAttribute("href") ?: continue
        a.className = if (href == currentRoute) "active" else ""
    }
}

private fun shellHtml(user: UserProfile): String {
    val navHtml = NAV.joinToString("") { entry ->
        when (entry) {
            is NavGroup ->
                """<div class="group-label">${entry.label}</div>"""
            is NavLink -> {
                val count = entry.count?.let { """<span class="count">$it</span>""" } ?: ""
                """<a href="${entry.href}"><span class="l"><span class="ico">${entry.icon}</span>${entry.label}</span>$count</a>"""
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
