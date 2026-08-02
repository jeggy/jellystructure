package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.StreamTicket
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

private const val SKIP_LEAD_MS = 0L
private const val AUTO_ADVANCE_AT_MS = 20_000L // R182-style: offer "Next episode" once credits-start is this close

/**
 * R189 FR-RV-TIZEN1-4 (milestone 2 upgrade) — full playback: AVPlay video, an auto-hiding control bar
 * (Play/Pause, ±10s, Tracks, Next episode), a track picker (audio/subtitle, best-effort index mapping —
 * see `TizenPlatform.kt`), and a Skip Intro / Skip Credits pill driven by the item's `TvSegmentMarkers`
 * (R182, same feature every other Ravilo client has). Progress reports every 10s via the same
 * `reportProgress` contract every other client uses; on stream completion, marks watched and — for a
 * series episode with a known next episode — auto-starts it via `App.replaceTop` (no extra back-stack
 * depth per episode).
 *
 * Unverified against real Tizen hardware — see `TizenPlatform.kt`'s doc comment.
 */
class PlayerScreen(private val ctx: PlaybackContext, private val ticket: StreamTicket) : Screen {
    private lateinit var container: HTMLElement
    private var reportTimerId: Int? = null
    private var hideControlsTimerId: Int? = null
    private var lastPositionMs: Long = ticket.startPositionMs
    private var durationMs: Long = 0
    private var closed = false
    private var controlsVisible = true
    private var tracksOpen = false
    private var isPlaying = true
    private var skipOffered = false
    private var autoAdvanceOffered = false

    // Controls: 0=Play/Pause 1=-10s 2=+10s 3=Tracks [4=Next episode]
    private var controlIndex = 0
    private val controls: List<String> get() = listOfNotNull("PlayPause", "Back10", "Fwd10", "Tracks", "Next".takeIf { ctx.nextEpisode != null })

    // Track picker: region 0 = audio list, region 1 = subtitle list
    private var trackRegion = 0
    private var trackIndex = 0

    override fun mount(container: HTMLElement) {
        this.container = container
        container.child("div", "player-screen") {
            child("div", "player-title", ctx.displayTitle)
            child("div", "player-status", "Loading…")
            child("div", "skip-pill", "") { setAttribute("style", "display:none") }
            child("div", "controls-bar")
            child("div", "tracks-overlay") { setAttribute("style", "display:none") }
        }
        startAvPlay()
        startProgressReporting()
        renderControls()
        scheduleAutoHide()
    }

    private fun startAvPlay() {
        val url = ticket.hlsUrl ?: "${ticket.jellyfinBaseUrl}/Videos/${ticket.itemId}/stream.${ticket.container}?static=true&api_key=${ticket.accessToken}"
        runCatching {
            WebApisGlobal.avplay.open(url)
            WebApisGlobal.avplay.setDisplayRect(0, 0, 1920, 1080)
            WebApisGlobal.avplay.setListener(avPlayListener(
                onBufferingComplete = { setStatus("") },
                onBufferingStart = { setStatus("Buffering…") },
                onCurrentPlayTime = { ms -> onTick(ms.toLong()) },
                onStreamCompleted = { onCompleted() },
                onError = { onCompleted() },
            ))
            WebApisGlobal.avplay.prepareAsync(
                successCallback = {
                    durationMs = runCatching { WebApisGlobal.avplay.getDuration().toLong() }.getOrDefault(0)
                    if (ticket.startPositionMs > 0) WebApisGlobal.avplay.seekTo(ticket.startPositionMs.toInt())
                    WebApisGlobal.avplay.play()
                    setStatus("")
                },
                errorCallback = { setStatus("Playback failed to start") },
            )
        }.onFailure { setStatus("AVPlay unavailable — is this running on a Tizen TV?") }
    }

    private fun onTick(ms: Long) {
        lastPositionMs = ms
        updateSkipPill(ms)
        maybeOfferAutoAdvance(ms)
    }

    private fun setStatus(text: String) {
        (container.querySelector(".player-status") as? HTMLElement)?.textContent = text
    }

    // ─── Skip Intro / Skip Credits (R182 parity) ───────────────────────────────

    private fun updateSkipPill(ms: Long) {
        val pill = container.querySelector(".skip-pill") as? HTMLElement ?: return
        val seg = ctx.segments
        val inIntro = seg.introStartMs != null && seg.introEndMs != null && ms in seg.introStartMs!!..seg.introEndMs!!
        val inCredits = seg.creditsStartMs != null && ms >= seg.creditsStartMs!!
        when {
            inIntro -> { pill.textContent = "Skip Intro"; pill.setAttribute("style", "display:block"); pill.setAttribute("data-skip-to", seg.introEndMs.toString()) }
            inCredits && ctx.nextEpisode == null -> { pill.textContent = "Skip Credits"; pill.setAttribute("style", "display:block"); pill.setAttribute("data-skip-to", durationMs.toString()) }
            else -> pill.setAttribute("style", "display:none")
        }
    }

