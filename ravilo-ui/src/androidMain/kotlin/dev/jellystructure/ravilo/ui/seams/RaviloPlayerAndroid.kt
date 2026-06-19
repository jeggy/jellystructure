package dev.jellystructure.ravilo.ui.seams

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import dev.jellystructure.ravilo.ui.RaviloAppContext

/**
 * Android actual backed by ExoPlayer/Media3.
 *
 * The Android engine is the seam that will be swapped for the forked
 * jellyfin-androidtv playback stack (`:ravilo-player` module, R14 full impl)
 * once the fork is vendored in. For now ExoPlayer covers direct-play URLs.
 */
actual class RaviloPlayer actual constructor() {
    private val ctx: Context get() = RaviloAppContext.get()
    private val exo: ExoPlayer by lazy { ExoPlayer.Builder(ctx).build() }

    actual fun load(streamUrl: String, startPositionMs: Long) {
        exo.setMediaItem(MediaItem.fromUri(streamUrl))
        exo.seekTo(startPositionMs)
        exo.prepare()
    }

    actual fun play() { exo.play() }
    actual fun pause() { exo.pause() }
    actual fun seekTo(positionMs: Long) { exo.seekTo(positionMs) }
    actual fun release() { exo.release() }
    actual val positionMs: Long get() = exo.currentPosition
    actual val isPlaying: Boolean get() = exo.isPlaying
}
