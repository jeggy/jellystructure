package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.Channel
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.Row
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** R189 milestone 2 — full Home: rotating hero banner, channel rail, content rows (with watched/
 *  progress/next-up badges), and a Live TV "On now" entry row when configured. Nav bar lives above all
 *  of this (shared `NavBar`); explicit Up/Down handoff between the nav bar and content, per `NavBar`'s
 *  doc comment. */
class HomeScreen : Screen {
    private lateinit var container: HTMLElement
    private lateinit var navBar: NavBar
    private var feed: HomeFeed? = null
    private var liveTvChannels: List<LiveTvChannel> = emptyList()
    private var heroTimer: Int? = null
    private var heroIndex = 0

    // Content focus, in render order: [on-now row] -> [channel rail] -> [one section per content row].
    private var sectionIndex = 0
    private var itemIndex = 0
    private val showOnNow get() = feed?.liveTvHome?.showOnNowRow == true && liveTvChannels.isNotEmpty()
    private val sections: List<List<Any>> get() {
        val f = feed ?: return emptyList()
        val out = mutableListOf<List<Any>>()
        if (showOnNow) out += liveTvChannels
        if (f.channels.isNotEmpty()) out += f.channels
        for (row in f.rows) if (row.items.isNotEmpty()) out += row.items
        return out
    }

    override fun mount(container: HTMLElement) {
        this.container = container
        container.child("div", "home-screen") {
            child("div", "loading", "Loading…")
        }
        app.scope.launch {
            runCatching { app.api.getHome() }
                .onSuccess { f ->
                    feed = f
                    if (f.liveTvHome?.showOnNowRow == true) {
                        runCatching { app.api.getLiveTvChannels() }.getOrNull()?.let {
                            liveTvChannels = it.filter { c -> c.shown && !c.unavailable }.sortedBy { c -> c.number }
                        }
                    }
                    render()
                }
                .onFailure { renderError() }
        }
    }

    private fun render() {
        val f = feed ?: return
        container.clear()
        navBar = NavBar(NavBar.NavTab.HOME, onNavigate = ::navigate, onSearch = { app.show(SearchScreen()) }, onProfile = { app.show(ProfileMenuScreen()) })
        navBar.mount(container)

        val body = container.child("div", "home-body")
        renderHero(body, f)
        var sectionIdx = 0
        if (showOnNow) { renderOnNow(body, liveTvChannels, sectionIdx); sectionIdx++ }
        if (f.channels.isNotEmpty()) { renderChannels(body, f.channels, sectionIdx); sectionIdx++ }
        for (row in f.rows) if (row.items.isNotEmpty()) { renderRow(body, row, sectionIdx); sectionIdx++ }

        highlightContent()
    }

    private fun navigate(tab: NavBar.NavTab) {
        when (tab) {
            NavBar.NavTab.HOME -> {}
            NavBar.NavTab.MOVIES -> app.show(BrowseScreen(dev.jellystructure.shared.tv.MediaKind.MOVIE))
            NavBar.NavTab.SERIES -> app.show(BrowseScreen(dev.jellystructure.shared.tv.MediaKind.SERIES))
        }
    }

    private fun renderHero(body: HTMLElement, f: HomeFeed) {
        if (f.heroes.isEmpty()) return
        val heroEl = body.child("div", "hero")
        fun paint() {
            val h = f.heroes[heroIndex % f.heroes.size]
            heroEl.clear()
            heroEl.child("img", "hero-backdrop") { setAttribute("src", h.backdropUrl) }
            heroEl.child("div", "hero-info") {
                h.taglineKicker?.let { child("div", "hero-kicker", it) }
                child("div", "hero-title", h.item.title)
                h.synopsis?.let { child("div", "hero-synopsis", it.take(180)) }
            }
        }
        paint()
        if (f.heroes.size > 1 && f.autoAdvanceSeconds > 0) {
            heroTimer = window.setInterval({ heroIndex++; paint() }, f.autoAdvanceSeconds * 1000)
        }
    }

