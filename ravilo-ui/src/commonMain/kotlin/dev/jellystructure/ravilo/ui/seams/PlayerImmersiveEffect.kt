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
@Composable
expect fun PlayerImmersiveEffect()
