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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URI
import javax.imageio.ImageIO

internal object AppImages {
    private fun load(name: String): Painter? = runCatching {
        AppImages::class.java.getResourceAsStream("/$name")?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
    }.getOrNull()

    val icon: Painter? by lazy { load("ravilo-icon.png") }
    val mark: Painter? by lazy { load("ravilo-mark.png") }
}

/** How often the About window reads the app's own figures; Activity Monitor's default is 5 s. */
private const val RESOURCE_READ_MS = 2_000L

/**
 * FR-R328-5 — About shows the mark, the version and *Signed in to {server}*. FR-R383-6 — and, on a Mac with the Swift
 * library, the app's own memory, CPU % and idle wakeups, read while the window is open (rates need two reads, so they
 * show a dash for the first moment).
 */
@Composable
internal fun AboutWindow(lang: String, onClose: () -> Unit) {
    val server = remember { raviloBaseUrl().takeIf { it.isNotBlank() }?.let { runCatching { URI(it).host }.getOrNull() ?: it } }
    var figures by remember { mutableStateOf<AppResources.Figures?>(null) }
    LaunchedEffect(Unit) {
        var previous: AppResources.Sample? = null
        while (true) {
            val now = withContext(Dispatchers.Default) { AppResources.read() } ?: break
            figures = AppResources.figures(previous, now)
            previous = now
            delay(if (figures?.cpuPercent == null) 1_000L else RESOURCE_READ_MS)
        }
    }
    DialogWindow(
        onCloseRequest = onClose,
        state = rememberDialogState(size = DpSize(360.dp, if (figures != null) 400.dp else 320.dp)),
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
            figures?.let { f ->
                Spacer(Modifier.height(16.dp))
                for ((key, value) in listOf(
                    "mac.about_memory" to AppResources.formatMemory(f.footprintBytes),
                    "mac.about_cpu" to AppResources.formatCpu(f.cpuPercent),
                    "mac.about_wakeups" to AppResources.formatWakeups(f.wakeupsPerSecond),
                )) {
                    Text(t(key, lang, mapOf("value" to value)), color = Color(0xFF8A8FA8), fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
