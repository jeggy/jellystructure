package dev.jellystructure.ravilo.ui.seams

import androidx.compose.ui.Modifier

// No-op — no mouse on Android/TV; D-pad activity already wakes chrome via the existing key handlers.
actual fun Modifier.wakeOnPointerMove(onMove: () -> Unit): Modifier = this
