package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.events.KeyboardEvent

/** R189 — the whole app's mutable "current screen" + shared services. Deliberately minimal: no
 *  navigation stack (Milestone 1 is Login -> Home -> Detail -> Player, a straight line with Back
 *  popping one level, tracked as a plain list). */
class App(val baseUrl: String) {
    val scope: CoroutineScope = MainScope()
    val httpClient = HttpClient(Js) { install(WebSockets) }
    val api = TvApiClient(
        client = httpClient,
        baseUrl = baseUrl,
        deviceToken = { MultiTokenStore.getActive()?.deviceToken },
    )

    private val backStack = mutableListOf<Screen>()
    private var current: Screen? = null

    fun show(screen: Screen, replaceStack: Boolean = false) {
        if (replaceStack) backStack.clear()
        current?.let { backStack.add(it) }
        current = screen
        render(screen)
    }

    /** Swaps the current screen without pushing it onto the back stack — used for auto-advance
     *  (episode N -> N+1) so Back from a binge exits the player once, not once per episode watched. */
    fun replaceTop(screen: Screen) {
        current = screen
        render(screen)
    }

    fun back(): Boolean {
        val previous = backStack.removeLastOrNull() ?: return false
        current = previous
        render(previous)
        return true
    }

    private fun render(screen: Screen) {
        val container = root()
        container.clear()
        currentScreenKeyHandler = screen
        screen.mount(container)
    }

    fun onKey(handled: (KeyboardEvent) -> Boolean) {
        document.onkeydown = { ev ->
            if (handled(ev)) ev.preventDefault()
            Unit
        }
    }
}

interface Screen {
    fun mount(container: org.w3c.dom.HTMLElement)
    /** Returns true if this screen consumed the key (stops it from falling through to global Back). */
    fun onKey(ev: KeyboardEvent): Boolean = false
}

lateinit var app: App

fun main() {
    // R189 — the backend base URL is baked in at build time via a webpack DefinePlugin substitution
    // (see webpack.config.d/tizen-config.js); falls back to the dev-time default so a plain
    // `jsBrowserDevelopmentRun` still works without the substitution.
    val baseUrl: String = js("(typeof RAVILO_BASE_URL !== 'undefined' ? RAVILO_BASE_URL : 'http://localhost:9505')").unsafeCast<String>()
    app = App(baseUrl)
    registerTvKeys()

    app.onKey { ev ->
        val screen = currentScreenKeyHandler
        val consumedByScreen = screen?.onKey(ev) ?: false
        if (consumedByScreen) return@onKey true
        if (ev.key == "Back" || ev.key == "Escape" || ev.keyCode == 10009) {
            return@onKey app.back()
        }
        false
    }

    // R189 milestone 2 — mirrors ravilo-ui's exact startup gate: 0 sessions -> Login, 1 -> straight to
    // Home (auto-activated), 2+ -> the profile picker.
    val sessions = MultiTokenStore.getAll()
    when {
        sessions.isEmpty() -> app.show(LoginScreen(), replaceStack = true)
        sessions.size == 1 -> {
            MultiTokenStore.setActive(sessions.first().userId)
            app.show(HomeScreen(), replaceStack = true)
        }
        else -> app.show(ProfilePickerScreen(), replaceStack = true)
    }
}

// Tracks whichever Screen is currently mounted so the single global keydown listener can delegate to
// it — App.show() below is extended (via the currentScreenKeyHandler var) rather than threading a
// listener through every screen constructor.
var currentScreenKeyHandler: Screen? = null
