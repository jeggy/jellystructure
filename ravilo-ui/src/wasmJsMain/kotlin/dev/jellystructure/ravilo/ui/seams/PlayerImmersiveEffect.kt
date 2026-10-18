@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * R376 (FR-R376-4, amended 2026-10-17 by FR-R376-4a–4e) — fullscreen and orientation, as a browser allows.
 *
 * - Play **never** enters fullscreen: the film fills the tab and the viewer's own click is what goes full screen
 *   (the chrome's control, F, a double-click — [PlayerFullscreen], [togglePlayerFullscreen]). A viewer already
 *   fullscreen when the player opens stays there.
 * - Leaving the player leaves only a fullscreen entered while it was up, as the Mac does (FR-R329-7); Esc stays the
 *   browser's own.
 * - An installed app (standalone) is already the full screen: no control. An iPhone has no element fullscreen at all,
 *   and `webkitEnterFullscreen` is never called (it hands the picture to iOS's own player and drops the chrome).
 * - Android: in a tab, landscape is locked while the viewer's fullscreen lasts and released when it ends; a refused
 *   lock says nothing (R244's rotate rule applies). The installed app locks landscape when the player opens.
 * - The shell's own Fullscreen button hides while the player is up (it sat over the chrome's bottom-right controls).
 *
 * [followSensor] has no web meaning: a page cannot read the system's rotation lock.
 */
@Composable
actual fun PlayerImmersiveEffect(followSensor: Boolean) {
    DisposableEffect(Unit) {
        val handle = jsEnterPlayer { active -> PlayerFullscreen.active = active }
        PlayerFullscreen.available = jsFullscreenAvailable()
        PlayerFullscreen.active = jsFullscreenActive()
        onDispose {
            jsLeavePlayer(handle)
            PlayerFullscreen.available = false
            PlayerFullscreen.active = false
        }
    }
}

private fun jsFullscreenActive(): Boolean = js("!!(document.fullscreenElement || document.webkitFullscreenElement)")

/** A browser tab (not the installed app) whose page can go full screen: absent on iPhone Safari, which has no element fullscreen. */
private fun jsFullscreenAvailable(): Boolean = js(
    """(function(){
        var standalone = (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches) || navigator.standalone === true;
        var el = document.documentElement;
        return !standalone && !!(document.fullscreenEnabled || document.webkitFullscreenEnabled) && !!(el.requestFullscreen || el.webkitRequestFullscreen);
    })()"""
)

private fun jsEnterPlayer(onChange: (Boolean) -> Unit): JsAny = js(
    """(function(){
        try { document.body.classList.add('rv-playing'); } catch (e) {}
        var standalone = (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches) || navigator.standalone === true;
        var android = /Android/i.test(navigator.userAgent || '');
        function lock() {
            if (!android) return;
            try { if (screen.orientation && screen.orientation.lock) screen.orientation.lock('landscape').catch(function(){}); } catch (e) {}
        }
        function unlock() { try { if (screen.orientation && screen.orientation.unlock) screen.orientation.unlock(); } catch (e) {} }
        function full() { return !!(document.fullscreenElement || document.webkitFullscreenElement); }
        var h = { byPlayer: false, standalone: standalone, change: null };
        // FR-R376-4d — the installed app locks landscape when the player opens; a tab takes the lock with the viewer's full screen.
        if (standalone) lock();
        else if (full()) { if (android) lock(); }
        h.change = function () {
            var on = full();
            // FR-R376-4c — any full screen that begins while the player is up is the player's to leave.
            h.byPlayer = on;
            if (on) lock(); else unlock();
            onChange(on);
        };
        if (!standalone) {
            document.addEventListener('fullscreenchange', h.change);
            document.addEventListener('webkitfullscreenchange', h.change);
        }
        return h;
    })()"""
)

private fun jsLeavePlayer(h: JsAny): Unit = js(
    """{
        try { document.body.classList.remove('rv-playing'); } catch (e) {}
        document.removeEventListener('fullscreenchange', h.change);
        document.removeEventListener('webkitfullscreenchange', h.change);
        try { if (screen.orientation && screen.orientation.unlock) screen.orientation.unlock(); } catch (e) {}
        if (h.byPlayer && (document.fullscreenElement || document.webkitFullscreenElement)) {
            try { var x = (document.exitFullscreen || document.webkitExitFullscreen).call(document); if (x && x.catch) x.catch(function(){}); } catch (e) {}
        }
    }"""
)
