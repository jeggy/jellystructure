package dev.jellystructure.ravilo.player

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * The GPL-containment boundary for Ravilo's Android playback (jellyfin `media3-ffmpeg-decoder`,
 * GPL-3.0). Builds a [RenderersFactory] that **prefers the FFmpeg extension decoders for AUDIO only**,
 * giving Ravilo the same automatic DTS / TrueHD / AC3 / E-AC3 handling as jellyfin-androidtv, while
 * keeping **hardware MediaCodec for all video**.
 *
 * `DefaultRenderersFactory` discovers the FFmpeg renderer (`androidx.media3.decoder.ffmpeg.*`) on the
 * classpath via reflection when the extension mode is `PREFER`, so no explicit audio wiring is needed.
 * The codec/format selection (direct-play vs transcode) is resolved server-side; this module only
 * provides the decode engine.
 *
 * Bug fix: the jellyfin ffmpeg artifact also ships an experimental `FfmpegVideoRenderer`, and the plain
 * factory-level `EXTENSION_RENDERER_MODE_PREFER` preferred it for **video** as well. That software video
 * path is both wasteful on a TV and — verified on a Bravia — leaves the player with **no working video
 * decoder at all** when a file carries an oddly-muxed extra video track: the "Server Farm" TURG
 * release muxes a `cover.png` as a non-attached-picture video stream, which Media3's Matroska extractor
 * exposes as `video/x-unknown`. With the FFmpeg video renderer in the mix the player opened but never
 * created any video decoder (no MediaCodec, no error — a black screen). Forcing video to the platform
 * `MediaCodecVideoRenderer` (extension mode OFF for video only) makes the track selector pick the real
 * h264/hevc track and ignore the un-decodable cover track. Audio still uses the preferred FFmpeg path.
 */
object RaviloRenderers {
    fun create(context: Context): RenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>,
            ) {
                // Ignore the caller's mode (PREFER) for video — hardware MediaCodec only, never the
                // preferred experimental FFmpeg software video renderer. Audio (buildAudioRenderers) is
                // untouched and still honours the factory's PREFER mode.
                super.buildVideoRenderers(
                    context,
                    EXTENSION_RENDERER_MODE_OFF,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    allowedVideoJoiningTimeMs,
                    out,
                )
            }
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
}
