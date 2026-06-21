package dev.jellystructure.ravilo.ui.seams

import android.content.Context
import androidx.media3.exoplayer.RenderersFactory

/**
 * Indirection so `:ravilo-ui` can build ExoPlayer with an FFmpeg-capable [RenderersFactory] without
 * linking the GPL decoder itself.
 *
 * `:ravilo-android` (which depends on the GPL-contained `:ravilo-player`) sets
 * [renderersFactoryProvider] at startup; [RaviloPlayer] uses it when constructing ExoPlayer and
 * falls back to the default renderers when it is unset (e.g. unit tests). This keeps the GPL
 * boundary in `:ravilo-player` while the player code stays in the shared module. See R31.
 */
object RaviloPlayerEngine {
    var renderersFactoryProvider: ((Context) -> RenderersFactory)? = null
}
