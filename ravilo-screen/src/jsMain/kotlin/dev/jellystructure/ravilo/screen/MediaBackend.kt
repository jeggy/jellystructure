package dev.jellystructure.ravilo.screen

import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.StreamTicket
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event

/**
 * R264 (dev review item 6 / open question 1) — the one seam this whole phase turns on: does the target
 * TV's browser layer draw on top of the native video plane, or does the receiver have to hand every bit
 * of on-screen text (subtitles, an OSD) to the native player itself? [AvPlayBackend] is Tizen's answer
 * (`webapis.avplay`, required on Tizen builds whose `<video>` has no real HLS/DASH); [HtmlVideoBackend]
 * is everyone else's (a plain `<video>` + hls.js, the same path the Chromecast receiver's browser
 * environment would use). Both report state through the same shape so [ScreenPlayer] never has to know
 * which one it's driving.
 */
interface MediaBackend {
    fun open(hlsUrl: String, startPositionMs: Long)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun close()
    /** "IDLE" | "READY" | "PLAYING" | "PAUSED" | "NONE" — AVPlay's own vocabulary; the `<video>` backend
     *  maps HTMLMediaElement's state onto it so both report identically. */
    fun state(): String
    fun positionMs(): Long
    fun durationMs(): Long
    fun setListener(listener: MediaBackendListener)
    /** Real capability probe — an old Tizen stick reports true HEVC/HDR support dishonestly less often
     *  than a browser's `canPlayType` does, but both are asked the same way (R245's own FR-R245-13 note). */
    fun capabilities(): ClientCapabilities
    /** Container-track selection (index aligns with [StreamTicket.audio]/`.subtitles`, best-effort — see
     *  [AvPlayObject.getTotalTrackInfo]'s own doc on that assumption). No-op default: rendering an
     *  externally-sideloaded subtitle track as an HTML overlay is R264's still-open question 1, so only
     *  container-embedded selection is wired here for now. */
    fun selectAudioTrack(index: Int) {}
    fun selectSubtitleTrack(index: Int) {}
}

interface MediaBackendListener {
    fun onBufferingStart() {}
    fun onBufferingComplete() {}
    fun onPlaying() {}
    fun onPaused() {}
    fun onStreamCompleted() {}
    fun onError(detail: String) {}
}

/** Tizen — required wherever `<video>` has no real HLS/DASH (webapis.avplay renders on its own hardware
 *  plane, behind the DOM; R264 open question 1 is whether HTML can still draw on top of it). */
class AvPlayBackend : MediaBackend {
    private var listener: MediaBackendListener? = null

    override fun open(hlsUrl: String, startPositionMs: Long) {
        WebApisGlobal.avplay.open(hlsUrl)
        WebApisGlobal.avplay.setDisplayRect(0, 0, window.screen.width, window.screen.height)
        WebApisGlobal.avplay.setDisplayMethod("PLAYER_DISPLAY_MODE_FULL_SCREEN")
        WebApisGlobal.avplay.setListener(avPlayListener(
            onBufferingStart = { listener?.onBufferingStart() },
            onBufferingComplete = { listener?.onBufferingComplete() },
            onStreamCompleted = { listener?.onStreamCompleted() },
            onError = { e -> listener?.onError(e.toString()) },
        ))
        WebApisGlobal.avplay.prepareAsync(
            { if (startPositionMs > 0) WebApisGlobal.avplay.seekTo(startPositionMs.toInt()); WebApisGlobal.avplay.play(); listener?.onPlaying() },
            { e -> listener?.onError(e.toString()) },
        )
    }

    override fun play() { runCatching { WebApisGlobal.avplay.play() }; listener?.onPlaying() }
    override fun pause() { runCatching { WebApisGlobal.avplay.pause() }; listener?.onPaused() }
    override fun seekTo(ms: Long) { runCatching { WebApisGlobal.avplay.seekTo(ms.toInt()) } }
    override fun close() { runCatching { WebApisGlobal.avplay.close() } }
    override fun state(): String = runCatching { WebApisGlobal.avplay.getState() }.getOrDefault("NONE")
    override fun positionMs(): Long = runCatching { WebApisGlobal.avplay.getCurrentTime().toLong() }.getOrDefault(0L)
    override fun durationMs(): Long = runCatching { WebApisGlobal.avplay.getDuration().toLong() }.getOrDefault(0L)
    override fun setListener(listener: MediaBackendListener) { this.listener = listener }

