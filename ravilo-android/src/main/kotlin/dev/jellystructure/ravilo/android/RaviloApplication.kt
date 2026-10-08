package dev.jellystructure.ravilo.android

import android.app.Application
import android.provider.Settings
import dev.jellystructure.ravilo.BuildConfig
import dev.jellystructure.ravilo.android.castconnect.CastConnectReceiver
import dev.jellystructure.ravilo.ui.seams.CastAppIdOverride
import dev.jellystructure.ravilo.ui.seams.TvDeviceName

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
        // R380 (owner decision 2) — the TV's own name, the one Cast shows, so the server lists it as one place.
        if (CastConnectReceiver.isTelevision(this)) {
            TvDeviceName.value = runCatching { Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }
        CastConnectReceiver.init(this)
    }
}
