package dev.jellystructure.ravilo.ui.seams

import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
actual fun PlayerVideoSurface(player: RaviloPlayer, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { player.setVideoTextureView(it) }
        },
        modifier = modifier,
    )
}
