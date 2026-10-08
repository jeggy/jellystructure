@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * R376 (FR-R376-4) — fullscreen and orientation, as a browser allows.
 *
 * - In a browser tab the player enters fullscreen on the **page element** (`document.documentElement`), never the
 *   `<video>`, so the Compose chrome stays on top; it leaves on exit — but only a fullscreen it entered itself, so a
 *   viewer who was already fullscreen (the shell's F key) stays there.
 * - An installed app (standalone) is already the full screen: nothing to enter. An iPhone has no element fullscreen
 *   at all, and `webkitEnterFullscreen` is never called (it hands the picture to iOS's own player and drops the chrome).
 * - Android: landscape is locked while the film plays in fullscreen or standalone, and unlocked on exit; a refused
 *   lock says nothing (R244's rotate rule applies).
 * - The shell's own Fullscreen button hides while the player is up (it sat over the chrome's bottom-right controls).
 *
 * [followSensor] has no web meaning: a page cannot read the system's rotation lock.
 */
@Composable
actual fun PlayerImmersiveEffect(followSensor: Boolean) {
    DisposableEffect(Unit) {
        val entered = jsEnterPlayerFullscreen()
        onDispose { jsLeavePlayerFullscreen(entered) }
    }
}

private fun jsEnterPlayerFullscreen(): Boolean = js(
    """(function(){
        try { document.body.classList.add('rv-playing'); } catch (e) {}
        var standalone = (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches) || navigator.standalone === true;
        var android = /Android/i.test(navigator.userAgent || '');
        function lock() {
            if (!android) return;
            try { if (screen.orientation && screen.orientation.lock) screen.orientation.lock('landscape').catch(function(){}); } catch (e) {}
        }
        if (standalone || document.fullscreenElement || document.webkitFullscreenElement) { lock(); return false; }
        var el = document.documentElement;
        var req = el.requestFullscreen || el.webkitRequestFullscreen;
        if (!req || !(document.fullscreenEnabled || document.webkitFullscreenEnabled)) return false;
        try {
            var p = req.call(el);
            if (p && p.then) p.then(lock, function(){}); else lock();
            return true;
        } catch (e) { return false; }
    })()"""
)

private fun jsLeavePlayerFullscreen(entered: Boolean): Unit = js(
    """{
        try { document.body.classList.remove('rv-playing'); } catch (e) {}
        try { if (screen.orientation && screen.orientation.unlock) screen.orientation.unlock(); } catch (e) {}
        if (entered && (document.fullscreenElement || document.webkitFullscreenElement)) {
            try { var x = (document.exitFullscreen || document.webkitExitFullscreen).call(document); if (x && x.catch) x.catch(function(){}); } catch (e) {}
        }
    }"""
)
