package dev.jellystructure.ravilo.ui.seams

/** R376 (FR-R376-8) — the web's control only; this platform's player has no picture-in-picture here. */
actual fun RaviloPlayer.pictureInPictureAvailable(): Boolean = false
actual fun RaviloPlayer.togglePictureInPicture() {}
