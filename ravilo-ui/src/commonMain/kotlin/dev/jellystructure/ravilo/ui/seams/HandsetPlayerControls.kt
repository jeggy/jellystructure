package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable

/**
 * R244 (FR-R244-5/7) — the handset player's platform hooks that no existing seam offered:
 *
 * - **Brightness** (FR-R244-5): scoped to the player's *window*, never the device default. There was no
 *   brightness API anywhere in the codebase before this phase (spec open question 1). The web actual
 *   answers `null` — the browser owns brightness, and a CSS filter would fake a capability rather than
 *   decline it (the `PlayerImmersiveEffect` precedent: its web actual is an empty body). A null setter
 *   means the left-half swipe is simply not offered on that platform.
 * - **Volume** (FR-R244-5): the media stream's level, 0–1. Web: null (the browser owns it).
 * - **System rotation lock** (FR-R244-7): true only while the *system* has rotation locked to portrait,
 *   which is the one condition under which the rotate button appears. [setLandscape] forces landscape
 *   for the player (and back to sensor-following) while that lock is on.
 *
 * Read once per composition of the player via [rememberHandsetPlayerControls]; every value here is a
 * snapshot or a setter, never a reactive stream — the chrome re-reads on each gesture.
 */
class HandsetPlayerControls(
    val brightness: Float?,
    val setBrightness: ((Float) -> Unit)?,
    val volume: Float?,
    val setVolume: ((Float) -> Unit)?,
    val systemRotationLocked: Boolean,
    val setLandscape: (Boolean) -> Unit,
)

@Composable
expect fun rememberHandsetPlayerControls(): HandsetPlayerControls