    private fun renderOnNow(body: HTMLElement, channels: List<LiveTvChannel>, sectionIdx: Int) {
        body.child("div", "row-title", "On now")
        val row = body.child("div", "row")
        for ((i, ch) in channels.withIndex()) {
            row.child("div", "tile onnow-tile") {
                setAttribute("data-section", sectionIdx.toString())
                setAttribute("data-item", i.toString())
                val chLogo = ch.logoUrl
                if (chLogo != null) child("img", "poster onnow-logo") { setAttribute("src", chLogo) }
                else child("div", "poster onnow-logo-empty", ch.name)
                child("div", "tile-title", ch.currentProgram?.name ?: ch.name)
                child("div", "tile-sub", "${ch.number} · ${ch.name}")
            }
        }
    }

    private fun renderChannels(body: HTMLElement, channels: List<Channel>, sectionIdx: Int) {
        val row = body.child("div", "channel-rail")
        for ((i, ch) in channels.withIndex()) {
            row.child("div", "channel-button") {
                setAttribute("data-section", sectionIdx.toString())
                setAttribute("data-item", i.toString())
                val logoUrl = ch.logoUrl
                if (logoUrl != null) {
                    child("img", "channel-logo") { setAttribute("src", logoUrl) }
                } else {
                    child("div", "channel-text", ch.name)
                }
            }
        }
    }

    private fun renderRow(body: HTMLElement, row: Row, sectionIdx: Int) {
        body.child("div", "row-title", row.title)
        val rowEl = body.child("div", "row")
        for ((i, card) in row.items.withIndex()) {
            rowEl.child("div", "tile") {
                setAttribute("data-section", sectionIdx.toString())
                setAttribute("data-item", i.toString())
                child("img", "poster") { setAttribute("src", card.posterUrl ?: "") }
                if ((card.progressPct ?: 0f) > 0f) {
                    child("div", "progress-track") {
                        child("div", "progress-fill") { setAttribute("style", "width:${(card.progressPct ?: 0f) * 100}%") }
                    }
                }
                if (card.watched) child("div", "badge-watched", "✓")
                child("div", "tile-title", card.title)
                card.nextUpLabel?.let { child("div", "tile-sub", it) }
            }
        }
    }

    private fun highlightContent() {
        val tiles = container.querySelectorAll(".tile, .channel-button")
        for (i in 0 until tiles.length) {
            val t = tiles.item(i) as HTMLElement
            val s = t.getAttribute("data-section")?.toIntOrNull()
            val it = t.getAttribute("data-item")?.toIntOrNull()
            val focused = !navBar.hasFocus() && s == sectionIndex && it == itemIndex
            t.classList.toggle("focused", focused)
            if (focused) t.scrollIntoView()
        }
    }

    private fun renderError() {
        container.clear()
        container.child("div", "home-screen") {
            child("p", "error", "Couldn't load the home screen. Check the connection and reload.")
        }
    }

    override fun onKey(ev: KeyboardEvent): Boolean {
        if (feed == null) return false
        if (navBar.hasFocus()) {
            return navBar.onKey(ev) { sectionIndex = 0; itemIndex = 0; highlightContent() }
        }
        val secs = sections
        if (secs.isEmpty()) return false
        when (ev.key) {
            "ArrowRight" -> {
                val max = secs[sectionIndex].size - 1
                if (itemIndex < max) { itemIndex++; highlightContent(); return true }
            }
            "ArrowLeft" -> {
                if (itemIndex > 0) { itemIndex--; highlightContent(); return true }
            }
            "ArrowDown" -> {
                if (sectionIndex < secs.size - 1) {
                    sectionIndex++
                    itemIndex = itemIndex.coerceAtMost(secs[sectionIndex].size - 1)
                    highlightContent(); return true
                }
            }
            "ArrowUp" -> {
                if (sectionIndex > 0) {
                    sectionIndex--
                    itemIndex = itemIndex.coerceAtMost(secs[sectionIndex].size - 1)
                    highlightContent()
                } else {
                    navBar.focusNav()
                    highlightContent()
                }
                return true
            }
            "Enter" -> {
                when (val entry = secs[sectionIndex].getOrNull(itemIndex)) {
                    is MediaCard -> app.show(DetailScreen(entry))
                    is Channel -> app.show(ChannelScreen(entry))
                    is LiveTvChannel -> app.show(LiveTvPlayerScreen(liveTvChannels, itemIndex))
                }
                return true
            }
        }
        return false
    }
}
