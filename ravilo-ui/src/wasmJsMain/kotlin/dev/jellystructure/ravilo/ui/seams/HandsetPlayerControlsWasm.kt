package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// R244 — the browser owns brightness, volume and orientation; nothing here fakes any of them (the
// PlayerImmersiveEffect precedent). Null setters mean the swipes are simply not offered on web.
@Composable
actual fun rememberHandsetPlayerControls(): HandsetPlayerControls = remember {
    HandsetPlayerControls(
        brightness = null, setBrightness = null,
        volume = null, setVolume = null,
        systemRotationLocked = false,
        setLandscape = {},
    )
}
