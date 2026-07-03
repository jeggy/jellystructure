package dev.jellystructure.ravilo.ui.seams

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * R163: the iframe embed IS YouTube's/Vimeo's own official player, hosted in a `WebView` — the
 * sanctioned playback path for a provider that has no direct byte stream (same bounded-exception
 * status as [RaviloPlayer] itself). Unlike the web target, Android has no canvas/DOM conflict: the
 * `WebView` composes as a normal view behind [dev.jellystructure.ravilo.ui.components.TrailerOverlay]'s
 * Compose chrome, both visible and hit-testable via ordinary Android view z-order.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun TrailerEmbed(site: String, key: String, modifier: Modifier) {
    val url = trailerEmbedUrl(site, key)
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                // Autoplay with sound is muted by WebView by default unless this is relaxed — otherwise
                // `autoplay=1` in the embed URL silently no-ops (R163 dev-review addendum 2).
                settings.mediaPlaybackRequiresUserGesture = false
                loadUrl(url)
            }
        },
        update = { webView -> if (webView.url != url) webView.loadUrl(url) },
        onRelease = { webView ->
            // Tear down rather than just navigating away — "closing stops playback" per the spec.
            webView.loadUrl("about:blank")
            webView.destroy()
        },
    )
}
