package dev.jellystructure.ravilo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import jellystructure.ravilo_ui.generated.resources.Res
import jellystructure.ravilo_ui.generated.resources.web_fallback
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.preloadFont

/**
 * R315 (FR-R315-2/3) — Compose on the web (Compose Multiplatform 1.9.3) has no system fonts: a character
 * that neither Sora nor Space Grotesk carries is drawn as a box. This registers the bundled fallback font
 * (a Noto subset holding exactly the app's symbols and every language name, `scripts/build-web-fallback-font.py`)
 * with the font resolver before [content] lays out any text, which is the mechanism Compose documents for
 * the web. A preloaded font also wins over the automatic download 1.12.0 introduces, so the app never
 * fetches fonts from a third party at runtime.
 *
 * The app waits for it at most [FALLBACK_WAIT_MS]: one ~100 KB file from the app's own origin, and never a
 * reason not to start (a failed load leaves only the rare symbol as a box, as before this phase).
 */
private const val FALLBACK_WAIT_MS = 3_000L

@OptIn(ExperimentalResourceApi::class)
@Composable
fun WithWebFallbackFont(content: @Composable () -> Unit) {
    val font = preloadFont(Res.font.web_fallback).value
    val resolver = LocalFontFamilyResolver.current
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(font) {
        if (font != null) {
            runCatching { resolver.preload(FontFamily(font)) }
            ready = true
        }
    }
    LaunchedEffect(Unit) {
        delay(FALLBACK_WAIT_MS)
        ready = true
    }
    if (ready) content()
}
