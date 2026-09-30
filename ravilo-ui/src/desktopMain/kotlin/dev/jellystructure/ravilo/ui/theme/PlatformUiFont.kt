package dev.jellystructure.ravilo.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import dev.jellystructure.ravilo.ui.desktop.DesktopPaths
import dev.jellystructure.ravilo.ui.desktop.LinuxPortal

/**
 * R337 (FR-R337-5, Q9, dev review 13) — macOS's system UI font, or GNOME's interface font read through the Settings
 * portal (the Flatpak's runtime ships neither Adwaita Sans nor Cantarell, so "whatever fontconfig says" alone would be
 * DejaVu on some hosts; fontconfig inside the sandbox sees the host's fonts). Without a portal: fontconfig's sans-serif.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
actual val platformUiFontFamily: FontFamily? by lazy {
    val name = if (DesktopPaths.isMac) ".AppleSystemUIFont" else LinuxPortal.interfaceFontFamily() ?: "sans-serif"
    runCatching {
        FontFamily(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { SystemFont(name, it) })
    }.getOrNull()
}
