package dev.jellystructure.ravilo.ui.seams

import androidx.compose.ui.graphics.Color

/**
 * R157 — the player screen's root fill color. Opaque on Android, where the video surface is
 * in-scene (Compose paints on top of it via a normal SurfaceView). Transparent on web, where the real
 * <video> element sits behind the (now non-opaque, see ravilo-web's CanvasBasedWindow(opaque=false))
 * Compose canvas — an opaque root fill there would paint over the video exactly like before this
 * phase. Player chrome (scrims, controls, next-up card, subtitles) keeps rendering normally on top.
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
