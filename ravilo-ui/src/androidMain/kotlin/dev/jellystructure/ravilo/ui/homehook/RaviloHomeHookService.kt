package dev.jellystructure.ravilo.ui.homehook

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlinx.datetime.Clock

/**
 * DEB-4910 (FR-3, FR-5–FR-8) — the hook. It hears one thing, a window coming forward ([AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED]),
 * and only from the stock home's package: it reads no window content (`canRetrieveWindowContent` is off in its configuration),
 * takes no gesture and keeps nothing. When the stock home's home screen comes forward and the hook is on, it brings Ravilo up.
 *
 * The decision is [HomeHookDecider]'s. While FR-5's pass-through is on, the filter is lifted to every package so the hook can
 * learn that the viewer opened something else; it reads only the package name of that window, and the filter goes back to the
 * stock home's package at that moment.
 */
class RaviloHomeHookService : AccessibilityService() {
    private lateinit var store: HomeHookStore
    private lateinit var guard: HomeHookGuard
    private lateinit var decider: HomeHookDecider
    private var stock: ResolvedStockHome? = null
    private var lastLaunchAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = PrefsHomeHookStore(applicationContext)
        guard = HomeHookGuard(store) { Clock.System.now().toEpochMilliseconds() }
        decider = HomeHookRuntime.deciderFor(packageName)
        HomeHookRuntime.service = this
        applyFilter()
    }

    /** FR-7 — the events this service hears: the stock home's package; every package only while FR-5's pass-through is on. */
    fun applyFilter() {
        val resolved = resolveStockHome(packageManager, packageName)
        stock = resolved
        val info = serviceInfo ?: return
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.packageNames = if (decider.passThrough || resolved == null) null else arrayOf(resolved.home.packageName)
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val wasPassThrough = decider.passThrough
        val hookActive = store.enabled && !store.paused
        val take = decider.onWindowChange(WindowChange(pkg, event.className?.toString()), stock?.home, hookActive)
        if (wasPassThrough && !decider.passThrough) applyFilter()   // the viewer opened something else: the narrow filter is back
        if (!take) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchAt < 800) return   // one launch per press: the home screen can report its window more than once
        lastLaunchAt = now
        bringRaviloUp()
    }

    private fun bringRaviloUp() {
        val launch: Intent = packageManager.getLeanbackLaunchIntentForPackage(packageName) ?: return
        guard.hookLaunched()
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_FROM_HOME_HOOK, true)
        runCatching { startActivity(launch) }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (HomeHookRuntime.service === this) HomeHookRuntime.service = null
        return super.onUnbind(intent)
    }
}
