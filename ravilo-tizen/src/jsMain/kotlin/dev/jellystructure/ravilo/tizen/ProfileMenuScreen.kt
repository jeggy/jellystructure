package dev.jellystructure.ravilo.tizen

import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** R189 milestone 2 — the profile menu, reached from the nav bar's avatar. A dedicated screen (not a
 *  floating dropdown) — simpler than layering an overlay on top of the current screen's own DOM/focus
 *  state, and a perfectly normal TV-app pattern. Rows: Switch profile / Settings / My List / Sign out /
 *  Unpair this TV (mirrors `ravilo-ui`'s `ProfileMenu` row set). */
class ProfileMenuScreen : Screen {
    private var index = 0
    private lateinit var rows: List<HTMLElement>
    private val active get() = MultiTokenStore.getActive()

    override fun mount(container: HTMLElement) {
        val root = container.child("div", "menu-screen") {
            child("h1", "menu-title", active?.displayName ?: "Profile")
        }
        val list = root.child("div", "menu-list")
        rows = listOf(
            list.child("div", "menu-row", "Switch profile"),
            list.child("div", "menu-row", "My List"),
            list.child("div", "menu-row", "Settings"),
            list.child("div", "menu-row", "Sign out"),
            list.child("div", "menu-row menu-danger", "Unpair this TV"),
        )
        highlight()
    }

    private fun highlight() {
        rows.forEachIndexed { i, el -> el.classList.toggle("focused", i == index) }
    }

    override fun onKey(ev: KeyboardEvent): Boolean {
        when (ev.key) {
            "ArrowDown" -> { if (index < rows.size - 1) { index++; highlight() }; return true }
            "ArrowUp" -> { if (index > 0) { index--; highlight() }; return true }
            "Enter" -> { activate(); return true }
        }
        return false
    }

    private fun activate() {
        when (index) {
            0 -> app.show(ProfilePickerScreen())
            1 -> app.show(MyListScreen())
            2 -> app.show(SettingsScreen())
            3 -> signOut()
            4 -> unpairAll()
        }
    }

    private fun signOut() {
        val session = active ?: return
        MultiTokenStore.remove(session.userId)
        val remaining = MultiTokenStore.getAll()
        if (remaining.isEmpty()) app.show(LoginScreen(), replaceStack = true)
        else app.show(ProfilePickerScreen(), replaceStack = true)
    }

    private fun unpairAll() {
        val sessions = MultiTokenStore.getAll()
        app.scope.launch {
            for (s in sessions) runCatching { app.api.unpair(s.deviceToken) }
            MultiTokenStore.clear()
            app.show(LoginScreen(), replaceStack = true)
        }
    }
}
