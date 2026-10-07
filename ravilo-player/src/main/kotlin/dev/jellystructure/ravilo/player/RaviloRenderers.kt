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
 * GPL-3.0). Builds the [RenderersFactory] for both players, with **hardware MediaCodec for all video**.
 *
 * R379 — audio follows jellyfin-android (`PlayerViewModel.setupPlayer`): the device's own decoders first
 * (`EXTENSION_RENDERER_MODE_ON`), the FFmpeg extension only for a format nothing on the device decodes (DTS and
 * TrueHD on most devices). [preferExtensions] = `EXTENSION_RENDERER_MODE_PREFER`, which jellyfin-android switches to
 * only after a platform decoder failed (the video player's retry, `RaviloPlayer`). Until R379 Ravilo always
 * preferred FFmpeg, and Media3's FFmpeg JNI builds its resampler once from the first frame's channel layout: an AC3
 * stream that went 5.1 → stereo → 5.1 for three frames (an HDTV capture, 2026-10-07) crashed the app natively.
 * `DefaultRenderersFactory` discovers the FFmpeg renderer (`androidx.media3.decoder.ffmpeg.*`) on the classpath
 * via reflection when the extension mode is ON or PREFER, so no explicit audio wiring is needed. The
 * codec/format selection (direct-play vs transcode) is resolved server-side; this module only provides the
 * decode engine.
 *
 * Bug fix: the jellyfin ffmpeg artifact also ships an experimental `FfmpegVideoRenderer`, and the plain
 * factory-level extension mode applied to it for **video** as well. That software video
 * path is both wasteful on a TV and — verified on a Bravia — leaves the player with **no working video
 * decoder at all** when a file carries an oddly-muxed extra video track: the "Server Farm" TURG
 * release muxes a `cover.png` as a non-attached-picture video stream, which Media3's Matroska extractor
 * exposes as `video/x-unknown`. With the FFmpeg video renderer in the mix the player opened but never
 * created any video decoder (no MediaCodec, no error — a black screen). Forcing video to the platform
 * `MediaCodecVideoRenderer` (extension mode OFF for video only) makes the track selector pick the real
 * h264/hevc track and ignore the un-decodable cover track. Audio uses the factory's mode.
 */
object RaviloRenderers {
    fun create(context: Context, preferExtensions: Boolean = false): RenderersFactory =
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
                // Ignore the caller's mode (ON / PREFER) for video — hardware MediaCodec only, never the
                // experimental FFmpeg software video renderer. Audio (buildAudioRenderers) is
                // untouched and honours the factory's mode.
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
            .setExtensionRendererMode(
                if (preferExtensions) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            .setEnableDecoderFallback(true)
}
