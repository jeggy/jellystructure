package dev.jellystructure.ravilo.ui.seams

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// R261 doesn't touch web (Non-goals): unchanged from what every call site already did. R263
// FR-R263-6 is the one that needs this to read the real `env(safe-area-inset-*)` values via CSS —
// Compose Multiplatform's own WindowInsets has no source on wasm, which is why this stays a
// placeholder rather than a fix.
@Composable
actual fun Modifier.safeAreaPadding(): Modifier = this.windowInsetsPadding(WindowInsets.safeDrawing)
