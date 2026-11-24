package dev.jellystructure.ravilo.screen

import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.StreamTicket
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import kotlin.js.Date

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
    /**
     * [startVariantBps] is the ticket's own start rung (DEB-5024 FR-26): AVPlay is told to begin there, before it prepares.
     * [directPlay] (DEB-5503 FR-44) — [hlsUrl] is the file itself, not a master: no start rung is set, and the stream is seeked to
     * [startPositionMs] (1 000 ms for a start at 0) before it plays.
     */
    fun open(hlsUrl: String, startPositionMs: Long, startVariantBps: Long? = null, directPlay: Boolean = false)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun close()
    /** "IDLE" | "READY" | "PLAYING" | "PAUSED" | "NONE" — AVPlay's own vocabulary; the `<video>` backend
     *  maps HTMLMediaElement's state onto it so both report identically. */
    fun state(): String
    fun positionMs(): Long
    fun durationMs(): Long
    /** DEB-5025 (FR-28) — how far ahead of the playhead the player holds picture, for the seek bar's lighter part. AVPlay
     *  answers no position (only a fill percentage), so by default nothing is buffered beyond the playhead. */
    fun bufferedMs(): Long = positionMs()
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
    /**
     * DEB-5503 (FR-44) — on a direct play, make the [position]th of the file's audio tracks the one that plays, in place. [fileAudioTracks]
     * is how many container audio tracks the ticket lists; false when the player lists a different number (or cannot select), and the
     * caller restreams with that audio instead.
     */
    fun selectFileAudio(position: Int, fileAudioTracks: Int): Boolean = false
    /** DEB-5024 (FR-25) — the bitrate of the rung playing now, when the player answers a number (AVPlay's `CURRENT_BANDWIDTH`). */
    fun currentBandwidthBps(): Long? = null
    /** DEB-5024 (FR-27) — the bitrates of the master's rungs, when the player answers (AVPlay's `AVAILABLE_BITRATE`); empty = unknown. */
    fun availableBitratesBps(): List<Long> = emptyList()
    /** DEB-5024 (FR-32) — true where the player can hold a position and say nothing (AVPlay): the app reads the position itself. `<video>` fires `waiting`. */
    val watchesPosition: Boolean get() = false
}

interface MediaBackendListener {
    fun onBufferingStart() {}
    fun onBufferingComplete() {}
    fun onPlaying() {}
    fun onPaused() {}
    fun onStreamCompleted() {}
    fun onError(detail: String) {}
    /** DEB-5024 (FR-25) — the playhead moved: AVPlay's `oncurrentplaytime`, `<video>`'s `timeupdate`. */
    fun onPlayTime(ms: Long) {}
    /** DEB-5024 (FR-25) — a rung switch the player reported (`onevent`'s type and data). */
    fun onPlayerEvent(type: String, data: String?) {}
    /** DEB-5394 (FR-37) — `prepareAsync` succeeded [openToPreparedMs] after `open()` began (AVPlay only; `<video>` has no such step). */
    fun onPrepared(openToPreparedMs: Long) {}
    /** DEB-5024 (FR-25) — the viewer's own seek, pause or resume: the wait right after it is theirs, not a stall. */
    fun onViewerAction() {}
}

/**
 * DEB-4866 (DEB-4654 FR-9) — our own encoder's ticket carries a server-relative `hls_url` (`/api/tv/stream/<id>/master.m3u8`);
 * Jellyfin's is absolute. The widget is not served from the backend, so a bare path has no origin to resolve against and
 * AVPlay answers `PLAYER_ERROR_INVALID_URI` at `prepareAsync()` (hls.js and a `<video>` would resolve it against the
 * widget's own origin). An absolute URL passes through unchanged.
 */
fun resolveStreamUrl(serverUrl: String, url: String): String =
    if (url.startsWith("/") && !url.startsWith("//")) serverUrl.trimEnd('/') + url else url

/** The class on `<html>` that makes the page transparent over AVPlay's video plane (screen.css). */
const val AVPLAY_ON = "avplay-on"

/** Tizen — required wherever `<video>` has no real HLS/DASH (webapis.avplay renders on its own hardware
 *  plane, behind the DOM; R264 open question 1 is whether HTML can still draw on top of it). */
class AvPlayBackend(private val serverUrl: String) : MediaBackend {
    private var listener: MediaBackendListener? = null

