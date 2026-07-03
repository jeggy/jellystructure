@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.browser.document
import org.w3c.dom.HTMLIFrameElement
import kotlin.js.ExperimentalWasmJsInterop

/**
 * R163: same "can't touch the Compose canvas" constraint as [RaviloPlayer]'s `<video>` element
 * (RaviloPlayerWasm.kt) — a DOM `<iframe>` fixed above the canvas (`z-index: 2`, matching the video's
 * chrome-hidden z-order), `pointer-events: none` so the provider's own player chrome (already disabled
 * via `controls=0`) never intercepts input; [dev.jellystructure.ravilo.ui.components.TrailerOverlay]'s
 * Close control handles Back/Select via Compose's own key routing regardless of DOM z-order.
 */
@Composable
actual fun TrailerEmbed(site: String, key: String, modifier: Modifier) {
    val url = remember(site, key) { trailerEmbedUrl(site, key) }
    DisposableEffect(url) {
        val iframe = (document.createElement("iframe") as HTMLIFrameElement).also { f ->
            f.style.cssText = "position:fixed;top:0;left:0;width:100%;height:100%;border:0;z-index:2;pointer-events:none;background:#000"
            f.src = url
            f.setAttribute("allow", "autoplay; encrypted-media; picture-in-picture")
            document.body?.appendChild(f)
        }
        onDispose {
            // Tearing the element down (rather than just clearing src) fully stops playback.
            runCatching { document.body?.removeChild(iframe) }
        }
    }
}
