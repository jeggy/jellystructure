package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.Skin
import dev.jellystructure.shared.tv.TileShape
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** R189 milestone 2 — the on-device viewer-tweakable settings (`GET`/`PUT /api/tv/settings` via the
 *  shared client's `getConfig`/`putViewerSettings`) — skin, tile shape, autoplay, continue-progress
 *  bars. Left/Right cycles a row's value and saves immediately (write-through, same convention the
 *  admin frontend uses elsewhere in this project — no separate Save button). */
class SettingsScreen : Screen {
    private lateinit var container: HTMLElement
    private var config: RaviloConfig? = null
    private var index = 0

    private val skins = Skin.entries
    private val shapes = TileShape.entries
    private val rows = listOf("Skin", "Tile shape", "Show progress bars", "Autoplay next episode")

    override fun mount(container: HTMLElement) {
        this.container = container
        container.child("div", "menu-screen") { child("div", "loading", "Loading…") }
        app.scope.launch {
            runCatching { app.api.getConfig() }.onSuccess { config = it; render() }.onFailure { renderError() }
        }
    }

    private fun render() {
        val cfg = config ?: return
        container.clear()
        val root = container.child("div", "menu-screen") { child("h1", "menu-title", "Settings") }
        val list = root.child("div", "menu-list")
        for ((i, label) in rows.withIndex()) {
            list.child("div", "menu-row settings-row") {
                setAttribute("data-row-index", i.toString())
                child("span", "settings-label", label)
                child("span", "settings-value", valueLabel(i, cfg))
            }
        }
        highlight()
    }

    private fun valueLabel(i: Int, cfg: RaviloConfig): String = when (i) {
        0 -> cfg.effectiveSkin().name
        1 -> cfg.tileShape.name
        2 -> if (cfg.showContinueProgress) "On" else "Off"
        3 -> if (cfg.autoplayNext) "On" else "Off"
        else -> ""
    }

    private fun highlight() {
        val els = container.querySelectorAll(".settings-row")
        for (i in 0 until els.length) (els.item(i) as HTMLElement).classList.toggle("focused", i == index)
    }

    private fun renderError() {
        container.clear()
        container.child("div", "menu-screen") { child("p", "error", "Couldn't load settings.") }
    }

    override fun onKey(ev: KeyboardEvent): Boolean {
        val cfg = config ?: return false
        when (ev.key) {
            "ArrowDown" -> { if (index < rows.size - 1) { index++; highlight() }; return true }
            "ArrowUp" -> { if (index > 0) { index--; highlight() }; return true }
            "ArrowRight" -> { cycle(cfg, 1); return true }
            "ArrowLeft" -> { cycle(cfg, -1); return true }
        }
        return false
    }

    private fun cycle(cfg: RaviloConfig, delta: Int) {
        when (index) {
            0 -> {
                val next = skins[(skins.indexOf(cfg.effectiveSkin()) + delta + skins.size) % skins.size]
                save(skin = next)
            }
            1 -> {
                val next = shapes[(shapes.indexOf(cfg.tileShape) + delta + shapes.size) % shapes.size]
                save(tileShape = next)
            }
            2 -> save(showContinueProgress = !cfg.showContinueProgress)
            3 -> save(autoplayNext = !cfg.autoplayNext)
        }
    }

    private fun save(
        skin: Skin? = null,
        tileShape: TileShape? = null,
        showContinueProgress: Boolean? = null,
        autoplayNext: Boolean? = null,
    ) {
        app.scope.launch {
            runCatching { app.api.putViewerSettings(skin = skin, tileShape = tileShape, showContinueProgress = showContinueProgress, autoplayNext = autoplayNext) }
            runCatching { app.api.getConfig() }.onSuccess { config = it; render() }
        }
    }
}
