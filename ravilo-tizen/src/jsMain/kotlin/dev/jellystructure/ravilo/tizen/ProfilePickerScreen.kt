package dev.jellystructure.ravilo.tizen

import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** R189 milestone 2 — "Who's watching?" profile grid, shown on cold start when 2+ local sessions are
 *  cached, or reached via the profile menu's "Switch profile". Mirrors `ravilo-ui`'s
 *  `ProfilePickerScreen` layout/behaviour (profile circles + Add user + Settings). */
class ProfilePickerScreen : Screen {
    private var index = 0
    private lateinit var tiles: List<HTMLElement>
    private var sessions: List<LocalSession> = emptyList()

    override fun mount(container: HTMLElement) {
        sessions = MultiTokenStore.getAll()
        val root = container.child("div", "picker-screen") {
            child("h1", "picker-title", "Who's watching?")
        }
        val row = root.child("div", "picker-row")
        val tileEls = mutableListOf<HTMLElement>()
        for (s in sessions) {
            val tile = row.child("div", "picker-tile") {
                child("div", "picker-circle", s.displayName.take(1).uppercase())
                child("div", "picker-name", s.displayName)
            }
            tileEls += tile
        }
        val addTile = row.child("div", "picker-tile") {
            child("div", "picker-circle picker-add", "+")
            child("div", "picker-name", "Add user")
        }
        tileEls += addTile
        tiles = tileEls
        highlight()
    }

    private fun highlight() {
        tiles.forEachIndexed { i, el -> el.classList.toggle("focused", i == index) }
    }

    override fun onKey(ev: KeyboardEvent): Boolean {
        when (ev.key) {
            "ArrowRight" -> { if (index < tiles.size - 1) { index++; highlight() }; return true }
            "ArrowLeft" -> { if (index > 0) { index--; highlight() }; return true }
            "Enter" -> {
                if (index < sessions.size) {
                    MultiTokenStore.setActive(sessions[index].userId)
                    app.show(HomeScreen(), replaceStack = true)
                } else {
                    app.show(LoginScreen())
                }
                return true
            }
        }
        return false
    }
}