    override fun open(hlsUrl: String, startPositionMs: Long, startVariantBps: Long?, directPlay: Boolean) {
        // DEB-4874 — `avplay.open` is only legal from NONE: over a stream that is still open (the episode the next-up card leaves,
        // whoever started it) it throws PLAYER_ERROR_INVALID_STATE. Leave AVPlay closed first, so every start is safe, not one path.
        runCatching { WebApisGlobal.avplay.close() }
        val openedAtMs = Date.now()   // DEB-5394 (FR-37) — the second span starts here
        // DEB-4845 — AVPlay draws on a plane behind the page; an opaque page background (html, body) hides it,
        // which is a black picture with sound. The class lets the page through for as long as a stream is open.
        document.documentElement?.classList?.add(AVPLAY_ON)
        WebApisGlobal.avplay.open(resolveStreamUrl(serverUrl, hlsUrl))
        // The app's own coordinate space (the page viewport, 1920x1080 on the widget's default), not the panel's
        // pixels: a 4K set reports 3840x2160 in window.screen.
        WebApisGlobal.avplay.setDisplayRect(0, 0, window.innerWidth.takeIf { it > 0 } ?: window.screen.width, window.innerHeight.takeIf { it > 0 } ?: window.screen.height)
        WebApisGlobal.avplay.setDisplayMethod("PLAYER_DISPLAY_MODE_FULL_SCREEN")
        WebApisGlobal.avplay.setListener(avPlayListener(
            onBufferingStart = { listener?.onBufferingStart() },
            onBufferingComplete = { listener?.onBufferingComplete() },
            onStreamCompleted = { listener?.onStreamCompleted() },
            onCurrentPlayTime = { ms -> listener?.onPlayTime(ms.toLong()) },
            onError = { e -> listener?.onError(e.toString()) },
            onEvent = { type, data -> listener?.onPlayerEvent(type.toString(), if (data == null || data == undefined) null else data.toString()) },
        ))
        // DEB-5024 (FR-26) — the first picture comes from the server's start rung, not from AVPlay's own guess.
        // DEB-5503 (FR-44) — a direct play is one file, not a master: there is no rung to start on.
        if (!directPlay && startVariantBps != null && startVariantBps > 0) runCatching { WebApisGlobal.avplay.setStreamingProperty("ADAPTIVE_INFO", "STARTBITRATE=$startVariantBps") }
        WebApisGlobal.avplay.prepareAsync(
            {
                listener?.onPrepared((Date.now() - openedAtMs).toLong())
                if (directPlay) {
                    // DEB-5503 (FR-44) — AVPlay's seekTo(0) fails or does nothing, so a start at 0 seeks to 1 000 ms; play follows the seek's answer, a failed seek included.
                    val startPlaying = { runCatching { WebApisGlobal.avplay.play() }; listener?.onPlaying(); Unit }
                    val at = if (startPositionMs > 0) startPositionMs else DIRECT_START_AT_ZERO_MS
                    val seeking = runCatching { WebApisGlobal.avplay.seekTo(at.toInt(), { startPlaying() }, { startPlaying() }) }
                    if (seeking.isFailure) startPlaying()
                } else {
                    if (startPositionMs > 0) WebApisGlobal.avplay.seekTo(startPositionMs.toInt()); WebApisGlobal.avplay.play(); listener?.onPlaying()
                }
            },
            { e -> listener?.onError(e.toString()) },
        )
    }

    override fun play() { listener?.onViewerAction(); runCatching { WebApisGlobal.avplay.play() }; listener?.onPlaying() }
    override fun pause() { listener?.onViewerAction(); runCatching { WebApisGlobal.avplay.pause() }; listener?.onPaused() }
    override fun seekTo(ms: Long) { listener?.onViewerAction(); runCatching { WebApisGlobal.avplay.seekTo(ms.toInt()) } }
    override fun close() {
        runCatching { WebApisGlobal.avplay.close() }
        document.documentElement?.classList?.remove(AVPLAY_ON)
    }
    override val watchesPosition: Boolean get() = true
    override fun state(): String = runCatching { WebApisGlobal.avplay.getState() }.getOrDefault("NONE")
    override fun positionMs(): Long = runCatching { WebApisGlobal.avplay.getCurrentTime().toLong() }.getOrDefault(0L)
    override fun durationMs(): Long = runCatching { WebApisGlobal.avplay.getDuration().toLong() }.getOrDefault(0L)
    override fun setListener(listener: MediaBackendListener) { this.listener = listener }

    override fun capabilities(): ClientCapabilities = ClientCapabilities(
        // DEB-5503 (FR-43) — AVPlay plays these files over HTTP on every set since 2019. DTS (unsupported on a Samsung since 2018), TrueHD
        // and FLAC are left out, so a file whose playing track uses one is transcoded as before; VP9 and AV1 are not declared.
        containers = listOf("mkv", "mp4", "webm", "ts"),
        videoCodecs = listOf("h264", "hevc"), // AVPlay's own getTotalTrackInfo/canPlay probes are per-track, not a single yes/no gate
        audioCodecs = listOf("aac", "ac3", "eac3", "mp3", "opus"),
        maxAudioChannels = 6,
        hlsOnly = false,
        hlsAdaptive = true,   // DEB-5024 (FR-26) — AVPlay plays a multi-variant master and picks its own rung
        linkKind = "unknown",
    )

