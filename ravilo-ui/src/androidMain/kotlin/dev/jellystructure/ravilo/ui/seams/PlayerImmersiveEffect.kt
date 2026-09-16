package dev.jellystructure.ravilo.ui.seams

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
actual fun PlayerImmersiveEffect(followSensor: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(Unit) {
        val window = activity.window
        val decorView = window.decorView
        val controller = WindowInsetsControllerCompat(window, decorView)
        val originalOrientation = activity.requestedOrientation
        // Captured, not assumed: on TV the bars are already hidden (permanently, app-wide), so
        // restoring "visible" on dispose would wrongly break the TV app's own leanback chrome.
        val barsWereVisible = ViewCompat.getRootWindowInsets(decorView)
            ?.isVisible(WindowInsetsCompat.Type.systemBars()) ?: true

        // R244 (FR-R244-7) — a phone plays upright too: FULL_USER follows the sensor and respects the
        // system rotation lock (the one line the dev review said this requirement is).
        activity.requestedOrientation = if (followSensor) ActivityInfo.SCREEN_ORIENTATION_FULL_USER else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            activity.requestedOrientation = originalOrientation
            if (barsWereVisible) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
