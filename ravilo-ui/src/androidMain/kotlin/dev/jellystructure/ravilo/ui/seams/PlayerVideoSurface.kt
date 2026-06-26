package dev.jellystructure.ravilo.ui.seams

import android.view.TextureView
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
    // On the TextureView path (API 21+) ExoPlayer applies rotation itself, so width/height already
    // reflect the on-screen orientation — no swap needed.
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
                // R55: wrap TextureView + SubtitleView overlay in a FrameLayout so cues render
                // over the video. We do NOT adopt PlayerView — all transport chrome is custom Compose.
                val frame = FrameLayout(ctx)
                val texture = TextureView(ctx)
                val subtitles = SubtitleView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                }
                frame.addView(texture, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ))
                frame.addView(subtitles)
                player.setVideoTextureView(texture)
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
