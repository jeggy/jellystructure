package dev.jellystructure.ravilo.ui.seams

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSpanElement
import org.w3c.dom.HTMLStyleElement
import org.w3c.dom.events.Event

/**
 * R169 (FR-R169-3) — the DOM transport bar itself. Lazily built once, then just updated/shown/hidden
 * on every call (cheap — no element churn). Sits at a higher z-index than both the `<video>` (kept
 * promoted above the canvas by [RaviloPlayer.setChromeVisible] not being asked to demote it while this
 * bridge is active) and the canvas, so it's always visually on top; the scrim has
 * `pointer-events: none` so a tap on empty space still falls through to the canvas underneath — the
 * existing "tap toggles chrome" gesture (R157) keeps working unchanged.
 */
actual object PlayerChromeBridge {
    private var built = false
    private lateinit var scrim: HTMLDivElement
    private lateinit var bar: HTMLDivElement
    private lateinit var skipBackBtn: HTMLButtonElement
    private lateinit var playPauseBtn: HTMLButtonElement
    private lateinit var skipFwdBtn: HTMLButtonElement
    private lateinit var curTimeEl: HTMLSpanElement
    private lateinit var durTimeEl: HTMLSpanElement
    private lateinit var seekEl: HTMLInputElement
    private var seekDragging = false

    private fun ensureBuilt() {
        if (built) return
        built = true

        if (document.getElementById("rv-chrome-style") == null) {
            val style = document.createElement("style") as HTMLStyleElement
            style.id = "rv-chrome-style"
            style.textContent = """
                #rv-chrome-bar button {
                    background: rgba(255,255,255,.14); border: none; color: #fff;
                    width: 44px; height: 44px; border-radius: 50%; font-size: 18px;
                    cursor: pointer; flex: none;
                }
                #rv-chrome-bar button:hover { background: rgba(255,255,255,.24); }
                #rv-chrome-bar input[type=range] { flex: 1; accent-color: #7b6ef0; cursor: pointer; }
                #rv-chrome-bar span { color: #fff; font: 13px sans-serif; flex: none; min-width: 40px; text-align: center; }
            """.trimIndent()
            document.head?.appendChild(style)
        }

        scrim = (document.createElement("div") as HTMLDivElement).also { el ->
            el.id = "rv-chrome-scrim"
            el.style.cssText = "position:fixed;inset:0;z-index:3;pointer-events:none;display:none;" +
                "background:linear-gradient(rgba(0,0,0,.35),rgba(0,0,0,.15) 40%,rgba(0,0,0,.15) 60%,rgba(0,0,0,.55))"
            document.body?.appendChild(el)
        }

        bar = (document.createElement("div") as HTMLDivElement).also { el ->
            el.id = "rv-chrome-bar"
            el.style.cssText = "position:fixed;left:0;right:0;bottom:0;z-index:4;display:none;" +
                "align-items:center;gap:14px;padding:16px 32px;pointer-events:auto"
            document.body?.appendChild(el)
        }

        skipBackBtn = makeButton("⏪")
        playPauseBtn = makeButton("▶")
        skipFwdBtn = makeButton("⏩")
        curTimeEl = (document.createElement("span") as HTMLSpanElement)
        seekEl = (document.createElement("input") as HTMLInputElement).also { it.type = "range"; it.min = "0" }
        durTimeEl = (document.createElement("span") as HTMLSpanElement)

        bar.appendChild(skipBackBtn)
        bar.appendChild(playPauseBtn)
        bar.appendChild(skipFwdBtn)
        bar.appendChild(curTimeEl)
        bar.appendChild(seekEl)
        bar.appendChild(durTimeEl)
    }

    private fun makeButton(label: String): HTMLButtonElement =
        (document.createElement("button") as HTMLButtonElement).also { it.textContent = label }

    actual fun show(state: PlayerChromeState, actions: PlayerChromeActions) {
        ensureBuilt()
        scrim.style.display = "block"
        bar.style.display = "flex"

        playPauseBtn.textContent = if (state.isPlaying) "⏸" else "▶"
        playPauseBtn.onclick = { actions.onTogglePlay(); Unit }
        skipBackBtn.onclick = { actions.onSkipBack(); Unit }
        skipFwdBtn.onclick = { actions.onSkipForward(); Unit }

        curTimeEl.textContent = formatMs(state.positionMs)
        durTimeEl.textContent = formatMs(state.durationMs)
        seekEl.max = state.durationMs.coerceAtLeast(0L).toString()
        // Don't fight the user's own drag by resetting `value` mid-gesture (this fires on every poll tick).
        if (!seekDragging) seekEl.value = state.positionMs.toString()
        seekEl.oninput = { seekDragging = true; Unit }
        seekEl.onchange = { _: Event ->
            seekDragging = false
            actions.onSeek(seekEl.value.toLongOrNull() ?: state.positionMs)
            Unit
        }
    }

    actual fun hide() {
        if (!built) return
        scrim.style.display = "none"
        bar.style.display = "none"
    }

    private fun formatMs(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0L)
        val m = totalSec / 60
        val s = totalSec % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }
}
