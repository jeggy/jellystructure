package dev.jellystructure.ravilo.ui.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

private const val PREFS = "ravilo_tv_apps"

/**
 * FR-14/FR-15 — the pins, the order All apps was left in and the opened record live in Ravilo's own preferences file on this TV:
 * shared by every profile, kept across updates, never synced and not part of `RaviloConfig`. Package names carry no `,` or `=`.
 */
class PrefsTvAppsStore(context: Context) : TvAppsStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override var pins: List<String>
        get() = prefs.getString("pins", "").orEmpty().split(',').filter { it.isNotEmpty() }
        set(v) { prefs.edit().putString("pins", v.joinToString(",")).apply() }

    override var order: AllAppsOrder
        get() = if (prefs.getString("order", "az") == "recent") AllAppsOrder.RECENT else AllAppsOrder.AZ
        set(v) { prefs.edit().putString("order", if (v == AllAppsOrder.RECENT) "recent" else "az").apply() }

    override var lastOpened: Map<String, Long>
        get() = prefs.getString("opened", "").orEmpty().split(',').mapNotNull {
            val (pkg, at) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            at.toLongOrNull()?.let { t -> pkg to t }
        }.toMap()
        set(v) { prefs.edit().putString("opened", v.entries.joinToString(",") { "${it.key}=${it.value}" }).apply() }
}

/**
 * The Android TV build's [TvAppsPlatform]: the TV's own package manager answers every question, so nothing here needs a network
 * or a signed-in viewer (FR-11). The apps are the ones with a `MAIN` + `LEANBACK_LAUNCHER` entry, which the manifest's `<queries>`
 * makes visible on Android 11+.
 */
class AndroidTvAppsPlatform(context: Context) : TvAppsPlatform {
    private val app = context.applicationContext
    private val pm: PackageManager get() = app.packageManager
    override val ownPackage: String = app.packageName

    private fun leanbackEntries(): List<ResolveInfo> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)

    override fun installedApps(): List<TvApp> = leanbackEntries().mapNotNull { ri ->
        val info = ri.activityInfo ?: return@mapNotNull null
        val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: info.packageName
        TvApp(info.packageName, label, hasBanner = info.banner != 0 || info.applicationInfo?.banner.let { it != null && it != 0 })
    }

    override fun launch(packageName: String): Boolean {
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName) ?: return false
        return try {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    override fun openSettings() {
        try {
            app.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    override fun banner(packageName: String): ImageBitmap? {
        val entry = leanbackEntries().firstOrNull { it.activityInfo?.packageName == packageName }?.activityInfo ?: return null
        val drawable = runCatching { entry.loadBanner(pm) ?: entry.applicationInfo?.loadBanner(pm) }.getOrNull() ?: return null
        return drawable.rasterized(BANNER_W, BANNER_H)
    }

    override fun icon(packageName: String): ImageBitmap? {
        val drawable = runCatching { pm.getApplicationIcon(packageName) }.getOrNull() ?: return null
        return drawable.rasterized(ICON_PX, ICON_PX)
    }

    private fun Drawable.rasterized(w: Int, h: Int): ImageBitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, w, h)
        draw(canvas)
        return bitmap.asImageBitmap()
    }

    private companion object {
        // A TV banner is 320×180 by Android's own guide; an icon is shown at about 34 dp.
        const val BANNER_W = 320
        const val BANNER_H = 180
        const val ICON_PX = 96
    }
}

/** Wires Apps on an Android TV. Called from [dev.jellystructure.ravilo.ui.homehook.installHomeHook], which is TV-only (FR-13). */
internal fun installTvApps(context: Context) {
    val app = context.applicationContext
    TvApps.install(AndroidTvAppsPlatform(app), PrefsTvAppsStore(app)) { kotlin.time.Clock.System.now().toEpochMilliseconds() }
}
