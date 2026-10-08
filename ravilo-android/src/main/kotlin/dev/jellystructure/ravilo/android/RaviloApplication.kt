package dev.jellystructure.ravilo.android

import android.app.Application
import dev.jellystructure.ravilo.BuildConfig
import dev.jellystructure.ravilo.android.castconnect.CastConnectReceiver
import dev.jellystructure.ravilo.ui.seams.CastAppIdOverride

/**
 * R266 — the app's first code, before any activity: Google's Cast Connect guide initialises `CastReceiverContext`
 * here (TV only — [CastConnectReceiver.init] does nothing on a phone), and the debug build's development Cast
 * application id is set before the Cast SDK's options provider can be asked for one.
 */
class RaviloApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Empty in a release build (build.gradle.kts), so the server's chromecast.app_id is used exactly as before.
        CastAppIdOverride.devAppId = BuildConfig.CAST_DEV_APP_ID.takeIf { it.isNotBlank() }
        CastConnectReceiver.init(this)
    }
}
