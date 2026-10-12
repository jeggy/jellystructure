@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * R376 (FR-R376-4) — the background, as a browser has one. When the page goes hidden (another tab, the phone's home
 * screen, a locked screen) the player pauses and takes the same stop/progress path Android's `ON_STOP` takes
 * ([onBackground] — the resume record, the stop report with the position). It goes as NOT playing, so the return
 * ([onForeground] — a fresh start at the record's position) does not auto-resume. Not while the picture is in
 * picture-in-picture or on AirPlay: the viewer is still watching then.
 *
 * R372 (FR-R372-11, amendment 2026-10-12) — and `pagehide`, which is the only one of the two a closing tab is
 * guaranteed to fire. Both go through [UnloadStop.whileUnloading], so the stop leaves on a `keepalive` fetch the
 * browser finishes after the page is gone instead of a Ktor request it aborts with the page. One stop either way: the
 * first event latches `away`, and `PlayerStore.stopSession` clears its item before anything else can post.
 */
@Composable
actual fun PlayerLifecycleEffect(
    player: RaviloPlayer,
    wasPlaying: () -> Boolean,
    onBackground: (wasPlaying: Boolean) -> Unit,
    onForeground: (afterStandby: Boolean) -> Unit,
) {
    val background by rememberUpdatedState(onBackground)
    val foreground by rememberUpdatedState(onForeground)
    DisposableEffect(player) {
        // FR-R372-11 — installed, never removed: a stop posted during this effect's own dispose still needs it.
        UnloadStop.transport = ::keepalivePost
        var away = false
        val handle = jsWatchVisibility(player.video) { hidden ->
            if (hidden && !away) {
                away = true
                player.pause()
                UnloadStop.whileUnloading { background(false) }
            } else if (!hidden && away) {
                away = false
                foreground(false)
            }
        }
        onDispose { jsUnwatchVisibility(handle) }
    }
}

/**
 * FR-R372-11 — the page-surviving post: `fetch(…, { keepalive: true })`, with the device token in `Authorization`
 * exactly as every other call carries it. Fire-and-forget by design — the promise outlives the page, so there is no
 * answer to wait for and nothing here can report one. True once the browser has taken the request.
 */
private fun keepalivePost(request: dev.jellystructure.shared.tv.PlainPost): Boolean {
    val headers = jsNewObject()
    request.headers.forEach { (name, value) -> jsPut(headers, name, value) }
    return jsKeepalivePost(request.url, headers, request.body)
}

private fun jsNewObject(): JsAny = js("({})")

private fun jsPut(target: JsAny, name: String, value: String): Unit = js("(function(){ target[name] = value; })()")

private fun jsKeepalivePost(url: String, headers: JsAny, body: String): Boolean = js(
    """(function(){
        try {
            fetch(url, { method: 'POST', headers: headers, body: body, keepalive: true });
            return true;
        } catch (e) {
            return false;
        }
    })()"""
)

private fun jsWatchVisibility(video: org.w3c.dom.HTMLVideoElement, onChange: (Boolean) -> Unit): JsAny = js(
    """(function(){
        var vis = function () {
            var hidden = document.visibilityState === 'hidden';
            if (hidden && (document.pictureInPictureElement === video || video.webkitCurrentPlaybackTargetIsWireless)) return;
            onChange(hidden);
        };
        var gone = function () { onChange(true); };
        document.addEventListener('visibilitychange', vis);
        window.addEventListener('pagehide', gone);
        return { vis: vis, gone: gone };
    })()"""
)

private fun jsUnwatchVisibility(handle: JsAny): Unit = js(
    """(function(){
        document.removeEventListener('visibilitychange', handle.vis);
        window.removeEventListener('pagehide', handle.gone);
    })()"""
)
