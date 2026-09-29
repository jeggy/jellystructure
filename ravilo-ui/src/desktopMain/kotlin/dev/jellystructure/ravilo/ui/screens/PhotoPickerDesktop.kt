package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private val PHOTO_TYPES = mapOf(
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif",
)

/** R328 (FR-R328-3) — the system's own open dialog (Finder's on a Mac), images only. Cancel calls nothing. */
@OptIn(ExperimentalEncodingApi::class)
@Composable
actual fun rememberChoosePhotoLauncher(onPicked: (PickedPhoto) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onPicked)
    return remember {
        {
            runCatching {
                val dialog = FileDialog(null as Frame?, "", FileDialog.LOAD).apply {
                    setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in PHOTO_TYPES }
                    isMultipleMode = false
                }
                dialog.isVisible = true
                val file = dialog.files.firstOrNull() ?: dialog.file?.let { File(dialog.directory, it) }
                val type = file?.extension?.lowercase()?.let(PHOTO_TYPES::get)
                if (file != null && type != null) current(PickedPhoto(Base64.Default.encode(file.readBytes()), type))
            }.onFailure { println("Ravilo: could not read the photo: ${it.message}") }
            Unit
        }
    }
}

/** Never offered on the desktop ([offersTakePhoto]); the same dialog if anything ever calls it. */
@Composable
actual fun rememberTakePhotoLauncher(onPicked: (PickedPhoto) -> Unit): () -> Unit = rememberChoosePhotoLauncher(onPicked)

actual val offersTakePhoto: Boolean = false
