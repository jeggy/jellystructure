package dev.jellystructure.ravilo.ui.seams

import kotlinx.browser.document
import org.w3c.dom.HTMLVideoElement

/**
 * Web actual: a browser <video> element appended to the document body.
 * Positioned behind the Compose canvas so the skiko layer renders chrome on top.
 * HLS is handled by native browser support (Safari, Edge) or a future hls.js injection.
 */
actual class RaviloPlayer actual constructor() {
    private val video: HTMLVideoElement = (document.createElement("video") as HTMLVideoElement).also { v ->
        v.style.cssText = "position:fixed;top:0;left:0;width:100%;height:100%;background:#000;z-index:0"
        v.controls = false
        document.body?.appendChild(v)
    }

    actual fun load(streamUrl: String, startPositionMs: Long) {
        video.src = streamUrl
        video.currentTime = startPositionMs / 1000.0
    }

    actual fun play() { video.play() }
    actual fun pause() { video.pause() }
    actual fun seekTo(positionMs: Long) { video.currentTime = positionMs / 1000.0 }
    actual fun release() { runCatching { document.body?.removeChild(video) } }
    actual val positionMs: Long get() = (video.currentTime * 1000).toLong()
    actual val isPlaying: Boolean get() = !video.paused && !video.ended
}
