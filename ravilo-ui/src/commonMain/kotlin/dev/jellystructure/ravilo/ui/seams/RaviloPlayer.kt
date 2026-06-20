package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.SubTrack

data class PlayerAudioTrack(val index: Int, val label: String, val language: String?)

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

    /** Enable a subtitle track by its URL (from SubTrack.url), or null to disable all subtitles. */
    fun selectSubtitleTrack(subtitleUrl: String?)

    fun release()

    val positionMs: Long
    val durationMs: Long
    val bufferedMs: Long
    val isPlaying: Boolean
    val isEnded: Boolean

    /** Audio track list, discovered from the stream after load. May be empty until media is ready. */
    val audioTracks: List<PlayerAudioTrack>
}
