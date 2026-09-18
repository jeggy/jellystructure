package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable

/**
 * Forces landscape orientation and hides the system bars for as long as the player screen is
 * composed, restoring whatever the host had before on dispose. A no-op on platforms with no such
 * concept (web — the browser owns fullscreen/orientation).
 *
 * R261 (FR-R261-1) — TV is unaffected **by declaration**, not by observing what the bars happened to be
 * doing on entry: its activity is already permanently immersive for its whole life (`MainActivity`'s own
 * `onCreate`), so the Android actual never touches bars/decor-fit/cutout-mode/keep-screen-on there,
 * entry or dispose (only the shared orientation call runs on both, since the TV activity declares no
 * `screenOrientation` in its manifest and depends on this to go landscape at all). The phone app, by
 * contrast, is portrait-locked outside the player (`ravilo-phone`'s manifest) with system bars visible —
 * this is what actually lets its player go landscape and edge-to-edge, and dispose now shows the bars
 * unconditionally rather than only if they read as "visible" on entry (they never did, on a phone).
 */
// R244 (FR-R244-7) — [followSensor] (a handset) stops forcing sensor-landscape: the player follows the
// sensor and honours the system's own rotation lock (portrait when locked — which is exactly when the
// rotate button appears, see HandsetPlayerControls). The TV keeps the forced-landscape behaviour.
@Composable
expect fun PlayerImmersiveEffect(followSensor: Boolean = false)
