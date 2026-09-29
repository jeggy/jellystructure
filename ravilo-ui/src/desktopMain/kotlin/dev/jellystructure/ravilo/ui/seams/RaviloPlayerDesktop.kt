package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.SubTrack

/**
 * R328 — no player yet: every load fails at once, so the player shows R237's failure card instead of waiting
 * forever. R329 replaces this with AVPlayer.
 */
actual class RaviloPlayer actual constructor() {
    private var failed = false
    actual fun load(streamUrl: String, startPositionMs: Long, subtitles: List<SubTrack>, audio: List<AudioTrack>, title: String, subtitle: String?, artworkUrl: String?) {
        failed = true
    }
    actual fun play() {}
    actual fun pause() {}
    actual fun seekTo(positionMs: Long) {}
    actual fun selectAudioTrack(index: Int) {}
    actual fun selectSubtitleTrack(index: Int) {}
    actual fun release() { failed = false }
    actual fun releaseEngine() {}
    actual fun recordRestoredAfterRecreate() {}
    actual fun setSessionActive(active: Boolean) {}
    actual fun setChromeVisible(visible: Boolean) {}
    actual fun setSubtitleScale(scale: Float) {}
    actual val positionMs: Long get() = 0
    actual val durationMs: Long get() = 0
    actual val bufferedMs: Long get() = 0
    actual val isPlaying: Boolean get() = false
    actual val isEnded: Boolean get() = false
    actual val playbackFailed: Boolean get() = failed
    actual val hasRenderedFirstFrame: Boolean get() = false
    actual val isBuffering: Boolean get() = false
    actual val isSeeking: Boolean get() = false
    actual val audioTracks: List<PlayerAudioTrack> get() = emptyList()
    actual val subtitleTracks: List<PlayerSubtitleTrack> get() = emptyList()
    actual fun qoeSnapshot(): PlayerQoeSnapshot = PlayerQoeSnapshot()
}

@Composable
actual fun PlayerVideoSurface(
    player: RaviloPlayer,
    modifier: Modifier,
    onVideoOutputStuck: () -> Unit,
    onVideoOutputRecovering: (Boolean) -> Unit,
    fill: Boolean,
    subtitleBottomInset: Dp,
) {
    Box(modifier)
}

@Composable
actual fun PlayerLifecycleEffect(
    player: RaviloPlayer,
    wasPlaying: () -> Boolean,
    onBackground: (wasPlaying: Boolean) -> Unit,
    onForeground: () -> Unit,
) {}
