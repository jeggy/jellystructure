package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * R163 — embedded YouTube/Vimeo trailer player. TMDB trailers have no direct byte stream (Phase 130
 * stores only a provider reference), so this is a **bounded exception** like [RaviloPlayer] itself —
 * an embedded provider player, not Media3/ExoPlayer.
 *
 * Android actual: an `AndroidView`-hosted `WebView` (the iframe embed IS YouTube's/Vimeo's official
 * player, so this is the sanctioned playback path). Web actual: a DOM `<iframe>` (the Compose canvas
 * can't host it directly — same constraint documented on [RaviloPlayer]/[RaviloPlayerWasm]).
 *
 * `controls=0` (YouTube) / minimal chrome (Vimeo) — the provider's own player UI is never meant to be
 * interactive; [dev.jellystructure.ravilo.ui.components.TrailerOverlay]'s Compose Close button owns
 * all input, per the spec's "surface owns input while open" invariant.
 */
@Composable
expect fun TrailerEmbed(site: String, key: String, modifier: Modifier = Modifier)

/** YouTube: `youtube-nocookie.com` (privacy-enhanced, no controls, autoplay). Vimeo: minimal chrome. */
fun trailerEmbedUrl(site: String, key: String): String =
    if (site.equals("vimeo", ignoreCase = true))
        "https://player.vimeo.com/video/$key?autoplay=1&title=0&byline=0&portrait=0&background=0"
    else
        "https://www.youtube-nocookie.com/embed/$key?autoplay=1&controls=0&modestbranding=1&rel=0&playsinline=1"
