package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable

// The browser owns fullscreen/orientation on web — nothing for this seam to do there.
@Composable
actual fun PlayerImmersiveEffect(followSensor: Boolean) {}
