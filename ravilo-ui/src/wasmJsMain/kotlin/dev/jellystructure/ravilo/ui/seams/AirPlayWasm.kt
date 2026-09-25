@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.seams

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.w3c.dom.HTMLVideoElement

/**
 * R265 (FR-R265-4/-8) — WebKit's AirPlay, read from the one `<video>` the web player owns. [bind] is
 * called by the player when it creates the element; the two WebKit events drive the flows, and [unbind]
 * on release clears them (no player, no target). On a browser without WebKit's events nothing ever
 * fires and both stay false, so the row, the glyph and the bar never appear there.
 */
object WebAirPlay : AirPlay {
    private val _available = MutableStateFlow(false)
    private val _wireless = MutableStateFlow(false)
    override val available: StateFlow<Boolean> = _available
    override val wireless: StateFlow<Boolean> = _wireless
    private var bound: HTMLVideoElement? = null

    internal fun bind(video: HTMLVideoElement) {
        bound = video
        jsBindAirPlay(video, { a -> _available.value = a }, { w -> _wireless.value = w })
    }

    internal fun unbind(video: HTMLVideoElement) {
        if (bound != video) return
        bound = null
        _available.value = false
        _wireless.value = false
    }

    override fun showPicker() {
        bound?.let { jsShowPicker(it) }
    }
}

actual val platformAirPlay: AirPlay? = WebAirPlay

// `x-webkit-airplay="allow"` is what lets WebKit offer the element to AirPlay at all; the two events are
// WebKit's own (no standard equivalent carries AirPlay). Wrapped in try: any other engine ignores both.
private fun jsBindAirPlay(video: HTMLVideoElement, onAvailable: (Boolean) -> Unit, onWireless: (Boolean) -> Unit): Unit = js(
    """{
        try {
            video.setAttribute('x-webkit-airplay', 'allow');
            video.addEventListener('webkitplaybacktargetavailabilitychanged', function (e) {
                onAvailable(e.availability === 'available');
            });
            video.addEventListener('webkitcurrentplaybacktargetiswirelesschanged', function () {
                onWireless(!!video.webkitCurrentPlaybackTargetIsWireless);
            });
        } catch (e) {}
    }"""
)

private fun jsShowPicker(video: HTMLVideoElement): Unit = js(
    """{ try { if (video.webkitShowPlaybackTargetPicker) video.webkitShowPlaybackTargetPicker(); } catch (e) {} }"""
)
