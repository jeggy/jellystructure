package dev.jellystructure.ravilo.ui.seams

import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.SubtitleView

@Composable
actual fun PlayerVideoSurface(player: RaviloPlayer, modifier: Modifier) {
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
        modifier = modifier,
    )
}
