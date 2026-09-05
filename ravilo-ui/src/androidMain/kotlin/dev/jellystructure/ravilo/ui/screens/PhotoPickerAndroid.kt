package dev.jellystructure.ravilo.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import dev.jellystructure.ravilo.ui.RaviloAppContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** R234 — `GetContent` opens the system photo picker (Android 13+) / gallery chooser, which already
 *  offers "Camera" as one of its own entries on most devices — see [rememberChoosePhotoLauncher]'s
 *  own doc for why this is shared by both entry points rather than a distinct camera intent. */
@Composable
@OptIn(ExperimentalEncodingApi::class)
actual fun rememberChoosePhotoLauncher(onPicked: (PickedPhoto) -> Unit): () -> Unit {
    val onPickedState = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ctx = RaviloAppContext.get()
        val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@rememberLauncherForActivityResult
        val contentType = ctx.contentResolver.getType(uri) ?: "image/jpeg"
        onPickedState.value(PickedPhoto(Base64.Default.encode(bytes), contentType))
    }
    return { launcher.launch("image/*") }
}

@Composable
actual fun rememberTakePhotoLauncher(onPicked: (PickedPhoto) -> Unit): () -> Unit =
    rememberChoosePhotoLauncher(onPicked)
