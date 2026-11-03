@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * R376 (FR-R376-4f–4h, amendment 2026-10-30, DEB-2998) — the background, as a browser has one. **On a computer a hidden
 * page changes nothing**: another tab, a minimised window and a covered window leave the film playing, paused or
 * buffering exactly as it was, and coming back does nothing either (no stop, no [onForeground], no new ticket). On a
 * phone or tablet the old rule stands: hidden pauses the player and takes the same stop/progress path Android's
 * `ON_STOP` takes ([onBackground] — the resume record, the stop report with the position), as NOT playing, so the
 * return ([onForeground] — a fresh start at the record's position) does not auto-resume; not while the picture is in
 * picture-in-picture or on AirPlay. [WebPageLifecycleGate] decides; this file only feeds it the DOM's events.
 *
 * Leaving still ends it on both (FR-R376-4g): `pagehide` stops, and the return from the back-forward cache starts
 * again. It is the only event a closing tab is guaranteed to fire (R372, FR-R372-11). Every stop goes through
 * [UnloadStop.whileUnloading], so it leaves on a `keepalive` fetch the browser finishes after the page is gone instead of
 * a Ktor request it aborts with the page. One stop either way: the gate's `away` latch, and `PlayerStore.stopSession`
 * clears its item before anything else can post.
 *
 * Live TV uses this same effect, so it follows. It also announces the player to `boot.js` ([jsPlayerUp]), which is what
 * keeps a hidden page's frames coming (FR-R376-4i).
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
        val gate = WebPageLifecycleGate(phone = jsIsPhone())
        val handle = jsWatchVisibility(player.video) { code, exempt ->
            when (gate.on(WebPageLifecycleGate.Signal.entries[code], exempt)) {
                WebPageLifecycleGate.Action.Stop -> {
                    player.pause()
                    UnloadStop.whileUnloading { background(false) }
                }
                WebPageLifecycleGate.Action.Foreground -> foreground(false)
                WebPageLifecycleGate.Action.None -> Unit
            }
        }
        jsPlayerUp(1)
        onDispose {
            jsPlayerUp(-1)
            jsUnwatchVisibility(handle)
        }
    }
}

/**
 * FR-R376-4i — tells `boot.js` a player is up (+1) or gone (-1), so that while the page is hidden *and* a player is up
 * Compose's frames come from a ~1 Hz timer instead of `requestAnimationFrame`, which a hidden page does not run
 * (the next-up countdown and the next episode need a recomposition). Absent in a bundle served without `boot.js`.
 */
private fun jsPlayerUp(delta: Int): Unit = js("{ var h = window.raviloHiddenFrames; if (h) h.player(delta); }")

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

/** FR-R376-4h — a phone or tablet, by the two tests already in the tree: `boot.js`'s `isIOS` and [PlayerImmersiveEffect]'s Android UA. */
private fun jsIsPhone(): Boolean = js(
    """(function(){
        var ua = navigator.userAgent || '';
        var ios = /iPad|iPhone|iPod/.test(ua) || (navigator.platform === 'MacIntel' && (navigator.maxTouchPoints || 0) > 1);
        return ios || /Android/i.test(ua);
    })()"""
)

/** Codes are [WebPageLifecycleGate.Signal] ordinals: 0 HIDDEN, 1 VISIBLE, 2 PAGE_HIDE, 3 PAGE_SHOW. */
private fun jsWatchVisibility(video: org.w3c.dom.HTMLVideoElement, onSignal: (Int, Boolean) -> Unit): JsAny = js(
    """(function(){
        var vis = function () {
            var hidden = document.visibilityState === 'hidden';
            var exempt = hidden && (document.pictureInPictureElement === video || !!video.webkitCurrentPlaybackTargetIsWireless);
            onSignal(hidden ? 0 : 1, exempt);
        };
        var gone = function () { onSignal(2, false); };
        var back = function (e) { if (e.persisted) onSignal(3, false); };
        document.addEventListener('visibilitychange', vis);
        window.addEventListener('pagehide', gone);
        window.addEventListener('pageshow', back);
        return { vis: vis, gone: gone, back: back };
    })()"""
)

private fun jsUnwatchVisibility(handle: JsAny): Unit = js(
    """(function(){
        document.removeEventListener('visibilitychange', handle.vis);
        window.removeEventListener('pagehide', handle.gone);
        window.removeEventListener('pageshow', handle.back);
    })()"""
)
