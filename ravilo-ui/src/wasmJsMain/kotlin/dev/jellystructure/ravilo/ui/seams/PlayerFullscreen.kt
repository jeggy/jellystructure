@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.seams

/**
 * R376 (FR-R376-4b) — runs inside the viewer's gesture (the click or key handler that called it), because
 * `requestFullscreen` needs one — Safari is the strict one. The page element, never the `<video>`, so the Compose
 * chrome stays on top. A refused request says nothing.
 */
actual fun togglePlayerFullscreen(): Unit = js(
    """{
        try {
            if (document.fullscreenElement || document.webkitFullscreenElement) {
                var x = (document.exitFullscreen || document.webkitExitFullscreen).call(document);
                if (x && x.catch) x.catch(function(){});
            } else {
                var el = document.documentElement;
                var r = (el.requestFullscreen || el.webkitRequestFullscreen).call(el);
                if (r && r.catch) r.catch(function(){});
            }
        } catch (e) {}
    }"""
)
