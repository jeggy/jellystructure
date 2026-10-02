package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.jellystructure.ravilo.ui.RemoteControl
import dev.jellystructure.ravilo.ui.seams.RaviloPlayer
import dev.jellystructure.shared.tv.RemotePlayer

/**
 * R354 (FR-R354-3/-4) — while the film player is composed, it is the player remote commands drive (the Jellyfin
 * dashboard, Home Assistant, a phone remote). Each command calls what the player's own control calls: the play button
 * ([togglePlay], only when the state differs — a stale or repeated command is harmless), the scrubber ([seekTo]), the
 * skip buttons ([skip]), Back ([stop]), the next-up card's *Play now* ([next]) and the episode list ([previous]).
 * Its own composable, so `PlayerScreen`'s method stays the size R258's register-count verifier allows.
 */
@Composable
internal fun RemoteVideoCommands(
    player: RaviloPlayer,
    isPlaying: () -> Boolean,
    togglePlay: () -> Unit,
    seekTo: (Long) -> Unit,
    skip: (Long) -> Unit,
    stop: () -> Unit,
    next: () -> Unit,
    previous: () -> Unit,
) {
    val playing by rememberUpdatedState(isPlaying)
    val onToggle by rememberUpdatedState(togglePlay)
    val seek by rememberUpdatedState(seekTo)
    val skipBy by rememberUpdatedState(skip)
    val leave by rememberUpdatedState(stop)
    val forward by rememberUpdatedState(next)
    val back by rememberUpdatedState(previous)
    DisposableEffect(player) {
        val remote = object : RemotePlayer {
            override fun play() { if (!playing()) onToggle() }
            override fun pause() { if (playing()) onToggle() }
            override fun toggle() = onToggle()
            override fun stop() = leave()
            override fun seekTo(positionMs: Long) = seek(positionMs)
            override fun seekBy(deltaMs: Long) = skipBy(deltaMs)
            override fun next() = forward()
            override fun previous() = back()
            override fun setVolume(level: Float, muted: Boolean) = player.setVolume(if (muted) 0f else level)
        }
        val detach = RemoteControl.attachVideo(remote)
        onDispose { detach() }
    }
}
