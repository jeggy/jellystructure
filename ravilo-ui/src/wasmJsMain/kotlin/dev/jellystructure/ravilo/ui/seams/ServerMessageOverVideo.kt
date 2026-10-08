package dev.jellystructure.ravilo.ui.seams

/** R354 (FR-R354-10) / R376 (FR-R376-1) — the `<video>` sits behind the Compose viewport for good now, so the Compose
 *  toast is already above the picture; R354's DOM cards (drawn while R157's swap lifted the video over the canvas)
 *  are gone with the swap. */
actual fun platformServerMessageOverVideo(): ServerMessageOverVideo? = null
