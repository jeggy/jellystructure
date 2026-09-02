package dev.jellystructure.ravilo.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.jellystructure.ravilo.player.RaviloRenderers
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.ravilo.ui.RaviloRoot
import dev.jellystructure.ravilo.ui.seams.RaviloPlayerEngine

/**
 * R60/R224 — phone entry point, now living in the same module/APK as the TV
 * [dev.jellystructure.ravilo.android.MainActivity] (one universal Play Store listing). Identical wiring
 * to the TV activity but tuned for a handset: portrait (set in the manifest), **standard window fitting**
 * (system bars reserved + visible, normal back button/gesture nav, no leanback immersive hide), and no
 * global keep-screen-on. The shared :ravilo-ui renders the same screens via touch —
 * `Modifier.dpadFocusable` already attaches tap gestures, so no UI fork is needed.
 *
 * Bug fix: this used to call `WindowCompat.setDecorFitsSystemWindows(window, false)` here too (edge-
 * to-edge for the whole app, "Compose insets handle the status/nav bars"), but nothing ever actually
 * applied that inset padding — the app looked and felt fullscreen everywhere, not just in the player,
 * with the system back gesture/button barely usable against it. The player is the only screen that
 * should ever go edge-to-edge/immersive (`PlayerImmersiveEffect`, seams/PlayerImmersiveEffect.kt) —
 * everywhere else now just uses Android's own default window fitting, which reserves and pads for the
 * system bars automatically, no custom inset handling needed.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        RaviloAppContext.init(this) // also wires SvgDecoder via SingletonImageLoader
        // R31: route player renderers through the GPL-contained FFmpeg decoders (DTS/TrueHD/AC3).
        RaviloPlayerEngine.renderersFactoryProvider = { ctx -> RaviloRenderers.create(ctx) }

        setContent {
            RaviloRoot()
        }
    }
}
