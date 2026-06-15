package dev.jellystructure

import dev.jellystructure.api.AuthApi
import dev.jellystructure.ui.renderActivity
import dev.jellystructure.ui.renderDashboard
import dev.jellystructure.ui.renderLanguage
import dev.jellystructure.ui.renderLibrary
import dev.jellystructure.ui.renderLogin
import dev.jellystructure.ui.renderMediaDetail
import dev.jellystructure.ui.renderSetup
import dev.jellystructure.ui.renderSettings
import dev.jellystructure.ui.renderShell
import dev.jellystructure.ui.renderTriage
import dev.jellystructure.ui.renderTrackOrder
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

        renderShell(user)
        Router.init { route -> handleRoute(route) }
        val current = Router.current()
        if (window.location.hash.isEmpty()) {
            // Came from a plain URL (e.g. /login with no hash) — set canonical hash so
            // the address bar reflects the current route and URL checks work reliably.
            Router.navigate(current) // fires hashchange → handleRoute via listener
        } else {
            handleRoute(current)
        }
    }

    fun navigate(route: String) {
        Router.navigate(route)
    }

    private fun handleRoute(route: String) {
        val container = document.getElementById("page-content") ?: return
        updateActiveNav(route)
        when {
            route == "/" || route.isEmpty() || route == "/dashboard" -> renderDashboard(container, scope)
            route == "/library" -> renderLibrary(container, scope)
            route.startsWith("/media/") -> {
                val id = route.removePrefix("/media/")
                if (id.isNotEmpty()) renderMediaDetail(container, scope, id)
                else renderLibrary(container, scope)
            }
            route == "/language" -> renderLanguage(container, scope)
            route == "/triage"   -> renderTriage(container, scope)
            route == "/activity" -> renderActivity(container, scope)
            route == "/settings" -> renderSettings(container, scope)
            route.startsWith("/track-order") -> {
                val id = route.substringAfter("id=", "").substringBefore("&")
                if (id.isNotEmpty()) renderTrackOrder(container, scope, id)
                else renderLibrary(container, scope)
            }
            else -> renderDashboard(container, scope)
        }
    }
}
