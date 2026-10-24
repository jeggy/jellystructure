package dev.jellystructure.ravilo.phone

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.jellystructure.ravilo.player.RaviloExtractorsFactory
import dev.jellystructure.ravilo.player.RaviloRenderers
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.ravilo.ui.RaviloRoot
import dev.jellystructure.ravilo.ui.screens.TvLinkInbox
import dev.jellystructure.ravilo.ui.screens.ssoReturnFromQuery
import dev.jellystructure.ravilo.ui.screens.ssoReturnInbox
import dev.jellystructure.ravilo.ui.screens.tvCodeFromLocation
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

        // DEB-2233 (FR-7) — the App Link that opened this activity; a recreation (rotation, process death) is not a new link.
        if (savedInstanceState == null) offerTvLink(intent)
        // DEB-2233 (FR-11) — single sign-on's return, the same way: only a fresh launch carries one.
        if (savedInstanceState == null) offerSsoReturn(intent)

        setContent {
            RaviloRoot()
        }
    }

    // DEB-2233 (FR-7) — a link opened while the app runs (the manifest's singleTask, when an App Link is declared).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerTvLink(intent)
        offerSsoReturn(intent)
    }

    /** `dev.jellystructure.ravilo:/signin?attempt=…&h=…` (the manifest's filter has matched the scheme and path) is the hand-off for the attempt the sign-in screen holds. */
    private fun offerSsoReturn(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.takeIf { it.scheme == "dev.jellystructure.ravilo" } ?: return
        ssoReturnInbox.offer(ssoReturnFromQuery("?" + uri.encodedQuery.orEmpty(), "attempt"))
    }

    /** `https://<host>/tv?c=<code>` hands its code to the app; the manifest's filter has already matched the host. */
    private fun offerTvLink(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        TvLinkInbox.offer(tvCodeFromLocation(uri.path.orEmpty(), "?" + uri.encodedQuery.orEmpty()))
    }
}
