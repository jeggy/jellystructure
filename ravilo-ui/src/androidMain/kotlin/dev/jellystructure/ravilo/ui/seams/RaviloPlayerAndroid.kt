package dev.jellystructure.ravilo.ui.seams

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.TextureView
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.media3.session.MediaSession
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.SubTrack

/** Strip ASS/SSA override tags from VTT cue text (R68). */
internal fun cleanCueText(text: String): String =
    text.replace(Regex("""\{\\[^}]*\}"""), "")
        .replace("""\N""", "\n")
        .replace("""\n""", "\n")
        .replace("""\h""", " ")
        .trim()

/**
 * Android actual backed by ExoPlayer/Media3.
 *
 * Exotic-codec support (DTS/TrueHD/AC3/E-AC3) comes from the FFmpeg extension decoders supplied by
 * the GPL-contained `:ravilo-player` module via [RaviloPlayerEngine.renderersFactoryProvider] (R31).
 * When the provider is unset (e.g. tests), this falls back to ExoPlayer's default renderers.
 */
actual class RaviloPlayer actual constructor() {
    private val ctx: Context get() = RaviloAppContext.get()
    private val exo: ExoPlayer by lazy {
        // R56: Media3's MatroskaExtractor already parses embedded VobSub/DVDSub and PGS tracks
        // from MKV containers by default; no custom ExtractorsFactory is needed.
        val builder = ExoPlayer.Builder(ctx)
        RaviloPlayerEngine.renderersFactoryProvider?.invoke(ctx)?.let { builder.setRenderersFactory(it) }
        builder.build().also { player ->
            // R77: capture video geometry so PlayerVideoSurface can apply the correct aspect ratio.
            player.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) { _videoSize.value = size }
            })
        }
    }

    // R44: a MediaSession bound to the player so the OS routes hardware transport keys (Play/Pause/
    // Stop/FF/Rew/Next/Prev) to us and external controllers (Assistant/Bluetooth/Now-Playing) work.
    // ExoPlayer maps the standard session commands to play/pause/seek; the shared chrome stays the
    // source of truth for position polling.
    private val mediaSessionLazy: Lazy<MediaSession> = lazy {
        MediaSession.Builder(ctx, exo).setId("ravilo-player").build()
    }
    private val mediaSession: MediaSession by mediaSessionLazy

    // R77: video geometry for automatic aspect-ratio correction in PlayerVideoSurface.
    private val _videoSize = MutableStateFlow(VideoSize.UNKNOWN)
    val videoSize: StateFlow<VideoSize> = _videoSize

    // R46: server-derived audio metadata (Jellyfin DisplayTitle, in container audio-stream order).
    private var audioMeta: List<AudioTrack> = emptyList()

    actual fun load(streamUrl: String, startPositionMs: Long, subtitles: List<SubTrack>, audio: List<AudioTrack>) {
        audioMeta = audio
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
        mediaSession // touch the lazy session so it's active for the OS while this item plays (R44)
    }

    fun setVideoTextureView(tv: TextureView) { exo.setVideoTextureView(tv) }

    /** R55 — attach a SubtitleView so ExoPlayer's text renderer can forward cues to the UI. */
    fun setSubtitleView(view: SubtitleView) {
        view.setStyle(CaptionStyleCompat(
            Color.WHITE,
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            Color.BLACK,
            null,
        ))
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 0.9f)
        exo.addListener(object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                val cleaned = cueGroup.cues.map { cue ->
                    val raw = cue.text?.toString() ?: return@map cue
                    val clean = cleanCueText(raw)
                    if (clean == raw) cue else cue.buildUpon().setText(clean).build()
                }
                view.setCues(cleaned)
            }
        })
    }

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

    actual fun selectSubtitleTrack(index: Int) {
        if (index < 0) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            return
        }
        val tracks = exo.currentTracks
        var textIdx = 0
        for (i in 0 until tracks.groups.size) {
            val group = tracks.groups[i]
            if (group.type == C.TRACK_TYPE_TEXT) {
                if (textIdx == index) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                        .build()
                    return
                }
                textIdx++
            }
        }
    }

    actual fun release() {
        if (mediaSessionLazy.isInitialized()) mediaSession.release()
        exo.release()
    }

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
                    // Prefer the server-derived label (Jellyfin DisplayTitle), mapped by audio-stream
                    // order; fall back to the container track name, then a humanized language, then
                    // the raw code (R46). If stream counts differ, the container label still applies.
                    val meta = audioMeta.getOrNull(idx)
                    val label = meta?.label?.takeIf { it.isNotBlank() }
                        ?: format.label
                        ?: languageName(format.language)
                        ?: format.language?.uppercase()
                        ?: "Track ${idx + 1}"
                    result += PlayerAudioTrack(idx, label, meta?.language ?: format.language)
                    idx++
                }
            }
            return result
        }

    actual val subtitleTracks: List<PlayerSubtitleTrack>
        get() {
            val result = mutableListOf<PlayerSubtitleTrack>()
            val tracks = exo.currentTracks
            var idx = 0
            for (i in 0 until tracks.groups.size) {
                val group = tracks.groups[i]
                if (group.type == C.TRACK_TYPE_TEXT) {
                    val format = group.mediaTrackGroup.getFormat(0)
                    val label = format.label ?: languageName(format.language) ?: format.language?.uppercase() ?: "Track ${idx + 1}"
                    val forced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                    val def = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0
                    // R56: mark VobSub/DVDSub image subs as "embed" so the picker can show them
                    // without a VTT URL; PGS is exposed separately as encode subs via the server list.
                    val mime = format.sampleMimeType?.lowercase()
                    val deliveryMethod = when {
                        mime == "application/vobsub" || mime == "application/dvd-subtitle" -> "embed"
                        else -> "external"
                    }
                    result += PlayerSubtitleTrack(idx, label, format.language, forced, def, deliveryMethod)
                    idx++
                }
            }
            return result
        }
}
