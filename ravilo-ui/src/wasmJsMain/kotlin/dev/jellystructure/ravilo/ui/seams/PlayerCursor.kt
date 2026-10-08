@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.seams

// R376 (FR-R376-1) — the canvas now lives in #ComposeTarget's shadow root and carries its own inline cursor (the
// viewport sets it for every pointer icon), so the page's cursor alone no longer hides it over the player.
actual fun setPointerCursorHidden(hidden: Boolean) {
    runCatching { jsSetCursorHidden(hidden) }
}

private fun jsSetCursorHidden(hidden: Boolean): Unit = js(
    """{
        document.body.style.cursor = hidden ? 'none' : 'auto';
        var host = document.getElementById('ComposeTarget');
        var c = host && host.shadowRoot && host.shadowRoot.querySelector('canvas');
        if (c) { if (hidden) { c._rvCursor = c.style.cursor; c.style.cursor = 'none'; } else if (c.style.cursor === 'none') { c.style.cursor = c._rvCursor || 'auto'; } }
    }"""
)
