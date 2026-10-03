@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay

/** R338 — `prefers-color-scheme`, read once a second: a change of the system's appearance repaints within a second.
 *  (Only a phone-layout web app uses it; in a desktop browser the web app is dark-only, D6.) */
@Composable
actual fun systemDarkAppearance(): Boolean? {
    val dark by produceState(prefersDark()) {
        while (true) { delay(1_000); value = prefersDark() }
    }
    return dark
}

private fun prefersDark(): Boolean =
    js("!(window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches)")

/** R338 amendment (2026-10-03) — the browser draws no bars of ours, but it shows the page's own background around the
 *  canvas: on an iPhone's home-screen app that is the status bar band and the home-indicator band. Painted in the
 *  theme's page colour, with `theme-color` to match, so the clock is never dark ink on a dark band in a light theme.
 *  The `<video>` keeps its own black. */
@Composable
actual fun SystemBarsAppearance(light: Boolean, background: Color) {
    val hex = "#" + (background.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')
    SideEffect { paintPageBackground(hex) }
}

private fun paintPageBackground(hex: String): Unit = js(
    """{ document.documentElement.style.background = hex; document.body.style.background = hex; var m = document.querySelector('meta[name="theme-color"]'); if (m) m.setAttribute('content', hex); }"""
)
