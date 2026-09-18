package dev.jellystructure.ravilo.ui.seams

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.jellystructure.ravilo.ui.isTvPlatform

@Composable
actual fun PlayerImmersiveEffect(followSensor: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = activity.window
        val decorView = window.decorView
        val controller = WindowInsetsControllerCompat(window, decorView)
        val originalOrientation = activity.requestedOrientation
        // Field doesn't exist below API 28 at all (added to the platform in Android 9, not just hidden
        // behind a version check) — reading it on an older OS throws NoSuchFieldError, not "returns 0".
        val originalCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window.attributes.layoutInDisplayCutoutMode else null

        // R244 (FR-R244-7) — a phone plays upright too: FULL_USER follows the sensor and respects the
        // system rotation lock. Unconditional, unchanged by R261: the TV activity declares no
        // screenOrientation in its manifest at all, so this is what actually puts it in landscape while
        // playing — gating it on isTvPlatform below would silently break TV landscape playback.
        activity.requestedOrientation = if (followSensor) ActivityInfo.SCREEN_ORIENTATION_FULL_USER else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // R261 (FR-R261-1/4/6, dev review items 1/2/6) — TV's own MainActivity already hides the system
        // bars, disables decor-fit and holds FLAG_KEEP_SCREEN_ON for the Activity's whole life; applying
        // (entry) or ever undoing (dispose) any of that a second time here is exactly the kind of thing
        // FR-R261-6's "no TV pixel moves" rules out, so this whole block is phone/web only. The old
        // dispose-time `isVisible(systemBars())` capture is gone — a phone has no caption bar source, so
        // that call was always false there and the bars never actually came back (this phase's bug).
        if (!isTvPlatform) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            // FR-R261-3 dev review item 2 — below Android 15 edge-to-edge isn't enforced, so nothing else
            // pulls the window out from under the display cutout; SHORT_EDGES is what lets the picture
            // reach it instead of being letterboxed by the system itself.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            // FR-R261-4, dev review item 6 — no new seam; a paused player is still "on screen".
            view.keepScreenOn = true
        }

        onDispose {
            activity.requestedOrientation = originalOrientation
            if (!isTvPlatform) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                controller.show(WindowInsetsCompat.Type.systemBars())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && originalCutoutMode != null) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = originalCutoutMode
                    }
                }
                view.keepScreenOn = false
            }
        }
    }
}
