package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.LiveTvChannel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

private const val HEARTBEAT_MS = 30_000

/** R189 milestone 2 — Live TV playback: tune -> AVPlay -> heartbeat -> explicit stop (the R177
 *  "open-close lifecycle" every Ravilo client follows for Live TV, distinct from VOD's resume-position
 *  contract — no start position, no fixed duration, and the tuner/provider stream must be explicitly
 *  closed via `stopLiveTv(liveStreamId)` on exit). Channel Up/Down re-tunes within the same screen
 *  instance (classic remote zapping) rather than pushing a new screen per channel change. */
class LiveTvPlayerScreen(private val channels: List<LiveTvChannel>, private var index: Int) : Screen {
    private lateinit var container: HTMLElement
    private var heartbeatTimerId: Int? = null
    private var liveStreamId: String? = null
    private var closed = false
    private var tuning = false

    override fun mount(container: HTMLElement) {
        this.container = container
        container.child("div", "player-screen") {
            child("div", "player-title", "")
            child("div", "player-status", "")
        }
        tune()
        heartbeatTimerId = window.setInterval({ app.scope.launch { app.api.liveTvHeartbeat() } }, HEARTBEAT_MS)
    }

    private fun tune() {
        if (tuning) return
        tuning = true
        val channel = channels.getOrNull(index) ?: return
        setTitle("${channel.number} · ${channel.name}")
        setStatus("Tuning…")
        app.scope.launch {
            // Close whatever was open before tuning the next channel -- Live TV's provider-side stream
            // must be explicitly released (R177), unlike VOD where a new startPlayback call is independent.
            liveStreamId?.let { runCatching { app.api.stopLiveTv(it) } }
            runCatching { WebApisGlobal.avplay.stop(); WebApisGlobal.avplay.close() }
            runCatching { app.api.tuneLiveTv(channel.channelId, defaultCapabilities()) }
                .onSuccess { ticket ->
                    liveStreamId = ticket.liveStreamId
                    startAvPlay(ticket.hlsUrl)
                }
                .onFailure { setStatus("Couldn't tune this channel"); tuning = false }
        }
    }

    private fun startAvPlay(url: String) {
        runCatching {
            WebApisGlobal.avplay.open(url)
            WebApisGlobal.avplay.setDisplayRect(0, 0, 1920, 1080)
            WebApisGlobal.avplay.setListener(avPlayListener(
                onBufferingComplete = { setStatus(""); tuning = false },
                onBufferingStart = { setStatus("Buffering…") },
                onError = { setStatus("Stream error"); tuning = false },
            ))
            WebApisGlobal.avplay.prepareAsync(
                successCallback = { WebApisGlobal.avplay.play(); setStatus(""); tuning = false },
                errorCallback = { setStatus("Playback failed to start"); tuning = false },
            )
        }.onFailure { setStatus("AVPlay unavailable — is this running on a Tizen TV?"); tuning = false }
    }

    private fun setStatus(text: String) { (container.querySelector(".player-status") as? HTMLElement)?.textContent = text }
    private fun setTitle(text: String) { (container.querySelector(".player-title") as? HTMLElement)?.textContent = text }

    override fun onKey(ev: KeyboardEvent): Boolean {
        when (ev.key) {
            "Back", "Escape" -> { finish(); return true }
            "ChannelUp", "ArrowUp" -> { zap(1); return true }
            "ChannelDown", "ArrowDown" -> { zap(-1); return true }
        }
        return false
    }

    private fun zap(delta: Int) {
        if (channels.isEmpty()) return
        index = (index + delta + channels.size) % channels.size
        tune()
    }

    private fun finish() {
        if (closed) return
        closed = true
        heartbeatTimerId?.let { window.clearInterval(it) }
        app.scope.launch { liveStreamId?.let { runCatching { app.api.stopLiveTv(it) } } }
        runCatching { WebApisGlobal.avplay.stop(); WebApisGlobal.avplay.close() }
        app.back()
    }
}
