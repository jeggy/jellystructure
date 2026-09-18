package dev.jellystructure.ravilo.ui.seams

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// R261 (dev review item 3/5) — union rather than plain safeDrawing: safeDrawing tracks live bar
// *visibility*, so a screen composed while the bars are mid-animation back in pads for their eventual
// state a frame or more before they arrive, producing the measured 69 px jump. Ignoring visibility
// means this never depends on where WindowInsetsControllerCompat's show()/hide() animation currently is.
@OptIn(ExperimentalLayoutApi::class)
@Composable
actual fun Modifier.safeAreaPadding(): Modifier {
    val insets = WindowInsets.systemBarsIgnoringVisibility
        .union(WindowInsets.displayCutout)
        .union(WindowInsets.ime)
    return this.windowInsetsPadding(insets)
}
