package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.StreamTicket
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/**
 * R189 FR-RV-TIZEN1-4 — plays a [StreamTicket] via Samsung's AVPlay (`webapis.avplay`), the native
 * video pipeline this era of Tizen needs for real HLS playback (see phase spec). Progress is reported
 * on the same cadence/contract every other Ravilo client uses (`TvApiClient.reportProgress`).
 *
 * Unverified against real hardware — see `TizenPlatform.kt`'s doc comment; this is Milestone 1's most
 * likely spot to need on-device correction (AVPlay's exact `open`/`prepareAsync` sequencing, display
 * rect handling, and `oncurrentplaytime` cadence are all reasoned from public docs, not tested here).
 */
class PlayerScreen(private val card: MediaCard, private val ticket: StreamTicket) : Screen {
    private var reportTimerId: Int? = null
    private var lastPositionMs: Long = ticket.startPositionMs
    private var closed = false

    override fun mount(container: HTMLElement) {
        container.child("div", "player-screen") {
            child("div", "player-title", card.title)
            child("div", "player-status", "Loading…")
        }

        val url = ticket.hlsUrl ?: run {
            // Direct-play fallback: Jellyfin's direct-stream URL, same shape ravilo-ui builds from
            // jellyfinBaseUrl + itemId + accessToken for a non-HLS container.
            "${ticket.jellyfinBaseUrl}/Videos/${ticket.itemId}/stream.${ticket.container}?static=true&api_key=${ticket.accessToken}"
        }

        runCatching {
            WebApisGlobal.avplay.open(url)
            WebApisGlobal.avplay.setDisplayRect(0, 0, 1920, 1080)
            WebApisGlobal.avplay.setListener(avPlayListener(
                onBufferingComplete = { setStatus(container, "") },
                onBufferingStart = { setStatus(container, "Buffering…") },
                onCurrentPlayTime = { ms -> lastPositionMs = ms.toLong() },
                onStreamCompleted = { finish() },
                onError = { finish() },
            ))
            WebApisGlobal.avplay.prepareAsync(
                successCallback = {
                    if (ticket.startPositionMs > 0) {
                        WebApisGlobal.avplay.seekTo(ticket.startPositionMs.toInt())
                    }
                    WebApisGlobal.avplay.play()
                    setStatus(container, "")
                },
                errorCallback = { setStatus(container, "Playback failed to start") },
            )
        }.onFailure {
            setStatus(container, "AVPlay unavailable — is this running on a Tizen TV?")
        }

        startProgressReporting()
    }

    private fun setStatus(container: HTMLElement, text: String) {
        val status = container.querySelector(".player-status") as? HTMLElement ?: return
        status.textContent = text
    }

    private fun startProgressReporting() {
        reportTimerId = kotlinx.browser.window.setInterval({
            app.scope.launch {
                runCatching { app.api.reportProgress(card.id, lastPositionMs) }
            }
        }, 10_000)
    }

    override fun onKey(ev: KeyboardEvent): Boolean {
        when (ev.key) {
            "MediaPlayPause", " " -> { togglePlayPause(); return true }
            "Back", "Escape" -> { finish(); return true }
        }
        return false
    }

    private fun togglePlayPause() {
        runCatching {
            if (WebApisGlobal.avplay.getState() == "PLAYING") WebApisGlobal.avplay.pause() else WebApisGlobal.avplay.play()
        }
    }

    private fun finish() {
        if (closed) return
        closed = true
        reportTimerId?.let { kotlinx.browser.window.clearInterval(it) }
        app.scope.launch { runCatching { app.api.stopPlayback(card.id, lastPositionMs) } }
        runCatching { WebApisGlobal.avplay.stop(); WebApisGlobal.avplay.close() }
        app.back()
    }
}
