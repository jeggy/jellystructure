package dev.jellystructure.ravilo.tizen

import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** R189 milestone 2 — the persistent top nav bar (Home/Movies/Series/Search/avatar), shared by every
 *  top-level screen. Deliberately NOT a Discover tab — that whole tab (Upcoming calendar + Seerr
 *  Request) is explicitly out of scope for this client.
 *
 *  Focus model: each top-level screen owns two focus "regions" — the nav bar (this component) and its
 *  own content below. `NavBar.onKey` is tried first while the nav bar has focus; ArrowDown hands focus
 *  to the screen's content (the screen's own `contentFocus()` callback), and the content's own key
 *  handler hands focus back up via `focusNav()` on ArrowUp from its first row. This explicit two-region
 *  handoff is deliberate — Ravilo's own (Compose) LoginScreen shipped a bug this same session where a
 *  focused text field silently swallowed Up/Down with no defined handoff target; splitting "nav" vs
 *  "content" into two explicit regions with an explicit contract avoids that class of dead end here. */
class NavBar(
    private val active: NavTab,
    private val onNavigate: (NavTab) -> Unit,
    private val onSearch: () -> Unit,
    private val onProfile: () -> Unit,
) {
    enum class NavTab { HOME, MOVIES, SERIES }

    private var focused = true
    private var index = 0 // 0=Home 1=Movies 2=Series 3=Search 4=Avatar
    private lateinit var items: List<HTMLElement>

    fun mount(container: HTMLElement) {
        val bar = container.child("div", "navbar")
        val brand = bar.child("div", "nav-brand", "Ravilo")
        val homeBtn = bar.child("div", "nav-item", "Home")
        val moviesBtn = bar.child("div", "nav-item", "Movies")
        val seriesBtn = bar.child("div", "nav-item", "Series")
        val searchBtn = bar.child("div", "nav-item nav-icon", "Search")
        val avatarBtn = bar.child("div", "nav-avatar", "")
        items = listOf(homeBtn, moviesBtn, seriesBtn, searchBtn, avatarBtn)
        index = when (active) { NavTab.HOME -> 0; NavTab.MOVIES -> 1; NavTab.SERIES -> 2 }
        updateActiveLabel(homeBtn, moviesBtn, seriesBtn)
        highlight()
    }

    private fun updateActiveLabel(vararg tabs: HTMLElement) {
        tabs.forEachIndexed { i, el -> el.classList.toggle("active", i == index) }
    }

    fun focusNav() {
        focused = true
        highlight()
    }

    fun hasFocus() = focused

    private fun highlight() {
        items.forEachIndexed { i, el -> el.classList.toggle("focused", focused && i == index) }
    }

    /** Returns true if the key was consumed by the nav bar. `onDown` is called when ArrowDown should
     *  hand focus to the screen's content (only meaningful while the nav bar has focus). */
    fun onKey(ev: KeyboardEvent, onDown: () -> Unit): Boolean {
        if (!focused) return false
        when (ev.key) {
            "ArrowRight" -> { if (index < items.size - 1) { index++; highlight() }; return true }
            "ArrowLeft" -> { if (index > 0) { index--; highlight() }; return true }
            "ArrowDown" -> { focused = false; highlight(); onDown(); return true }
            "Enter" -> {
                when (index) {
                    0 -> onNavigate(NavTab.HOME)
                    1 -> onNavigate(NavTab.MOVIES)
                    2 -> onNavigate(NavTab.SERIES)
                    3 -> onSearch()
                    4 -> onProfile()
                }
                return true
            }
        }
        return false
    }
}
