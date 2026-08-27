package dev.jellystructure.ravilo.ui.seams

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.jellystructure.ravilo.ui.LocalServerBaseUrl
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import kotlinx.coroutines.flow.distinctUntilChanged

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
    /**
     * R96: device-appropriate pixel width for proxy images (/api/tv/image/...). Appended as `?w=`;
     * the backend (R93) honours it for backdrop/still types and caches per width. Ignored for
     * absolute (TMDB/https) URLs. Null = backend default — e.g. a LANDSCAPE tile passes ~640 instead
     * of decoding the full 1920px backdrop (~8.3 MB) into a 256dp slot.
     */
    requestedWidth: Int? = null,
) {
    // R85: relative paths (e.g. /api/tv/image/{id}/poster) are resolved against the server base URL.
    // Absolute TMDB/Jellyfin URLs (https://…) pass through unchanged.
    // R96: append ?w= only for proxy paths (the backend ignores it on absolute URLs anyway).
    val baseUrl = LocalServerBaseUrl.current
    val sized = sizedProxyUrl(url, requestedWidth)
    val resolved = if (sized.startsWith("/") && baseUrl.isNotBlank()) "$baseUrl$sized" else sized

    // R87: build the request per-call so the crossfade is a short ~220ms "settle," not the 600ms
    // global "pop." Coil skips the crossfade for memory-cache hits, so prefetched (R88) / revisited
    // images appear instantly with no fade.
    val ctx = LocalPlatformContext.current
    val request = remember(resolved) {
        ImageRequest.Builder(ctx)
            .data(resolved)
            .crossfade(RaviloMotion.IMAGE_CROSSFADE_MS)
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

// ─── R88: scroll-ahead prefetch helpers ──────────────────────────────────────

private fun resolveUrl(url: String, baseUrl: String): String =
    if (url.startsWith("/") && baseUrl.isNotBlank()) "$baseUrl$url" else url

/**
 * R96: append a `w=` size param to a proxy (/api/tv/image/...) URL so the backend serves a
 * device-appropriate size; returns the URL unchanged for absolute/non-proxy URLs or a null/zero width.
 * Used by BOTH the display path (RemoteImage) and the row prefetch resolver so a tile and its prefetch
 * share ONE cache key — otherwise the prefetch warms the full-size image and the tile then cache-misses
 * on `?w=`. R214: the server may already have appended its own `?v=<version>` cache-buster — appending
 * another bare `?w=` after one would build an invalid `...?v=1?w=200` URL, so join with `&` once a `?`
 * is already present.
 */
fun sizedProxyUrl(url: String, width: Int?): String {
    if (width == null || width <= 0 || !url.startsWith("/api/tv/image/")) return url
    val sep = if ('?' in url) '&' else '?'
    return "$url${sep}w=$width"
}

/**
 * R100: warm a single image (e.g. a detail backdrop) ahead of navigating to it, so it is a
 * memory-cache hit — and paints with no fade — by the time the destination composes. No-op for a
 * null/blank URL. Safe to call from a non-composable click handler ([ctx] captured at composition).
 */
fun prefetchImage(ctx: PlatformContext, baseUrl: String, url: String?) {
    if (url.isNullOrBlank()) return
    SingletonImageLoader.get(ctx).enqueue(ImageRequest.Builder(ctx).data(resolveUrl(url, baseUrl)).build())
}

private const val PREFETCH_LOOKAHEAD = 4

/**
 * Shared prefetch loop. Observes the last-visible index and enqueues up to [lookahead] image
 * requests ahead of it. R99: a `lastEnqueued` high-water-mark means an advancing scroll only
 * enqueues the *newly* exposed items — index 10→[11..14], 11→[15] — instead of re-building an
 * overlapping window's worth of `ImageRequest`s on every item-step. Scroll-back enqueues nothing
 * (those URLs are already warm). The watermark resets when [urls] changes (the effect restarts).
 * Capped at 4 to stay well within the backend's Semaphore(8) gate.
 */
@Composable
private fun PrefetchEffect(stateKey: Any, urls: List<String>, lookahead: Int, lastVisibleIndex: () -> Int) {
    val ctx = LocalPlatformContext.current
    val baseUrl = LocalServerBaseUrl.current
    LaunchedEffect(stateKey, urls, baseUrl) {
        var lastEnqueued = -1
        snapshotFlow { lastVisibleIndex() }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible < 0) return@collect
                val start = maxOf(lastVisible + 1, lastEnqueued + 1)
                val end = lastVisible + lookahead
                if (start > end) return@collect
                val loader = SingletonImageLoader.get(ctx)
                for (i in start..end) {
                    val url = urls.getOrNull(i) ?: break
                    if (url.isBlank()) continue
                    loader.enqueue(ImageRequest.Builder(ctx).data(resolveUrl(url, baseUrl)).build())
                }
                if (end > lastEnqueued) lastEnqueued = end
            }
    }
}

@Composable
fun PrefetchLazyRowEffect(listState: LazyListState, urls: List<String>, lookahead: Int = PREFETCH_LOOKAHEAD) =
    PrefetchEffect(listState, urls, lookahead) { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }

@Composable
fun PrefetchLazyGridEffect(gridState: LazyGridState, urls: List<String>, lookahead: Int = PREFETCH_LOOKAHEAD) =
    PrefetchEffect(gridState, urls, lookahead) { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
