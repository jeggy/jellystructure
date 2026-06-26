@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.SubTrack
import kotlinx.browser.document
import org.w3c.dom.HTMLVideoElement

/**
 * Web actual: a browser <video> element appended to the document body, positioned behind
 * the Compose/skiko canvas so the shared chrome renders on top.
 * HLS is handled by browser-native support (Safari/Edge) or a future hls.js injection.
 * Audio track selection is handled by the browser; subtitle tracks use <track> elements.
 */
actual class RaviloPlayer actual constructor() {
    private val video: HTMLVideoElement = (document.createElement("video") as HTMLVideoElement).also { v ->
        v.style.cssText = "position:fixed;top:0;left:0;width:100%;height:100%;background:#000;z-index:0"
        v.controls = false
        document.body?.appendChild(v)
    }

    private var loadedSubtitles: List<SubTrack> = emptyList()
    private var loadedAudio: List<AudioTrack> = emptyList()

    actual fun load(streamUrl: String, startPositionMs: Long, subtitles: List<SubTrack>, audio: List<AudioTrack>) {
        loadedSubtitles = subtitles
        loadedAudio = audio
        // Remove existing <track> children
        while (video.childElementCount > 0) {
            video.firstChild?.let { video.removeChild(it) }
        }
        // R17: lazy-load hls.js for HLS streams (the R56 transcode / burn-in TranscodingUrl is an
        // .m3u8, which non-Safari browsers can't play natively). Direct-play URLs set video.src.
        attachSource(video, streamUrl)
        video.currentTime = startPositionMs / 1000.0
        // R44: route browser/OS media keys to the <video> via the Media Session API.
        wireMediaSession(video)
        subtitles.forEach { sub ->
            val url = sub.url ?: return@forEach
            // R17: native .ass/.ssa subs render with JASSUB (libass) for full styling; the rest are
            // delivered as VTT and use a plain <track>. JASSUB is lazy-loaded only when first needed.
            if (url.endsWith(".ass", ignoreCase = true) || url.endsWith(".ssa", ignoreCase = true)) {
                if (sub.isDefault) mountAss(video, url)
                return@forEach
            }
            val trackEl = document.createElement("track")
            trackEl.setAttribute("kind", if (sub.forced) "forced" else "subtitles")
            sub.language?.let { trackEl.setAttribute("srclang", it) }
            sub.label?.let { trackEl.setAttribute("label", it) }
            if (sub.isDefault) trackEl.setAttribute("default", "")
            video.appendChild(trackEl)
            // R68: fetch VTT, strip ASS/SSA override tags, attach as blob: URL
            fetchAndCleanVtt(url) { cleanUrl -> trackEl.setAttribute("src", cleanUrl) }
        }
    }

    actual fun play() { video.play() }
    actual fun pause() { video.pause() }
    actual fun seekTo(positionMs: Long) { video.currentTime = positionMs / 1000.0 }

    actual fun selectAudioTrack(index: Int) {
        // Browser manages audio track selection; multi-audio streams require hls.js API
    }

    actual fun selectSubtitleTrack(index: Int) {
        // TextTrackList item-by-index access is not bridged in Kotlin/WASM DOM bindings.
        // Subtitle switching is deferred to a future JS-interop bridge; the <track default>
        // attribute set in load() handles the initial selection. (index -1 = off.)
    }

    actual fun release() {
        runCatching { destroyOverlays(video) } // R17: tear down any hls.js / JASSUB instance
        runCatching { document.body?.removeChild(video) }
    }

    actual val positionMs: Long get() = (video.currentTime * 1000).toLong()
    actual val durationMs: Long get() {
        val d = video.duration
        return if (d.isNaN() || d.isInfinite()) 0L else (d * 1000).toLong()
    }
    actual val bufferedMs: Long get() {
        val buf = video.buffered
        return if (buf.length > 0) (buf.end(buf.length - 1) * 1000).toLong() else 0L
    }
    actual val isPlaying: Boolean get() = !video.paused && !video.ended
    actual val isEnded: Boolean get() = video.ended
    // R46: the browser doesn't expose rich embedded-audio metadata, so surface the server-derived
    // labels for the picker. (Switching multi-audio still needs an hls.js bridge — display only.)
    actual val audioTracks: List<PlayerAudioTrack> get() =
        loadedAudio.mapIndexed { i, a ->
            val label = a.label?.takeIf { it.isNotBlank() } ?: languageName(a.language) ?: a.language ?: "Track ${i + 1}"
            PlayerAudioTrack(i, label, a.language)
        }
    actual val subtitleTracks: List<PlayerSubtitleTrack> get() =
        loadedSubtitles.mapIndexed { i, s ->
            val label = s.label ?: languageName(s.language) ?: s.language ?: "Track ${i + 1}"
            PlayerSubtitleTrack(i, label, s.language, s.forced, s.isDefault)
        }
}

/**
 * R44: wire the browser Media Session API so OS / keyboard media-transport keys drive the <video>.
 * Handlers operate on the element directly (no WASM↔JS callback bridge); the shared chrome reflects
 * the new state on its next poll. Wrapped in try/catch — `mediaSession` is absent on some browsers.
 */