    private fun skipPillActive(): Boolean =
        (container.querySelector(".skip-pill") as? HTMLElement)?.getAttribute("style")?.contains("block") == true

    private fun activateSkipPill() {
        val pill = container.querySelector(".skip-pill") as? HTMLElement ?: return
        val to = pill.getAttribute("data-skip-to")?.toIntOrNull() ?: return
        runCatching { WebApisGlobal.avplay.seekTo(to) }
        pill.setAttribute("style", "display:none")
    }

    // ─── Auto-advance (series) ──────────────────────────────────────────────────

    private fun maybeOfferAutoAdvance(ms: Long) {
        val next = ctx.nextEpisode ?: return
        if (autoAdvanceOffered) return
        val remaining = durationMs - ms
        if (durationMs > 0 && remaining in 0..AUTO_ADVANCE_AT_MS) {
            autoAdvanceOffered = true
            showNextUpCard(next.title)
        }
    }

    private fun showNextUpCard(title: String) {
        val pill = container.querySelector(".skip-pill") as? HTMLElement ?: return
        pill.textContent = "Next: $title"
        pill.setAttribute("style", "display:block")
        pill.setAttribute("data-next", "1")
    }

    private fun onCompleted() {
        if (closed) return
        app.scope.launch { runCatching { app.api.markPlayed(ctx.itemId, true) } }
        val next = ctx.nextEpisode
        if (next != null) {
            advanceTo(next.id, "${ctx.parentCard.title} — ${next.episodeNumber}. ${next.title}")
        } else {
            finish()
        }
    }

    private fun advanceTo(itemId: String, title: String) {
        app.scope.launch {
            runCatching { app.api.startPlayback(itemId, defaultCapabilities()) }
                .onSuccess { newTicket ->
                    runCatching { WebApisGlobal.avplay.stop(); WebApisGlobal.avplay.close() }
                    reportTimerId?.let { window.clearInterval(it) }
                    // R189: next-episode lookup for a *further* auto-advance chain is out of milestone-2
                    // scope (would need re-fetching the series detail); a binge stops offering auto-advance
                    // after one hop, same UX otherwise.
                    app.replaceTop(PlayerScreen(ctx.copy(itemId = itemId, displayTitle = title, nextEpisode = null), newTicket))
                }
                .onFailure { finish() }
        }
    }

    // ─── Controls bar ────────────────────────────────────────────────────────

    private fun renderControls() {
        val bar = container.querySelector(".controls-bar") as? HTMLElement ?: return
        bar.clear()
        for ((i, c) in controls.withIndex()) {
            bar.child("div", "control-btn") {
                setAttribute("data-control-index", i.toString())
                textContent = when (c) {
                    "PlayPause" -> if (isPlaying) "Pause" else "Play"
                    "Back10" -> "«10s"
                    "Fwd10" -> "10s»"
                    "Tracks" -> "Audio & Subtitles"
                    "Next" -> "Next episode"
                    else -> c
                }
            }
        }
        highlightControls()
    }

    private fun highlightControls() {
        val els = container.querySelectorAll(".control-btn")
        for (i in 0 until els.length) (els.item(i) as HTMLElement).classList.toggle("focused", i == controlIndex)
    }

    private fun showControls() {
        controlsVisible = true
        (container.querySelector(".controls-bar") as? HTMLElement)?.setAttribute("style", "display:flex")
        (container.querySelector(".player-title") as? HTMLElement)?.setAttribute("style", "display:block")
        scheduleAutoHide()
    }

    private fun hideControls() {
        if (tracksOpen) return
        controlsVisible = false
        (container.querySelector(".controls-bar") as? HTMLElement)?.setAttribute("style", "display:none")
        (container.querySelector(".player-title") as? HTMLElement)?.setAttribute("style", "display:none")
    }

    private fun scheduleAutoHide() {
        hideControlsTimerId?.let { window.clearTimeout(it) }
        hideControlsTimerId = window.setTimeout({ hideControls() }, 5000)
    }

    // ─── Track picker overlay ───────────────────────────────────────────────────

    private fun openTracks() {
        tracksOpen = true
        val overlay = container.querySelector(".tracks-overlay") as? HTMLElement ?: return
        overlay.clear()
        overlay.setAttribute("style", "display:block")
        overlay.child("h2", "tracks-title", "Audio & Subtitles")
        overlay.child("h3", "tracks-subtitle", "Audio")
        val audioList = overlay.child("div", "tracks-list")
        for ((i, a) in ticket.audio.withIndex()) {
            audioList.child("div", "track-row") {
                setAttribute("data-audio-index", i.toString())
                textContent = a.label ?: a.language ?: "Track ${a.index}"
            }
        }
        overlay.child("h3", "tracks-subtitle", "Subtitles")
        val subList = overlay.child("div", "tracks-list")
        subList.child("div", "track-row") { setAttribute("data-sub-index", "-1"); textContent = "Off" }
        for ((i, s) in ticket.subtitles.withIndex()) {
            subList.child("div", "track-row") {
                setAttribute("data-sub-index", i.toString())
                textContent = s.label ?: s.language ?: "Track ${s.index}"
            }
        }
        trackRegion = 0
        trackIndex = 0
        highlightTracks()
    }

