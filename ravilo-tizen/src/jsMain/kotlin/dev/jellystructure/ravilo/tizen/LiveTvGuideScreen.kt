package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.LiveTvChannel
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** R189 milestone 2 — Live TV channel guide: shown channels with current/next program (embedded on
 *  each `LiveTvChannel`, no separate guide fetch needed for this — matches the R177 "addendum C"
 *  behaviour every other Ravilo client relies on). Selecting a channel tunes it via `LiveTvPlayerScreen`.
 *  No nav bar — reached from Home's "On now" row / entry point, Back returns to Home. */
class LiveTvGuideScreen : Screen {
    private lateinit var container: HTMLElement
    private var channels: List<LiveTvChannel> = emptyList()
    private var index = 0

    override fun mount(container: HTMLElement) {
        this.container = container
        container.child("div", "browse-screen") { child("div", "loading", "Loading…") }
        app.scope.launch {
            runCatching { app.api.getLiveTvChannels() }
                .onSuccess { channels = it.filter { c -> c.shown && !c.unavailable }.sortedBy { c -> c.number }; render() }
                .onFailure { renderError() }
        }
    }

    private fun render() {
        container.clear()
        val root = container.child("div", "browse-screen") { child("h1", "menu-title", "Live TV") }
        val list = root.child("div", "guide-list")
        for ((i, ch) in channels.withIndex()) {
            list.child("div", "guide-row") {
                setAttribute("data-index", i.toString())
                val logoUrl = ch.logoUrl
                if (logoUrl != null) child("img", "guide-logo") { setAttribute("src", logoUrl) }
                child("div", "guide-info") {
                    child("div", "guide-channel-name", "${ch.number} · ${ch.name}")
                    ch.currentProgram?.let { p -> child("div", "guide-now", "Now: ${p.name}") }
                    ch.nextProgram?.let { p -> child("div", "guide-next", "Next: ${p.name}") }
                }
            }
        }
        highlight()
    }

    private fun highlight() {
        val els = container.querySelectorAll(".guide-row")
        for (i in 0 until els.length) {
            val el = els.item(i) as HTMLElement
            val focused = i == index
            el.classList.toggle("focused", focused)
            if (focused) el.scrollIntoView()
        }
    }

    private fun renderError() {
        container.clear()
        container.child("div", "browse-screen") { child("p", "error", "Live TV is unavailable right now.") }
    }

    override fun onKey(ev: KeyboardEvent): Boolean {
        if (channels.isEmpty()) return false
        when (ev.key) {
            "ArrowDown" -> { if (index < channels.size - 1) { index++; highlight() }; return true }
            "ArrowUp" -> { if (index > 0) { index--; highlight() }; return true }
            "Enter" -> { app.show(LiveTvPlayerScreen(channels, index)); return true }
        }
        return false
    }
}
