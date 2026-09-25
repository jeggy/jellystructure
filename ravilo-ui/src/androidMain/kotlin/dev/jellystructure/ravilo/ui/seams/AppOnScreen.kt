package dev.jellystructure.ravilo.ui.seams

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * R293 (FR-R293-1) — Android: the activity's `ON_START`/`ON_STOP` plus the screen broadcasts, exactly the
 * pair [PlayerLifecycleEffect] listens to. Starts from the lifecycle's current state, so an observer added
 * to an already-started activity does not wait for a replayed event.
 */
@Composable
actual fun rememberAppOnScreen(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current.applicationContext
    // Waking a locked phone starts the activity for ~2 s before the keyguard stops it again (measured on
    // the Pixel 9, 2026-09-25: ON_START, connect, ON_STOP, a zero-second socket on every wake). An
    // ON_START behind the keyguard is therefore not "on screen"; the unlock brings its own ON_START. A TV
    // has no keyguard, so its wake is unchanged.
    fun visible(): Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && isInteractive(context) && !keyguardLocked(context)
    var onScreen by remember(lifecycle) { mutableStateOf(visible()) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { val v = !keyguardLocked(context); android.util.Log.d("R293", "lifecycle ON_START → ${if (v) "on screen" else "behind the keyguard, waiting"}"); if (v) onScreen = true }
                // The unlock's own ON_START still sees the keyguard as locked; the RESUME that follows it does
                // not lie — a resumed activity is on screen — and ACTION_USER_PRESENT below covers a device
                // that resumes nothing on unlock.
                Lifecycle.Event.ON_RESUME -> { android.util.Log.d("R293", "lifecycle ON_RESUME → on screen"); onScreen = true }
                Lifecycle.Event.ON_STOP -> { android.util.Log.d("R293", "lifecycle ON_STOP → off screen"); onScreen = false }
                else -> {}
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> { android.util.Log.d("R293", "SCREEN_OFF (${lifecycle.currentState}) → off screen"); onScreen = false }
                    Intent.ACTION_SCREEN_ON -> { val v = visible(); android.util.Log.d("R293", "SCREEN_ON (${lifecycle.currentState}) → ${if (v) "on screen" else "not yet"}"); if (v) onScreen = true }
                    Intent.ACTION_USER_PRESENT -> { val v = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && isInteractive(context); android.util.Log.d("R293", "USER_PRESENT (${lifecycle.currentState}) → ${if (v) "on screen" else "not started"}"); if (v) onScreen = true }
                }
            }
        }
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        runCatching { context.registerReceiver(receiver, filter) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return onScreen
}

private fun keyguardLocked(context: Context): Boolean =
    runCatching { (context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked ?: false }.getOrDefault(false)

private fun isInteractive(context: Context): Boolean =
    runCatching { (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true }.getOrDefault(true)

@Composable
actual fun rememberDeviceStateProbe(): () -> String {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current.applicationContext
    return remember(lifecycle, context) {
        {
            val state = lifecycle.currentState.name.lowercase()
            val interactive = if (isInteractive(context)) "on" else "off"
            val net = runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                when {
                    caps == null -> "none"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    else -> "other"
                } + if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) "+ok" else ""
            }.getOrDefault("unknown")
            listOf(state, interactive, net, Build.VERSION.SDK_INT.toString()).joinToString(";") { EventsSocketLog.clean(it, 16) }
        }
    }
}
