package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.shared.tv.MusicTrackItem
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * R322 (dev review 3) — the web app has no music player yet (a later phase). This actual exists so `:ravilo-web`
 * builds; [supported] is false, so music mode never appears here (R321 FR-R321-2).
 */
actual object MusicEngine {
    actual val supported: Boolean = false
    actual val state: StateFlow<MusicPlayerState> = MutableStateFlow(MusicPlayerState())
    actual fun attach(api: TvApiClient) = Unit
    actual fun playQueue(tracks: List<MusicTrackItem>, startIndex: Int, context: MusicContext, shuffle: Boolean) = Unit
    actual fun loadPaused(tracks: List<MusicTrackItem>, index: Int, positionMs: Long, context: MusicContext?) = Unit
    actual fun togglePlay() = Unit
    actual fun play() = Unit
    actual fun pause() = Unit
    actual fun next() = Unit
    actual fun previous() = Unit
    actual fun seekTo(positionMs: Long) = Unit
    actual fun playAt(index: Int) = Unit
    actual fun cycleRepeat() = Unit
    actual fun toggleShuffle() = Unit
    actual fun move(from: Int, to: Int) = Unit
    actual fun remove(index: Int) = Unit
    actual fun playNext(track: MusicTrackItem) = Unit
    actual fun addToQueue(track: MusicTrackItem) = Unit
    actual fun clear() = Unit
    actual fun stopForVideo() = Unit
    actual fun retry() = Unit
    actual fun skip() = Unit
    actual fun currentPositionMs(): Long = 0L
    actual fun setEvenVolume(on: Boolean) = Unit
    actual fun playBook(detail: dev.jellystructure.shared.tv.AudiobookDetail, part: Int, positionMs: Long, play: Boolean) = Unit
    actual fun skipBy(deltaMs: Long) = Unit
    actual fun seekBook(bookMs: Long) = Unit
    actual fun setSpeed(speed: Double) = Unit
    actual fun setSleep(timer: SleepTimer?) = Unit
    actual fun setSkipSilence(on: Boolean) = Unit
    actual fun bookPositionMs(): Long = 0L
}