    private fun closeTracks() {
        tracksOpen = false
        (container.querySelector(".tracks-overlay") as? HTMLElement)?.setAttribute("style", "display:none")
        scheduleAutoHide()
    }

    private fun highlightTracks() {
        val audioEls = container.querySelectorAll("[data-audio-index]")
        for (i in 0 until audioEls.length) (audioEls.item(i) as HTMLElement).classList.toggle("focused", trackRegion == 0 && i == trackIndex)
        val subEls = container.querySelectorAll("[data-sub-index]")
        for (i in 0 until subEls.length) (subEls.item(i) as HTMLElement).classList.toggle("focused", trackRegion == 1 && i == trackIndex)
    }

    private fun activateTrack() {
        if (trackRegion == 0) {
            val audio = ticket.audio.getOrNull(trackIndex) ?: return
            runCatching { WebApisGlobal.avplay.setSelectTrack("AUDIO", audio.index) }
        } else {
            val subIndex = trackIndex - 1 // row 0 is "Off"
            if (subIndex < 0) return // Milestone 2: no explicit "disable subtitles" AVPlay call wired yet.
            val sub = ticket.subtitles.getOrNull(subIndex) ?: return
            runCatching { WebApisGlobal.avplay.setSelectTrack("TEXT", sub.index) }
        }
    }

    // ─── Input ──────────────────────────────────────────────────────────────────

    override fun onKey(ev: KeyboardEvent): Boolean {
        if (tracksOpen) return onTracksKey(ev)
        when (ev.key) {
            "Back", "Escape" -> { finish(); return true }
            "MediaPlayPause", " " -> { togglePlayPause(); return true }
            "Enter" -> {
                if (!controlsVisible) { showControls(); return true }
                if (skipPillActive()) {
                    val pill = container.querySelector(".skip-pill") as? HTMLElement
                    if (pill?.getAttribute("data-next") == "1") { ctx.nextEpisode?.let { advanceTo(it.id, "${ctx.parentCard.title} — ${it.episodeNumber}. ${it.title}") }; return true }
                    activateSkipPill(); return true
                }
                activateControl(); return true
            }
            "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown" -> {
                if (!controlsVisible) { showControls(); return true }
                when (ev.key) {
                    "ArrowRight" -> if (controlIndex < controls.size - 1) controlIndex++
                    "ArrowLeft" -> if (controlIndex > 0) controlIndex--
                }
                highlightControls()
                scheduleAutoHide()
                return true
            }
        }
        return false
    }

    private fun onTracksKey(ev: KeyboardEvent): Boolean {
        when (ev.key) {
            "ArrowDown" -> {
                if (trackRegion == 0 && trackIndex >= ticket.audio.size - 1) { trackRegion = 1; trackIndex = 0 }
                else if (trackRegion == 0) trackIndex++
                else if (trackIndex < ticket.subtitles.size) trackIndex++
                highlightTracks(); return true
            }
            "ArrowUp" -> {
                if (trackRegion == 1 && trackIndex == 0) { trackRegion = 0; trackIndex = (ticket.audio.size - 1).coerceAtLeast(0) }
                else if (trackIndex > 0) trackIndex--
                highlightTracks(); return true
            }
            "Enter" -> { activateTrack(); return true }
            "Back", "Escape" -> { closeTracks(); return true }
        }
        return false
    }

    private fun activateControl() {
        when (controls.getOrNull(controlIndex)) {
            "PlayPause" -> togglePlayPause()
            "Back10" -> seekRelative(-10_000)
            "Fwd10" -> seekRelative(10_000)
            "Tracks" -> openTracks()
            "Next" -> ctx.nextEpisode?.let { advanceTo(it.id, "${ctx.parentCard.title} — ${it.episodeNumber}. ${it.title}") }
        }
    }

    private fun togglePlayPause() {
        runCatching {
            if (WebApisGlobal.avplay.getState() == "PLAYING") { WebApisGlobal.avplay.pause(); isPlaying = false }
            else { WebApisGlobal.avplay.play(); isPlaying = true }
        }
        renderControls()
    }

    private fun seekRelative(deltaMs: Int) {
        val target = (lastPositionMs + deltaMs).coerceAtLeast(0).toInt()
        runCatching { WebApisGlobal.avplay.seekTo(target) }
    }

    private fun startProgressReporting() {
        reportTimerId = window.setInterval({
            app.scope.launch { runCatching { app.api.reportProgress(ctx.itemId, lastPositionMs, isPaused = !isPlaying) } }
        }, 10_000)
    }

    private fun finish() {
        if (closed) return
        closed = true
        reportTimerId?.let { window.clearInterval(it) }
        hideControlsTimerId?.let { window.clearTimeout(it) }
        app.scope.launch { runCatching { app.api.stopPlayback(ctx.itemId, lastPositionMs) } }
        runCatching { WebApisGlobal.avplay.stop(); WebApisGlobal.avplay.close() }
        app.back()
    }
}
