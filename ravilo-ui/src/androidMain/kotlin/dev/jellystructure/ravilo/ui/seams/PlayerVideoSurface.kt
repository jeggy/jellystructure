package dev.jellystructure.ravilo.ui.seams

import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.SubtitleView

@Composable
actual fun PlayerVideoSurface(player: RaviloPlayer, modifier: Modifier) {
    // R77: collect video geometry and compute display aspect ratio (DAR).
    // pixelWidthHeightRatio (SAR) corrects anamorphic encoding (e.g. DVD 720×480 @ SAR 32:27 → 16:9).
    // ExoPlayer applies rotation itself, so width/height already reflect the on-screen orientation —
    // no swap needed.
    val videoSize by player.videoSize.collectAsState()
    val dar: Float = run {
        val w = videoSize.width
        val h = videoSize.height
        if (w <= 0 || h <= 0) return@run 0f
        w * videoSize.pixelWidthHeightRatio / h
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                // Bug fix (phase-R173): TextureView doesn't correctly propagate HDR (PQ) color/transfer
                // metadata to the display compositor — frames get GL-composited as an ordinary texture,
                // so HDR content renders as if it were SDR gamma (very dark). SurfaceView is its own
                // hardware-composer layer; the decoder's HDR metadata reaches SurfaceFlinger directly and
                // the display tone-maps correctly, with no app-level color-mode API needed — verified
                // against Jellyfin's own Android TV client, which does exactly this (a bare SurfaceView
                // wired via `ExoPlayer.setVideoSurfaceView`, nothing else).
                // R55: wrap SurfaceView + SubtitleView overlay in a FrameLayout so cues render over the
                // video. We do NOT adopt PlayerView — all transport chrome is custom Compose.
                val frame = FrameLayout(ctx)
                val surface = SurfaceView(ctx)
                val subtitles = SubtitleView(ctx).apply {
                    val bottomPx = (28 * ctx.resources.displayMetrics.density).toInt()
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    setPadding(0, 0, 0, bottomPx)
                }
                frame.addView(surface, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ))
                frame.addView(subtitles)
                player.setVideoSurfaceView(surface)
                player.setSubtitleView(subtitles)
                frame
            },
            // R77: constrain to DAR when known — Compose fits the view inside the available space
            // and the PlayerScreen's black background shows through as letterbox/pillarbox bars.
            // Falls back to fillMaxSize() until the first frame is decoded (VideoSize.UNKNOWN).
            modifier = if (dar > 0f) Modifier.aspectRatio(dar) else Modifier.fillMaxSize(),
        )
    }
}
