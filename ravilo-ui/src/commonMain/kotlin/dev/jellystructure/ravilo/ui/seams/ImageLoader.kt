package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.jellystructure.ravilo.ui.LocalServerBaseUrl
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme

@Composable
fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Crop,
    /**
     * R87: solid color painted under the image while it loads. When null, a full-bleed (Crop) image
     * gets the theme `surfaceVariant`; a Fit image (logo) gets no placeholder so it never flashes an
     * opaque box. Pass a content-related color (poster hash tint, hero brand color) for the nicest look.
     */
    placeholderColor: Color? = null,
) {
    // R85: relative paths (e.g. /api/tv/image/{id}/poster) are resolved against the server base URL.
    // Absolute TMDB/Jellyfin URLs (https://…) pass through unchanged.
    val baseUrl = LocalServerBaseUrl.current
    val resolved = if (url.startsWith("/") && baseUrl.isNotBlank()) "$baseUrl$url" else url

    // R87: build the request per-call so the crossfade is a short ~220ms "settle," not the 600ms
    // global "pop." Coil skips the crossfade for memory-cache hits, so prefetched (R88) / revisited
    // images appear instantly with no fade.
    val ctx = LocalPlatformContext.current
    val request = remember(resolved) {
        ImageRequest.Builder(ctx)
            .data(resolved)
            .crossfade(RaviloMotion.ImageCrossfadeMs)
            .build()
    }

    // A colored placeholder paints synchronously in the same frame; the image crossfades over it —
    // the R84 "paint first, hydrate over a fixed-size element" pattern, so no blank→pop while scrolling.
    // Logos (Fit) stay transparent unless a caller explicitly asks for a placeholder color.
    val fill = placeholderColor ?: RaviloTheme.colors.surfaceVariant
    val solid = remember(fill) { ColorPainter(fill) }
    val placeholder = if (placeholderColor != null || contentScale != ContentScale.Fit) solid else null

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        alignment = alignment,
        placeholder = placeholder,
        error = placeholder,
        fallback = placeholder,
    )
}
