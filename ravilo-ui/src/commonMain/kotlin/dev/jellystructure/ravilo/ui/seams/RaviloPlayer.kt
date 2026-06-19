package dev.jellystructure.ravilo.ui.seams

/** Platform-specific video player. Stub impls in androidMain/wasmJsMain; real impl at R14. */
expect class RaviloPlayer() {
    fun load(streamUrl: String, startPositionMs: Long)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun release()
    val positionMs: Long
    val isPlaying: Boolean
}
