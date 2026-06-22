package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.SubTrack

data class PlayerAudioTrack(val index: Int, val label: String, val language: String?)
data class PlayerSubtitleTrack(
    val index: Int,
    val label: String,
    val language: String?,
    val forced: Boolean = false,
    val isDefault: Boolean = false,
)

/**
 * Platform-specific video player seam.
 *
 * Android actual: ExoPlayer/Media3 (stub for R14; full forked jellyfin-androidtv engine in R14 final).
 * Web actual: browser-native <video> + hls.js.
 *
 * Control plane (start/stop/progress) stays in PlayerStore/TvApiClient; only the byte stream is here.
 */
expect class RaviloPlayer() {
    /** Load a stream URL starting at [startPositionMs]. Subtitle tracks may be added as external tracks. */
    fun load(streamUrl: String, startPositionMs: Long, subtitles: List<SubTrack>)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    /** Select an audio track by its index in [audioTracks]. */
    fun selectAudioTrack(index: Int)

    /** Select a subtitle track by its index in [subtitleTracks], or -1 to disable subtitles. */
    fun selectSubtitleTrack(index: Int)

    fun release()

    val positionMs: Long
    val durationMs: Long
    val bufferedMs: Long
    val isPlaying: Boolean
    val isEnded: Boolean

    /** Audio track list, discovered from the stream after load. May be empty until media is ready. */
    val audioTracks: List<PlayerAudioTrack>

    /** Subtitle track list, discovered from the stream after load (embedded + sideloaded externals). */
    val subtitleTracks: List<PlayerSubtitleTrack>
}
