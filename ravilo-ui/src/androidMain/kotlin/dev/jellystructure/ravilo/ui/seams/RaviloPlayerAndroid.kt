package dev.jellystructure.ravilo.ui.seams

actual class RaviloPlayer actual constructor() {
    actual fun load(streamUrl: String, startPositionMs: Long) {}
    actual fun play() {}
    actual fun pause() {}
    actual fun seekTo(positionMs: Long) {}
    actual fun release() {}
    actual val positionMs: Long get() = 0L
    actual val isPlaying: Boolean get() = false
}
