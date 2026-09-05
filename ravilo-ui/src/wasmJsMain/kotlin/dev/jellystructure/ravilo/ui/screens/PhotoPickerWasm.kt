package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader

/** R234 — same hidden `<input type="file">` + `FileReader.readAsDataURL` idiom the admin frontend's
 *  channel-logo upload already uses (`RaviloConfig.kt`); [PickedPhoto.dataBase64] carries the whole
 *  `data:<type>;base64,...` URL through unchanged, same as that upload passes it to the server. */
@Composable
actual fun rememberChoosePhotoLauncher(onPicked: (PickedPhoto) -> Unit): () -> Unit {
    val onPickedState = rememberUpdatedState(onPicked)
    val input = remember {
        (document.createElement("input") as HTMLInputElement).apply {
            type = "file"
            accept = "image/*"
            style.display = "none"
            document.body?.appendChild(this)
            addEventListener("change", {
                val file: File = files?.item(0) ?: return@addEventListener
                val reader = FileReader()
                reader.onload = {
                    val dataUrl = (reader.result as? JsString)?.toString().orEmpty()
                    if (dataUrl.isNotEmpty()) {
                        val contentType = dataUrl.substringAfter("data:", "").substringBefore(";").ifBlank { file.type.ifBlank { "image/jpeg" } }
                        onPickedState.value(PickedPhoto(dataUrl, contentType))
                    }
                }
                reader.readAsDataURL(file)
            })
        }
    }
    return { input.click() }
}

@Composable
actual fun rememberTakePhotoLauncher(onPicked: (PickedPhoto) -> Unit): () -> Unit =
    rememberChoosePhotoLauncher(onPicked)
