package dev.jellystructure.ravilo.ui.seams

import androidx.compose.ui.graphics.Color

/**
 * R157 — the player screen's root fill color, under the video surface. R376 (FR-R376-1) — on the web the surface
 * clears its own pixels so the `<video>` behind the canvas shows through, whatever this fill is.
 */
expect val playerBackdropColor: Color

/**
 * R157 — does tapping empty space on the player screen toggle the chrome, or activate whatever
 * control currently holds D-pad focus? Web convention is "click empty space to show/hide controls"
 * (the video itself has no clickable target); Android/TV convention (unchanged by this phase) is
 * that a tap always acts on the focused control, since D-pad focus IS the pointer on that platform.
 */
expect val playerTapTogglesChrome: Boolean

/**
 * R329 (FR-R329-6) — do ← and → seek (−10 s / +30 s) in the player? On the Mac, yes: a keyboard's arrows seek, as
 * in every desktop player. On a TV they move focus along the transport row (a hidden chrome is revealed first,
 * R251), and a phone has no arrows.
 */
expect val playerArrowsSeek: Boolean
