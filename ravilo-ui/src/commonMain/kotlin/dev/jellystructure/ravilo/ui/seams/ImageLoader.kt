package dev.jellystructure.ravilo.ui.seams

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
import kotlinx.coroutines.CancellationException
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
    /** R308 (FR-R308-3) — e.g. a source-in tint that re-inks a logo the server judged invisible on its
     *  plate. Null = draw the image as it is (every caller before R308). */
    colorFilter: ColorFilter? = null,
    /** DEB-5837 (FR-6) — drawn over the placeholder colour while a load has failed and waits for its retry;
     *  a retry that succeeds crossfades the image over it. Null = today's placeholder. */
    onFailed: (@Composable () -> Unit)? = null,
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

    // DEB-4396 (FR-1) — a failed load is tried again: 1, 2, 4, 8, 16 s, then every 30 s while this tile is
    // composed, and at once when the network comes back or the app returns to the foreground. [attempt] keys the
    // AsyncImage so a retry is a fresh load; the tile looks the same while it waits (the placeholder below).
    var attempt by remember(resolved) { mutableIntStateOf(0) }
    var failures by remember(resolved) { mutableIntStateOf(0) }
    var failed by remember(resolved) { mutableStateOf(false) }
    // Unlike [failed] this stays up through a retry's load, so the onFailed slot does not flicker every 30 s.
    var showFailed by remember(resolved) { mutableStateOf(false) }
    if (failed) {
        LaunchedEffect(resolved, failures) {
            ArtworkRetry.waitForRetry(failures)
            failed = false
            attempt++
        }
    }

    // A colored placeholder paints synchronously in the same frame; the image crossfades over it —
    // the R84 "paint first, hydrate over a fixed-size element" pattern, so no blank→pop while scrolling.
    // Logos (Fit) stay transparent unless a caller explicitly asks for a placeholder color.
    val fill = placeholderColor ?: RaviloTheme.colors.surfaceVariant
    val solid = remember(fill) { ColorPainter(fill) }
    val placeholder = if (placeholderColor != null || contentScale != ContentScale.Fit) solid else null

    @Composable fun image(m: Modifier) = key(attempt) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = m,
            contentScale = contentScale,
            alignment = alignment,
            placeholder = placeholder,
            error = placeholder,
            fallback = placeholder,
            colorFilter = colorFilter,
            // A request cancelled because the tile left the screen is not a failure (and its effect is gone anyway).
            onError = { e -> if (e.result.throwable !is CancellationException) { failures++; failed = true; showFailed = true } },
            onSuccess = { failures = 0; showFailed = false },
        )
    }
    if (onFailed == null) {
        image(modifier)
    } else {
        Box(modifier) {
            image(Modifier.matchParentSize())
            if (showFailed) onFailed()
        }
    }
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

/**
 * DEB-4396 (FR-4.3) — enqueue [urls] (proxy paths or absolute) on the singleton loader now, from a non-composable
 * point such as the first composition of the app. The URLs must be the ones the tiles request, `?w=` included.
 */
fun prefetchImages(ctx: PlatformContext, baseUrl: String, urls: List<String>) {
    val loader = SingletonImageLoader.get(ctx)
    urls.forEach { u -> if (u.isNotBlank()) loader.enqueue(ImageRequest.Builder(ctx).data(resolveUrl(u, baseUrl)).build()) }
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

// ─── DEB-4290: vertical (row-ahead) prefetch ─────────────────────────────────

/** How many rows below the last visible one get their first tiles warmed. */
const val ROW_PREFETCH_LOOKAHEAD_ROWS = 3

/** How many tiles from the start of each such row — about what a TV row shows before it scrolls sideways. */
const val ROW_PREFETCH_TILES_PER_ROW = 8

/**
 * DEB-4290 — the rows to warm now: the [lookaheadRows] below [lastVisibleRow] that [warmedThrough] has not
 * already covered (a high-water mark, like R99's — scrolling back up, or Down within a row, enqueues
 * nothing). Empty when nothing is visible yet (`lastVisibleRow < 0`) or the page ends first.
 */
fun rowsToPrefetch(lastVisibleRow: Int, rowCount: Int, warmedThrough: Int, lookaheadRows: Int = ROW_PREFETCH_LOOKAHEAD_ROWS): IntRange {
    if (lastVisibleRow < 0) return IntRange.EMPTY
    val start = maxOf(lastVisibleRow + 1, warmedThrough + 1)
    val end = minOf(lastVisibleRow + lookaheadRows, rowCount - 1)
    return if (start > end) IntRange.EMPTY else start..end
}

/**
 * DEB-4290 — a vertical page's rows are composed only as they scroll in, so a row's first posters were
 * first requested the frame the row appeared (decode + a crossfade on the UI-thread-adjacent path: ~17 %
 * janky frames on the first fast scroll down TV Home). [rows] is the page's rows in LazyColumn order as
 * (item key, tile URLs, empty for a row with no images to warm); as the last visible item advances, the
 * first [tilesPerRow] URLs of the next [ROW_PREFETCH_LOOKAHEAD_ROWS] rows are enqueued, so they are
 * memory-cache hits by the time focus gets there. The URLs must be the ones the tiles request (same
 * `?w=`), or the prefetch warms an entry nobody reads.
 */
@Composable
fun PrefetchLazyColumnRowsEffect(
    listState: LazyListState,
    rows: List<Pair<Any, List<String>>>,
    tilesPerRow: Int = ROW_PREFETCH_TILES_PER_ROW,
) {
    val ctx = LocalPlatformContext.current
    val baseUrl = LocalServerBaseUrl.current
    LaunchedEffect(rows, baseUrl) {
        val indexByKey = HashMap<Any, Int>(rows.size).also { m -> rows.forEachIndexed { i, (k, _) -> m[k] = i } }
        var warmedThrough = -1
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull { it.key in indexByKey }?.key }
            .distinctUntilChanged()
            .collect { key ->
                val last = key?.let { indexByKey[it] } ?: return@collect
                val window = rowsToPrefetch(last, rows.size, warmedThrough)
                if (window.isEmpty()) return@collect
                val loader = SingletonImageLoader.get(ctx)
                for (r in window) {
                    for (url in rows[r].second.take(tilesPerRow)) {
                        if (url.isNotBlank()) loader.enqueue(ImageRequest.Builder(ctx).data(resolveUrl(url, baseUrl)).build())
                    }
                }
                warmedThrough = window.last
            }
    }
}
