package dev.jellystructure.ravilo.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.jellystructure.ravilo.ui.i18n.t
import dev.jellystructure.ravilo.ui.raviloBaseUrl
import dev.jellystructure.shared.raviloVersion
import java.net.URI
import javax.imageio.ImageIO

internal object AppImages {
    private fun load(name: String): Painter? = runCatching {
        AppImages::class.java.getResourceAsStream("/$name")?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
    }.getOrNull()

    val icon: Painter? by lazy { load("ravilo-icon.png") }
    val mark: Painter? by lazy { load("ravilo-mark.png") }
}

/** FR-R328-5 — About shows the mark, the version and *Signed in to {server}*. */
@Composable
internal fun AboutWindow(lang: String, onClose: () -> Unit) {
    val server = remember { raviloBaseUrl().takeIf { it.isNotBlank() }?.let { runCatching { URI(it).host }.getOrNull() ?: it } }
    DialogWindow(
        onCloseRequest = onClose,
        state = rememberDialogState(size = DpSize(360.dp, 320.dp)),
        title = t("mac.menu_about", lang),
        resizable = false,
        icon = AppImages.icon,
        onPreviewKeyEvent = { ev -> (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape).also { if (it) onClose() } },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF000B25)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppImages.mark?.let { Image(it, contentDescription = null, modifier = Modifier.size(96.dp)) }
            Spacer(Modifier.height(12.dp))
            Text("Ravilo", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(t("mac.about_version", lang, mapOf("version" to raviloVersion())), color = Color(0xFFB8BCD0), fontSize = 14.sp)
            if (server != null) {
                Spacer(Modifier.height(4.dp))
                Text(t("mac.about_signed_in", lang, mapOf("server" to server)), color = Color(0xFFB8BCD0), fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
