package dev.jellystructure.ravilo.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import dev.jellystructure.ravilo.player.RaviloRenderers
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.ravilo.ui.RaviloRoot
import dev.jellystructure.ravilo.ui.seams.RaviloPlayerEngine

/**
 * R60 — phone entry point. Identical wiring to the TV [dev.jellystructure.ravilo.android.MainActivity]
 * but tuned for a handset: portrait (set in the manifest), **system bars stay visible** (no leanback
 * immersive hide), and no global keep-screen-on. The shared :ravilo-ui renders the same screens via
 * touch — `Modifier.dpadFocusable` already attaches tap gestures, so no UI fork is needed.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        RaviloAppContext.init(this) // also wires SvgDecoder via SingletonImageLoader
        // R31: route player renderers through the GPL-contained FFmpeg decoders (DTS/TrueHD/AC3).
        RaviloPlayerEngine.renderersFactoryProvider = { ctx -> RaviloRenderers.create(ctx) }

        // Draw edge-to-edge but KEEP the system bars visible (phone chrome). Compose insets handle
        // the status/nav bars; the player can go immersive on its own screen.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            RaviloRoot()
        }
    }
}
