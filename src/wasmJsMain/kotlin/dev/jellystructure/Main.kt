package dev.jellystructure

import dev.jellystructure.api.AuthApi
import dev.jellystructure.ui.renderActivity
import dev.jellystructure.ui.renderDashboard
import dev.jellystructure.ui.renderLibrary
import dev.jellystructure.ui.renderLogin
import dev.jellystructure.ui.renderBulkReorderWizard
import dev.jellystructure.ui.renderMediaDetail
import dev.jellystructure.ui.renderMetadata
import dev.jellystructure.ui.renderSetup
import dev.jellystructure.ui.renderRaviloConfig
import dev.jellystructure.ui.renderSettings
import dev.jellystructure.ui.renderShell
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
        val container = document.getElementById("page-content") ?: return
        val path = Router.currentPath()
        val query = Router.currentQuery()
        updateActiveNav(path)
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
            path.startsWith("/ravilo") -> renderRaviloConfig(container, scope)
            path == "/settings" -> renderSettings(container, scope, query)
            path.startsWith("/metadata") -> renderMetadata(container, scope, query["tab"] ?: "studios")
            else -> renderDashboard(container, scope)
        }
    }
}
