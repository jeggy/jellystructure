package dev.jellystructure.ravilo.ui.seams

import android.content.Context
import android.net.Uri
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.shared.tv.SubTrack

/**
 * Android actual backed by ExoPlayer/Media3.
 *
 * This is the seam that will be replaced by the forked jellyfin-androidtv playback engine
 * (`:ravilo-player` module) at R14 full bring-up. Until then, ExoPlayer covers direct-play
 * and HLS URLs; the forked engine adds DTS/TrueHD/AC3 decode via media3-ffmpeg-decoder.
 */
actual class RaviloPlayer actual constructor() {
    private val ctx: Context get() = RaviloAppContext.get()
    private val exo: ExoPlayer by lazy { ExoPlayer.Builder(ctx).build() }

    actual fun load(streamUrl: String, startPositionMs: Long, subtitles: List<SubTrack>) {
        val subConfigs = subtitles.mapNotNull { sub ->
            val url = sub.url ?: return@mapNotNull null
            val mime = when {
                url.endsWith(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
                url.endsWith(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
                url.endsWith(".ass", ignoreCase = true) || url.endsWith(".ssa", ignoreCase = true) -> MimeTypes.TEXT_SSA
                else -> MimeTypes.TEXT_VTT
            }
            val flags = when {
                sub.isDefault -> C.SELECTION_FLAG_DEFAULT
                sub.forced    -> C.SELECTION_FLAG_FORCED
                else           -> 0
            }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                .setMimeType(mime)
                .setLanguage(sub.language)
                .setLabel(sub.label)
                .setSelectionFlags(flags)
                .build()
        }
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setSubtitleConfigurations(subConfigs)
            .build()
        exo.setMediaItem(mediaItem)
        exo.seekTo(startPositionMs)
        exo.prepare()
    }

    fun setVideoTextureView(tv: TextureView) { exo.setVideoTextureView(tv) }

    actual fun play() { exo.play() }
    actual fun pause() { exo.pause() }
    actual fun seekTo(positionMs: Long) { exo.seekTo(positionMs) }

    actual fun selectAudioTrack(index: Int) {
        val tracks = exo.currentTracks
        var audioGroupIdx = 0
        for (i in 0 until tracks.groups.size) {
            val group = tracks.groups[i]
            if (group.type == C.TRACK_TYPE_AUDIO) {
                if (audioGroupIdx == index) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                        .build()
                    return
                }
                audioGroupIdx++
            }
        }
    }

    actual fun selectSubtitleTrack(subtitleUrl: String?) {
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setIgnoredTextSelectionFlags(if (subtitleUrl == null) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
    }

    actual fun release() { exo.release() }

    actual val positionMs: Long get() = exo.currentPosition.coerceAtLeast(0)
    actual val durationMs: Long get() = exo.duration.let { if (it == C.TIME_UNSET) 0L else it.coerceAtLeast(0) }
    actual val bufferedMs: Long get() = exo.bufferedPosition.coerceAtLeast(0)
    actual val isPlaying: Boolean get() = exo.isPlaying
    actual val isEnded: Boolean get() = exo.playbackState == Player.STATE_ENDED

    actual val audioTracks: List<PlayerAudioTrack>
        get() {
            val result = mutableListOf<PlayerAudioTrack>()
            val tracks = exo.currentTracks
            var idx = 0
            for (i in 0 until tracks.groups.size) {
                val group = tracks.groups[i]
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    val format = group.mediaTrackGroup.getFormat(0)
                    val label = format.label ?: format.language?.uppercase() ?: "Track ${idx + 1}"
                    result += PlayerAudioTrack(idx, label, format.language)
                    idx++
                }
            }
            return result
        }
}
