package dev.jellystructure.ravilo.player

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory

/**
 * The GPL-containment boundary for Ravilo's Android playback (jellyfin `media3-ffmpeg-decoder`,
 * GPL-3.0). Builds a [RenderersFactory] that **prefers** the FFmpeg extension decoders, giving
 * Ravilo the same automatic DTS / TrueHD / AC3 / E-AC3 handling as jellyfin-androidtv.
 *
 * `DefaultRenderersFactory` discovers the FFmpeg renderer (`androidx.media3.decoder.ffmpeg.*`) on
 * the classpath via reflection when the extension mode is `PREFER`, so no explicit renderer wiring
 * is needed — having the decoder on the app's runtime classpath (via this module) is enough. The
 * codec/format selection itself (direct-play vs transcode) is resolved server-side through
 * `/api/tv/playback/start`; this module only provides the decode engine.
 */
object RaviloRenderers {
    fun create(context: Context): RenderersFactory =
        DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
}
