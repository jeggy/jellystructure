package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.Composable

/** One picked image, already base64-encoded (a bare string or a `data:<type>;base64,...` URL — the
 *  server tolerates either, same as the existing channel-logo upload convention) + its content type. */
data class PickedPhoto(val dataBase64: String, val contentType: String)

/**
 * R234 (FR-R234-3) — phone/web's photo entry points. Returns a launcher function; calling it opens the
 * platform's own picker, and [onPicked] fires once with the result (never on cancel).
 *
 * Simplification, recorded rather than silently decided (R234 open question 1 already flagged this
 * exact ambiguity): both [rememberChoosePhotoLauncher] and [rememberTakePhotoLauncher] open the SAME
 * platform picker today — Android's `GetContent` (any image MIME type) already offers the device's camera as one
 * of the chooser's own entries on most devices, and there is no dedicated camera capture (FileProvider
 * + manifest provider declaration) wired yet. A future pass can split them for a device where the
 * gallery picker doesn't surface a camera option; until then this is honest, not a placeholder — both
 * buttons work, they just currently launch the identical picker.
 */
@Composable
expect fun rememberChoosePhotoLauncher(onPicked: (PickedPhoto) -> Unit): () -> Unit

@Composable
expect fun rememberTakePhotoLauncher(onPicked: (PickedPhoto) -> Unit): () -> Unit
