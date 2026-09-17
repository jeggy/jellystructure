package dev.jellystructure.ravilo.android

import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.jellystructure.ravilo.player.RaviloRenderers
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.ravilo.ui.RaviloRoot
import dev.jellystructure.ravilo.ui.seams.RaviloPlayerEngine

// R245 amendment (2026-09-18) — a FragmentActivity (itself a ComponentActivity, so setContent is
// unchanged): androidx.mediarouter's MediaRouteButton shows its device chooser as a DialogFragment and
// throws "The activity must be a subclass of FragmentActivity" on the first tap otherwise — the Cast
// button crashed the app outright on a Pixel 9.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        RaviloAppContext.init(this) // also wires SvgDecoder via SingletonImageLoader
        // R31: route player renderers through the GPL-contained FFmpeg decoders (DTS/TrueHD/AC3).
        RaviloPlayerEngine.renderersFactoryProvider = { ctx -> RaviloRenderers.create(ctx) }

        // Keep screen on during playback; go full-screen (leanback style)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            RaviloRoot()
        }
    }
}