private fun wireMediaSession(video: HTMLVideoElement): Unit = js(
    """{
        if (typeof navigator !== 'undefined' && navigator.mediaSession) {
            var ms = navigator.mediaSession;
            try {
                ms.setActionHandler('play', function () { video.play(); });
                ms.setActionHandler('pause', function () { video.pause(); });
                ms.setActionHandler('seekforward', function () {
                    var d = video.duration; video.currentTime = Math.min(isFinite(d) ? d : 1e9, video.currentTime + 30);
                });
                ms.setActionHandler('seekbackward', function () {
                    video.currentTime = Math.max(0, video.currentTime - 10);
                });
                ms.setActionHandler('stop', function () { video.pause(); });
            } catch (e) {}
        }
    }"""
)

/**
 * R17 — set the video source, lazy-loading hls.js the first time an HLS (.m3u8) stream is played
 * (the R56 transcode / burn-in TranscodingUrl). Safari plays HLS natively; everything else uses
 * hls.js, fetched from a CDN only on demand. Direct-play URLs just set `video.src`.
 */
private fun attachSource(video: HTMLVideoElement, url: String): Unit = js(
    """{
        function native(){ video.src = url; }
        if (url.indexOf('.m3u8') === -1) { native(); return; }
        if (video.canPlayType && video.canPlayType('application/vnd.apple.mpegurl')) { native(); return; }
        function attach(){
            try {
                if (window.Hls && window.Hls.isSupported()) {
                    if (video._hls) { try { video._hls.destroy(); } catch(e){} }
                    var hls = new window.Hls(); video._hls = hls;
                    hls.loadSource(url); hls.attachMedia(video);
                } else { native(); }
            } catch(e) { native(); }
        }
        if (window.Hls) { attach(); return; }
        if (!window.__hlsLoading) {
            window.__hlsLoading = true;
            var s = document.createElement('script');
            s.src = 'https://cdn.jsdelivr.net/npm/hls.js@1.5.13/dist/hls.min.js';
            s.onload = attach; s.onerror = native;
            document.head.appendChild(s);
        } else {
            var iv = setInterval(function(){ if (window.Hls){ clearInterval(iv); attach(); } }, 100);
            setTimeout(function(){ clearInterval(iv); }, 8000);
        }
    }"""
)

/**
 * R17 — render an .ass/.ssa subtitle with JASSUB (libass in WASM), lazy-loaded on first use so the
 * worker + wasm aren't fetched unless a styled subtitle is actually selected.
 */
private fun mountAss(video: HTMLVideoElement, url: String): Unit = js(
    """{
        function go(){
            try {
                if (video._jassub) { try { video._jassub.destroy(); } catch(e){} video._jassub = null; }
                video._jassub = new window.JASSUB({
                    video: video,
                    subUrl: url,
                    workerUrl: 'https://cdn.jsdelivr.net/npm/jassub@1.7.0/dist/jassub-worker.js',
                    wasmUrl: 'https://cdn.jsdelivr.net/npm/jassub@1.7.0/dist/jassub-worker.wasm'
                });
            } catch(e) {}
        }
        if (window.JASSUB) { go(); return; }
        if (!window.__jassubLoading) {
            window.__jassubLoading = true;
            var s = document.createElement('script');
            s.src = 'https://cdn.jsdelivr.net/npm/jassub@1.7.0/dist/jassub.umd.js';
            s.onload = go;
            document.head.appendChild(s);
        } else {
            var iv = setInterval(function(){ if (window.JASSUB){ clearInterval(iv); go(); } }, 100);
            setTimeout(function(){ clearInterval(iv); }, 8000);
        }
    }"""
)

/**
 * R68 — fetch a VTT subtitle URL, strip ASS/SSA override tags from every cue, and return a
 * blob: URL pointing at the cleaned VTT so the browser <track> renders clean text.
 * The callback receives the cleaned URL (blob: or the original on fetch failure).
 */
private fun fetchAndCleanVtt(url: String, callback: (String) -> Unit): Unit = js(
    """{
        fetch(url)
            .then(function(r){ return r.text(); })
            .then(function(vtt){
                // Strip {\...} ASS override blocks and normalize \N/\h escape sequences.
                var cleaned = vtt
                    .replace(/\{\\[^}]*\}/g, '')
                    .replace(/\\N/g, '\n')
                    .replace(/\\n/g, '\n')
                    .replace(/\\h/g, ' ');
                var blob = new Blob([cleaned], {type: 'text/vtt'});
                var blobUrl = URL.createObjectURL(blob);
                callback(blobUrl);
            })
            .catch(function(){ callback(url); }); // fall back to original on error
    }"""
)

/** R17 — destroy any hls.js / JASSUB instance attached to the element (called on release). */
private fun destroyOverlays(video: HTMLVideoElement): Unit = js(
    """{
        if (video._hls) { try { video._hls.destroy(); } catch(e){} video._hls = null; }
        if (video._jassub) { try { video._jassub.destroy(); } catch(e){} video._jassub = null; }
    }"""
)