    override fun capabilities(): ClientCapabilities = ClientCapabilities(
        containers = listOf("mp4", "ts"),
        videoCodecs = listOf("h264", "hevc"), // AVPlay's own getTotalTrackInfo/canPlay probes are per-track, not a single yes/no gate
        audioCodecs = listOf("aac", "ac3", "eac3"),
        maxAudioChannels = 6,
        hlsOnly = true,
        linkKind = "unknown",
    )

    override fun selectAudioTrack(index: Int) { runCatching { WebApisGlobal.avplay.setSelectTrack("AUDIO", index) } }
    override fun selectSubtitleTrack(index: Int) { runCatching { WebApisGlobal.avplay.setSelectTrack("TEXT", index) } }
}

/** webOS / any newer TV browser with real MSE-HLS support — the same `<video>` + hls.js path
 *  ravilo-web's own player uses (FR-235-9's vendored hls.js), self-hosted for the same "no CDN on a TV
 *  with an unreliable network path" reasoning. */
class HtmlVideoBackend(private val video: HTMLVideoElement) : MediaBackend {
    private var listener: MediaBackendListener? = null
    private var hls: dynamic = null

    override fun open(hlsUrl: String, startPositionMs: Long) {
        val canNative = (video.asDynamic().canPlayType("application/vnd.apple.mpegurl") as String).isNotBlank()
        if (canNative) {
            video.src = hlsUrl
        } else {
            val hlsCtor: dynamic = js("window.Hls")
            if (hlsCtor != null && (hlsCtor.isSupported() as Boolean)) {
                hls = hlsCtor.Constructor()
                hls.loadSource(hlsUrl)
                hls.attachMedia(video)
            } else {
                listener?.onError("no HLS support (neither native nor hls.js)")
                return
            }
        }
        video.addEventListener("waiting", { listener?.onBufferingStart() })
        video.addEventListener("playing", { listener?.onBufferingComplete(); listener?.onPlaying() })
        video.addEventListener("pause", { listener?.onPaused() })
        video.addEventListener("ended", { listener?.onStreamCompleted() })
        video.addEventListener("error", { _: Event -> listener?.onError((video.error.asDynamic()?.message as? String) ?: "video element error") })
        video.addEventListener("loadedmetadata", {
            if (startPositionMs > 0) video.currentTime = startPositionMs / 1000.0
            video.play()
        })
    }

    override fun play() { video.play() }
    override fun pause() { video.pause() }
    override fun seekTo(ms: Long) { video.currentTime = ms / 1000.0 }
    override fun close() { runCatching { hls?.destroy() }; video.pause(); video.removeAttribute("src"); video.load() }
    override fun state(): String = when {
        video.ended -> "NONE"
        video.paused -> "PAUSED"
        video.readyState < 3 -> "READY"
        else -> "PLAYING"
    }
    override fun positionMs(): Long = (video.currentTime * 1000).toLong()
    override fun durationMs(): Long = (video.duration.takeIf { !it.isNaN() } ?: 0.0).let { (it * 1000).toLong() }
    override fun setListener(listener: MediaBackendListener) { this.listener = listener }

    override fun selectAudioTrack(index: Int) {
        runCatching {
            val tracks: dynamic = video.asDynamic().audioTracks ?: return
            for (i in 0 until (tracks.length as Int)) tracks[i].enabled = (i == index)
        }
    }

    override fun selectSubtitleTrack(index: Int) {
        runCatching {
            val tracks: dynamic = video.asDynamic().textTracks ?: return
            for (i in 0 until (tracks.length as Int)) tracks[i].mode = if (i == index) "showing" else "disabled"
        }
    }

    override fun capabilities(): ClientCapabilities {
        fun can(type: String) = (video.asDynamic().canPlayType(type) as String).isNotBlank()
        return ClientCapabilities(
            containers = listOf("mp4", "ts"),
            videoCodecs = listOfNotNull("h264", "hevc".takeIf { can("video/mp4; codecs=\"hev1.1.6.L153.B0\"") }),
            audioCodecs = listOf("aac", "mp3"),
            maxAudioChannels = 6,
            hlsOnly = true,
            linkKind = "unknown",
        )
    }
}

/** R264 (FR-R264-8) — Tizen ships AVPlay; a webOS/other build falls back to `<video>`+hls.js. Detected
 *  once at boot, not per-play — a receiver's hardware doesn't change mid-session. */
fun detectMediaBackend(): MediaBackend {
    val hasAvPlay = runCatching { js("typeof webapis !== 'undefined' && !!webapis.avplay") as Boolean }.getOrDefault(false)
    return if (hasAvPlay) AvPlayBackend() else HtmlVideoBackend(document.getElementById("video") as HTMLVideoElement)
}