    override fun availableBitratesBps(): List<Long> = runCatching {
        val v: dynamic = WebApisGlobal.avplay.getStreamingProperty("AVAILABLE_BITRATE")
        if (v == null || v == undefined) emptyList() else Regex("\\d+").findAll(v.toString()).mapNotNull { it.value.toLongOrNull() }.filter { it > 0 }.toList()
    }.getOrDefault(emptyList())

    override fun currentBandwidthBps(): Long? = runCatching {
        val v: dynamic = WebApisGlobal.avplay.getStreamingProperty("CURRENT_BANDWIDTH")
        if (v == null || v == undefined) null else v.toString().trim().toLongOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    override fun selectAudioTrack(index: Int) { runCatching { WebApisGlobal.avplay.setSelectTrack("AUDIO", index) } }
    override fun selectSubtitleTrack(index: Int) { runCatching { WebApisGlobal.avplay.setSelectTrack("TEXT", index) } }

    /** DEB-5503 (FR-44) — the ticket's container order is the player's: its [position]th audio is the [position]th `AUDIO` entry, checked against the count first. */
    override fun selectFileAudio(position: Int, fileAudioTracks: Int): Boolean = runCatching {
        val audio = WebApisGlobal.avplay.getTotalTrackInfo().filter { it.type == "AUDIO" }
        if (audio.size != fileAudioTracks) return@runCatching false
        val track = audio.getOrNull(position) ?: return@runCatching false
        WebApisGlobal.avplay.setSelectTrack("AUDIO", track.index)
        true
    }.getOrDefault(false)
}

/** DEB-5503 (FR-44) — AVPlay's `seekTo(0)` fails or does not seek, so a direct play that starts at 0 seeks here. */
const val DIRECT_START_AT_ZERO_MS = 1_000L

/** webOS / any newer TV browser with real MSE-HLS support — the same `<video>` + hls.js path
 *  ravilo-web's own player uses (FR-235-9's vendored hls.js), self-hosted for the same "no CDN on a TV
 *  with an unreliable network path" reasoning. */
class HtmlVideoBackend(private val video: HTMLVideoElement, private val serverUrl: String) : MediaBackend {
    private var listener: MediaBackendListener? = null
    private var hls: dynamic = null

    override fun open(hlsUrl: String, startPositionMs: Long, startVariantBps: Long?, directPlay: Boolean) {
        val url = resolveStreamUrl(serverUrl, hlsUrl)
        val canNative = (video.asDynamic().canPlayType("application/vnd.apple.mpegurl") as String).isNotBlank()
        if (canNative) {
            video.src = url
        } else {
            val hlsCtor: dynamic = js("window.Hls")
            if (hlsCtor != null && (hlsCtor.isSupported() as Boolean)) {
                hls = hlsCtor.Constructor()
                hls.loadSource(url)
                hls.attachMedia(video)
            } else {
                listener?.onError("no HLS support (neither native nor hls.js)")
                return
            }
        }
        video.addEventListener("waiting", { listener?.onBufferingStart() })
        video.addEventListener("playing", { listener?.onBufferingComplete(); listener?.onPlaying() })
        video.addEventListener("pause", { listener?.onPaused() })
        video.addEventListener("timeupdate", { listener?.onPlayTime(positionMs()) })
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
    override fun bufferedMs(): Long = runCatching {
        val ranges = video.buffered
        val at = video.currentTime
        for (i in 0 until ranges.length) if (at >= ranges.start(i) && at <= ranges.end(i)) return@runCatching (ranges.end(i) * 1000).toLong()
        positionMs()
    }.getOrDefault(positionMs())
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
            hlsAdaptive = true,   // DEB-5024 (FR-26) — hls.js adapts too
            linkKind = "unknown",
        )
    }
}

/** R264 (FR-R264-8) — Tizen ships AVPlay; a webOS/other build falls back to `<video>`+hls.js. Detected
 *  once at boot, not per-play — a receiver's hardware doesn't change mid-session. */
fun detectMediaBackend(serverUrl: String): MediaBackend {
    val hasAvPlay = runCatching { js("typeof webapis !== 'undefined' && !!webapis.avplay") as Boolean }.getOrDefault(false)
    return if (hasAvPlay) AvPlayBackend(serverUrl) else HtmlVideoBackend(document.getElementById("video") as HTMLVideoElement, serverUrl)
}
