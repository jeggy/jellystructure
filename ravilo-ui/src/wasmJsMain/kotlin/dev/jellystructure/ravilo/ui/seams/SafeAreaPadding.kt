package dev.jellystructure.ravilo.ui.seams

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// R263 (FR-R263-6) — Compose Multiplatform's own WindowInsets has no source on wasm (no platform ever
// pushes a real value into it there), so R261's placeholder (delegating to WindowInsets.safeDrawing,
// always zero on web) is replaced here with the app's own seam: a zero-size probe element in
// index.html (#ravilo-safe-area-probe) whose padding is set from env(safe-area-inset-*), read back via
// getComputedStyle. In a standalone iOS app this is the Dynamic Island / home indicator; in a Safari
// tab or on Android it's 0 on every side and this is a no-op, matching R261's placeholder exactly for
// every case that isn't a standalone iOS install.
//
// Polled every 500ms rather than wired to resize/orientationchange listeners (which would need a
// JS-to-Kotlin callback bridge) — insets change only on rotation or entering/leaving standalone mode,
// both rare enough that a short poll is indistinguishable from an event in practice, and this avoids
// building a second bridge mechanism next to the one seams/InstallPrompt.kt already uses for the same
// reason.
@Composable
actual fun Modifier.safeAreaPadding(): Modifier {
    val insets by produceState(initialValue = readSafeAreaInsetsPx()) {
        while (true) {
            delay(500)
            value = readSafeAreaInsetsPx()
        }
    }
    return this.padding(
        start = insets.left.dp,
        top = insets.top.dp,
        end = insets.right.dp,
        bottom = insets.bottom.dp,
    )
}

private class SafeAreaInsetsPx(val top: Float, val right: Float, val bottom: Float, val left: Float)

private fun readSafeAreaInsetsPx(): SafeAreaInsetsPx =
    SafeAreaInsetsPx(top = jsSafeAreaTop(), right = jsSafeAreaRight(), bottom = jsSafeAreaBottom(), left = jsSafeAreaLeft())

// One CSS px is treated as one Compose dp — the convention Compose-for-Web/Wasm's canvas already
// follows (there is no separate "device pixel ratio" scaling layer between the DOM and the canvas
// here), and env(safe-area-inset-*) itself is reported in CSS px.
private fun jsSafeAreaTop(): Float = js(
    "(parseFloat(getComputedStyle(document.getElementById('ravilo-safe-area-probe')).paddingTop) || 0)"
)
private fun jsSafeAreaRight(): Float = js(
    "(parseFloat(getComputedStyle(document.getElementById('ravilo-safe-area-probe')).paddingRight) || 0)"
)
private fun jsSafeAreaBottom(): Float = js(
    "(parseFloat(getComputedStyle(document.getElementById('ravilo-safe-area-probe')).paddingBottom) || 0)"
)
private fun jsSafeAreaLeft(): Float = js(
    "(parseFloat(getComputedStyle(document.getElementById('ravilo-safe-area-probe')).paddingLeft) || 0)"
)
