package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
actual fun PlayerLifecycleEffect(
    player: RaviloPlayer,
    wasPlaying: () -> Boolean,
    onBackground: () -> Unit,
    onForeground: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // rememberUpdatedState: the observer below is installed once per lifecycle owner, so a plain capture
    // would pin the callbacks from the FIRST composition — i.e. the first episode's PlayerStore — and a
    // background in episode 5 would stop episode 1's session instead (the same stale-capture bug the
    // player's cleanup effect used to have). These always call the current episode's callbacks.
    val currentOnBackground by rememberUpdatedState(onBackground)
    val currentOnForeground by rememberUpdatedState(onForeground)
    DisposableEffect(lifecycle) {
        var resumeOnForeground = false
        // ON_START/ON_RESUME are replayed to a freshly added observer to sync it up to the owner's
        // current state, so "did we actually go away?" needs its own latch — without it, entering the
        // player would immediately look like a return from the background and re-start the session.
        var backgrounded = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    resumeOnForeground = wasPlaying()
                    player.pause()
                }
                // ON_STOP, not ON_PAUSE: the player is genuinely off-screen now, and this is the last
                // callback we are guaranteed to get before a swipe-kill/OOM. End the session here so
                // Jellyfin stops listing us as streaming and the resume position is final.
                Lifecycle.Event.ON_STOP -> {
                    backgrounded = true
                    currentOnBackground()
                }
                Lifecycle.Event.ON_START -> {
                    if (backgrounded) {
                        backgrounded = false
                        currentOnForeground()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (resumeOnForeground) player.play()
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
