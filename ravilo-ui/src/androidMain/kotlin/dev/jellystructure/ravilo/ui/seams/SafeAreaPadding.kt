package dev.jellystructure.ravilo.ui.seams

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// R261 (dev review item 3/5) — union rather than plain safeDrawing: safeDrawing tracks live bar
// *visibility*, so a screen composed while the bars are mid-animation back in pads for their eventual
// state a frame or more before they arrive, producing the measured 69 px jump. Ignoring visibility
// means this never depends on where WindowInsetsControllerCompat's show()/hide() animation currently is.
//
// R274 — these insets are real on a phone, whatever `phone/MainActivity.kt` says about "Android's own
// default window fitting": ravilo-android is targetSdk 36, and Android 15+ enforces edge-to-edge for
// anything targeting 35 or above (36 removed the opt-out attribute). The window is not fitted, so this
// seam is the only thing standing between the app and the system bars.
@OptIn(ExperimentalLayoutApi::class)
@Composable
actual fun Modifier.safeAreaPadding(includeIme: Boolean, plusBottom: Dp): Modifier {
    var insets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    // Added before the IME is unioned in, never after — see the seam's own doc for why the order is
    // the requirement (FR-R274-3).
    if (plusBottom > 0.dp) insets = insets.add(WindowInsets(bottom = plusBottom))
    if (includeIme) insets = insets.union(WindowInsets.ime)
    return this.windowInsetsPadding(insets)
}
