package dev.jellystructure

import dev.jellystructure.api.AuthApi
import dev.jellystructure.api.UserProfile
import dev.jellystructure.ui.renderActivity
import dev.jellystructure.ui.renderDashboard
import dev.jellystructure.ui.renderLibrary
import dev.jellystructure.ui.renderLogin
import dev.jellystructure.ui.renderBulkReorderWizard
import dev.jellystructure.ui.renderMediaDetail
import dev.jellystructure.ui.renderMetadata
import dev.jellystructure.ui.renderSegments
import dev.jellystructure.ui.renderSetup
import dev.jellystructure.ui.renderLiveTv
import dev.jellystructure.ui.renderRaviloConfig
import dev.jellystructure.ui.renderRaviloUsers
import dev.jellystructure.ui.renderSettings
import dev.jellystructure.ui.renderShell
import dev.jellystructure.ui.renderSubtitles
import dev.jellystructure.ui.updateActiveNav
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

fun main() {
    val scope = MainScope()
    scope.launch {
        App.start()
    }
}

object App {
    private val scope = MainScope()

    // Phase 163 — /segments is a fullscreen, chrome-less route (same technique as Login/Setup: it
    // replaces document.body wholesale, tearing down the sidebar/topbar). shellMounted tracks whether
    // the shell currently exists so handleRoute() knows to rebuild it before dispatching into
    // #page-content again once the user navigates away.
    private var shellMounted = false
    private var currentUser: UserProfile? = null

    suspend fun start() {
        if (AuthApi.isSetupNeeded()) {
            renderSetup { start() }
            return
        }

        val user = AuthApi.me()
        if (user == null) {
            renderLogin { start() }
            return
        }
        currentUser = user

        renderShell(user)
        shellMounted = true
        Router.init { _ -> handleRoute() }
        if (window.location.hash.isEmpty()) {
            Router.navigate("/dashboard")
        } else {
            handleRoute()
        }
    }

    fun navigate(route: String) {
        Router.navigate(route)
    }

    private fun handleRoute() {
        val path = Router.currentPath()
        val query = Router.currentQuery()
        if (path == "/segments") {
            shellMounted = false
            renderSegments(scope, query)
            return
        }
        if (!shellMounted) {
            currentUser?.let { renderShell(it) }
            shellMounted = true
        }
        val container = document.getElementById("page-content") ?: return
        updateActiveNav(Router.current())
        when {
            path == "/" || path.isEmpty() || path == "/dashboard" -> renderDashboard(container, scope)
            path.startsWith("/library") -> renderLibrary(container, scope, query)
            path.startsWith("/media/") && path.contains("/bulk-reorder") -> {
                val id = path.removePrefix("/media/").substringBefore('/')
                if (id.isNotEmpty()) renderBulkReorderWizard(container, scope, id)
                else renderLibrary(container, scope, query)
            }
            path.startsWith("/media/") -> {
                val id = path.removePrefix("/media/").substringBefore('?')
                if (id.isNotEmpty()) renderMediaDetail(container, scope, id, query["tab"])
                else renderLibrary(container, scope, query)
            }
            path == "/activity" -> renderActivity(container, scope, query)
            path == "/subtitles" -> renderSubtitles(container, scope)
            path.startsWith("/ravilo-users") -> renderRaviloUsers(container, scope)
            path.startsWith("/ravilo") -> renderRaviloConfig(container, scope)
            path.startsWith("/livetv") -> renderLiveTv(container, scope)
            path == "/settings" -> renderSettings(container, scope, query)
            path.startsWith("/metadata") -> renderMetadata(container, scope, query["tab"] ?: "studios")
            else -> renderDashboard(container, scope)
        }
    }
}
