package dev.jellystructure.ravilo.ui.homehook

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/** Carried by the intent the hook brings Ravilo up with, so the activity can tell it from a launch by the viewer. */
const val EXTRA_FROM_HOME_HOOK = "dev.jellystructure.ravilo.extra.FROM_HOME_HOOK"

private const val PREFS = "ravilo_home_hook"

/**
 * FR-1 — the hook's own preferences file: on this TV only, shared by every profile, never synced and not part of the config.
 * Every write is a `commit()`: the guard's foreground mark has to be on disk before a crash, not queued behind it.
 */
class PrefsHomeHookStore(context: Context) : HomeHookStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).commit() }
    override var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(v) { prefs.edit().putBoolean("paused", v).commit() }
    override var launchedAt: Long
        get() = prefs.getLong("launchedAt", 0L)
        set(v) { prefs.edit().putLong("launchedAt", v).commit() }
    override var foreground: Boolean
        get() = prefs.getBoolean("foreground", false)
        set(v) { prefs.edit().putBoolean("foreground", v).commit() }
    override var closes: List<Long>
        get() = prefs.getString("closes", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        set(v) { prefs.edit().putString("closes", v.joinToString(",")).commit() }
}

/** The stock home as Android reports it, with the name it shows the viewer. */
internal class ResolvedStockHome(val home: StockHome, val label: String?)

/**
 * FR-6 — the stock home is whatever Android resolves the Home intent to, never a package name written here. Ravilo has no
 * HOME entry (FR-7), so it can never resolve to itself; it is excluded anyway. A chooser ("android") means no default is set:
 * the first real home takes its place.
 */
internal fun resolveStockHome(pm: PackageManager, ownPackage: String): ResolvedStockHome? {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val chosen = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        ?.takeIf { it.packageName != "android" && it.packageName != ownPackage }
    val info = chosen
        ?: pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo }
            .firstOrNull { it.packageName != "android" && it.packageName != ownPackage }
        ?: return null
    val label = runCatching { info.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    return ResolvedStockHome(StockHome(info.packageName, info.name), label)
}

/** What the service and the platform share inside the one process: the decider (FR-5/6) and a handle to the running service. */
internal object HomeHookRuntime {
    @Volatile var decider: HomeHookDecider? = null
    @Volatile var service: RaviloHomeHookService? = null

    fun deciderFor(ownPackage: String): HomeHookDecider =
        decider ?: synchronized(this) { decider ?: HomeHookDecider(ownPackage).also { decider = it } }
}

/** The Android TV build's [HomeHookPlatform]. */
class AndroidHomeHookPlatform(context: Context) : HomeHookPlatform {
    private val app = context.applicationContext
    private val component = ComponentName(app, RaviloHomeHookService::class.java)

    /** Android's own list of the services that are on: the true state, with no memory kept here. */
    override fun androidSwitchOn(): Boolean {
        val am = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val si = it.resolveInfo?.serviceInfo
            si != null && si.packageName == component.packageName && si.name == component.className
        }
    }

    override fun openAndroidAccessibilitySettings() {
        // FR-7 — a phone never lists the service: the component stays disabled until this runs, on a TV.
        app.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        val onRavilosPage = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToShortString())
            .putExtra("android.intent.extra.COMPONENT_NAME", component.flattenToShortString())
        val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        for (i in listOf(onRavilosPage, list, Intent(Settings.ACTION_SETTINGS))) {
            try {
                app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    override fun stockHomeLabel(): String? = resolveStockHome(app.packageManager, app.packageName)?.label

    override fun openStockHome() {
        val stock = resolveStockHome(app.packageManager, app.packageName) ?: return
        // The pass-through is set before the launch: the window it opens must already be let through.
        HomeHookRuntime.deciderFor(app.packageName).letStockHomeStay()
        HomeHookRuntime.service?.applyFilter()
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setComponent(ComponentName(stock.home.packageName, stock.home.className))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(home) }
    }
}

/**
 * Wires the hook on an Android TV and does nothing on a phone (FR-1, FR-7). Called from `Application.onCreate`, so the guard
 * has looked at how the last process ended before any activity or the service runs.
 */
fun installHomeHook(context: Context) {
    val app = context.applicationContext
    val tv = (app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    if (!tv) return
    HomeHook.install(AndroidHomeHookPlatform(app), PrefsHomeHookStore(app)) { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() }
    HomeHook.processStarted()
    // DEB-4912 (FR-13) — Apps is registered where the hook is, on a TV only; every other platform has no entry.
    dev.jellystructure.ravilo.ui.apps.installTvApps(app)
}
