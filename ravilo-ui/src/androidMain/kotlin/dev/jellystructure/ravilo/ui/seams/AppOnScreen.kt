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
    var onScreen by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && isInteractive(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> onScreen = true
                Lifecycle.Event.ON_STOP -> onScreen = false
                else -> {}
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreen = false
                    Intent.ACTION_SCREEN_ON -> if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) onScreen = true
                }
            }
        }
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        runCatching { context.registerReceiver(receiver, filter) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return onScreen
}

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
