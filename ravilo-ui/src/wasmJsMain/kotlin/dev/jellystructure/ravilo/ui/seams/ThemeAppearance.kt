@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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

/** R338 — the browser draws no bars of ours to recolour. */
@Composable
actual fun SystemBarsAppearance(light: Boolean) {}
