package dev.jellystructure.ravilo.ui.seams

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
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
    // R163 dev-review addendum 4 — YouTube error 153: `loadUrl(url)` makes the embed URL the WebView's
    // very FIRST navigation, so there is no referring page and no Referer/Origin header for the
    // provider's embed validation to check — that's exactly what error 153 rejects. Fix: give the
    // WebView a real page context first (`loadDataWithBaseURL` at some https origin, hosting an
    // <iframe> to the embed URL) so the iframe request carries a proper referrer.
    //
    // Bug fix: that origin was the provider's OWN domain (youtube.com) — confirmed live (soveværelse
    // TV) and reproduced headlessly (Playwright) that this makes YouTube's embed validation reject
    // the video with "Error code: 152 - 4", even for videos that are public/embeddable everywhere
    // else (YouTube's own oEmbed endpoint serves this exact video fine). Likely cause: a page whose
    // origin/referrer IS youtube.com embedding a youtube.com iframe doesn't look like a legitimate
    // third-party embed (which is what every real site's trailer embed actually is) — it looks like
    // YouTube embedding itself, which its own validation appears to special-case and reject. A
    // neutral, unrelated origin (confirmed working both live and headlessly) fixes it; it doesn't
    // need to resolve anywhere since `loadDataWithBaseURL` never fetches it — it's only used as the
    // Origin/Referer metadata for the iframe's own request.
    val embedOrigin = "https://ravilotv.invalid"
    val html = """
        <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body{margin:0;padding:0;background:#000;overflow:hidden}iframe{position:fixed;inset:0;width:100%;height:100%;border:0}</style>
        </head><body>
        <iframe src="$url" allow="autoplay; encrypted-media; picture-in-picture" referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe>
        </body></html>
    """.trimIndent()
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                // The YouTube/Vimeo iframe player needs local/session storage to initialize correctly.
                settings.domStorageEnabled = true
                // Autoplay with sound is muted by WebView by default unless this is relaxed — otherwise
                // `autoplay=1` in the embed URL silently no-ops (R163 dev-review addendum 2).
                settings.mediaPlaybackRequiresUserGesture = false
                // The provider's iframe is third-party relative to our synthetic origin above; Android
                // WebView blocks third-party cookies by default, and the embed relies on them.
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                loadDataWithBaseURL(embedOrigin, html, "text/html", "utf-8", null)
                tag = key
            }
        },
        update = { webView ->
            if (webView.tag != key) {
                webView.loadDataWithBaseURL(embedOrigin, html, "text/html", "utf-8", null)
                webView.tag = key
            }
        },
        onRelease = { webView ->
            // Tear down rather than just navigating away — "closing stops playback" per the spec.
            webView.loadUrl("about:blank")
            webView.destroy()
        },
    )
}
