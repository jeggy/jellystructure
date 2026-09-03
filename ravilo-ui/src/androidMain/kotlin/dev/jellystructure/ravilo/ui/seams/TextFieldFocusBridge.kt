package dev.jellystructure.ravilo.ui.seams

import androidx.compose.ui.Modifier

// No-op — no global keydown-hijacking handler exists on Android to guard against.
actual fun Modifier.reportTextFieldFocus(): Modifier = this
