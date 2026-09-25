package dev.jellystructure.ravilo.ui.seams

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.jellystructure.ravilo.ui.seams.PlayerLifecycleGate.Action
import dev.jellystructure.ravilo.ui.seams.PlayerLifecycleGate.Signal

/**
 * R292 (FR-R292-1/3/8) — the engine does not survive the app going off screen. `ON_PAUSE` pauses;
 * `ON_STOP` (or `ACTION_SCREEN_OFF`, for a TV standby that sends no `ON_STOP`) lets the caller capture its
 * resume record and stop the session, then **releases the engine**: decoders, `AudioTrack`, buffers,
 * network, media session. `ON_START` (or `ACTION_SCREEN_ON` with the activity still started) is a new
 * start from that record, never a re-attach. Which signal fires is [PlayerLifecycleGate]'s decision,
 * tested without a device; this file only wires Android to it. Supersedes R220 FR-R220-4.
 */
@Composable
actual fun PlayerLifecycleEffect(
    player: RaviloPlayer,
    wasPlaying: () -> Boolean,
    onBackground: (wasPlaying: Boolean) -> Unit,
    onForeground: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current.applicationContext
    // rememberUpdatedState: the observer is installed once per lifecycle owner; a plain capture would pin
    // the FIRST episode's callbacks for the whole binge (the stale-capture bug this file has had before).
    val currentWasPlaying by rememberUpdatedState(wasPlaying)
    val currentOnBackground by rememberUpdatedState(onBackground)
    val currentOnForeground by rememberUpdatedState(onForeground)
    DisposableEffect(lifecycle) {
        val gate = PlayerLifecycleGate()
        fun act(action: Action) {
            when (action) {
                Action.None -> Unit
                Action.Pause -> player.pause()
                Action.ResumePlayback -> player.play()
                is Action.Background -> {
                    // Order matters: the record is captured from the LIVE engine, then the session is
                    // stopped with that same position, then the engine goes (FR-R292-2).
                    currentOnBackground(action.wasPlaying)
                    player.setSessionActive(false)
                    player.releaseEngine()
                }
                Action.Foreground -> {
                    currentOnForeground()
                    player.setSessionActive(true)
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> act(gate.on(Signal.PAUSE, playing = { currentWasPlaying() }))
                Lifecycle.Event.ON_RESUME -> act(gate.on(Signal.RESUME))
                Lifecycle.Event.ON_STOP -> act(gate.on(Signal.STOP))
                Lifecycle.Event.ON_START -> act(gate.on(Signal.START))
                else -> {}
            }
        }
        // FR-R292-8 — display standby on an Android TV may deliver no ON_STOP: the screen-off broadcast is
        // handled exactly like one, at most once per transition (the gate), and screen-on like ON_START
        // when the activity is still started.
        val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> act(gate.on(Signal.SCREEN_OFF, playing = { currentWasPlaying() }))
                    Intent.ACTION_SCREEN_ON -> act(gate.on(Signal.SCREEN_ON, onScreen = { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }))
                }
            }
        }
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        runCatching { context.registerReceiver(screenReceiver, filter) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(screenReceiver) }
        }
    }
}
