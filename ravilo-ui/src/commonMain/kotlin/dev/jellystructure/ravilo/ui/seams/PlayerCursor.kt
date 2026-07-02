package dev.jellystructure.ravilo.ui.seams

/**
 * R157 (FR-R157-3.2) — hides/shows the OS mouse cursor over the player. No-op on Android/TV (no
 * cursor concept); on web, toggles the document body's CSS cursor so it disappears after a few
 * seconds of inactivity during playback and reappears the moment the mouse moves.
 */
expect fun setPointerCursorHidden(hidden: Boolean)
