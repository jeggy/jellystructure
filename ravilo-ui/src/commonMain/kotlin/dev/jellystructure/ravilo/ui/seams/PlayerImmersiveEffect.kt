package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable

/**
 * Forces landscape orientation and hides the system bars for as long as the player screen is
 * composed, restoring whatever the host had before on dispose. A no-op on platforms with no such
 * concept (web — the browser owns fullscreen/orientation).
 *
 * TV is unaffected in practice: its activity is already permanently landscape + immersive
 * (`MainActivity`'s own `onCreate`), so this just re-applies the same state and restores it to
 * the same state on dispose. The phone app, by contrast, is portrait-locked outside the player
 * (`ravilo-phone`'s manifest) with system bars visible — this is what actually lets its player go
 * landscape and edge-to-edge.
 */
// R244 (FR-R244-7) — [followSensor] (a handset) stops forcing sensor-landscape: the player follows the
// sensor and honours the system's own rotation lock (portrait when locked — which is exactly when the
// rotate button appears, see HandsetPlayerControls). The TV keeps the forced-landscape behaviour.
@Composable
expect fun PlayerImmersiveEffect(followSensor: Boolean = false)
