package dev.jellystructure.ravilo.ui.seams

/**
 * R376 (FR-R376-8) — the platform's picture-in-picture for this player, where it has one: the browser's
 * (`requestPictureInPicture`, desktop Chrome/Safari and Android Chrome). False everywhere else, and then the control is
 * absent, never greyed.
 */
expect fun RaviloPlayer.pictureInPictureAvailable(): Boolean

/** R376 (FR-R376-8) — enters picture-in-picture, or leaves it when it is already on. */
expect fun RaviloPlayer.togglePictureInPicture()
