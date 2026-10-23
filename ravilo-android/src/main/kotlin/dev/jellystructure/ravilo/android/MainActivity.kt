package dev.jellystructure.ravilo.android

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.jellystructure.ravilo.android.castconnect.CastConnectReceiver
import dev.jellystructure.ravilo.player.RaviloExtractorsFactory
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
        RaviloPlayerEngine.renderersFactoryProvider = { ctx, prefer -> RaviloRenderers.create(ctx, prefer) }
        RaviloPlayerEngine.extractorsFactoryProvider = { RaviloExtractorsFactory() }

        // Keep screen on during playback; go full-screen (leanback style)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            // R213 — test tags become UiAutomator resource ids, so the baseline-profile journey (a separate
            // instrumentation APK) can find the setup, sign-in and Home screens without depending on the language.
            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
            Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                RaviloRoot()
            }
        }
        // R266 — launched by Cast Connect: the SDK reads its LAUNCH/LOAD intent and calls the load callback.
        CastConnectReceiver.handleIntent(intent)
    }

    // R266 — a cast to a TV already running Ravilo (singleTask).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        CastConnectReceiver.handleIntent(intent)
    }

    // R266 (FR-R266-2) — the receiver lives while the app is on screen and is stopped with it: never held across a
    // backgrounded player.
    override fun onStart() {
        super.onStart()
        CastConnectReceiver.start()
    }

    override fun onStop() {
        // R380 (found live 2026-10-09) — Home ends music the server started on this TV too, not only a Cast LOAD's.
        dev.jellystructure.ravilo.ui.seams.TvCastChannel.appLeftScreen()
        CastConnectReceiver.stop()
        super.onStop()
    }
}
