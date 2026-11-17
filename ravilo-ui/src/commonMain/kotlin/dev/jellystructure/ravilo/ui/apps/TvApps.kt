package dev.jellystructure.ravilo.ui.apps

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.jellystructure.ravilo.ui.homehook.HomeHook
import dev.jellystructure.ravilo.ui.isTvPlatform

/*
 * DEB-4912 (specs/ravilo/requirements/ravilo-can-be-the-tvs-home-screen.md, FR-10–FR-15) — Apps: the TV's own settings, the stock home,
 * the viewer's pinned apps and *See all apps*. Everything that can be decided without Android is here, as plain Kotlin with an
 * injected clock: which entries Apps shows and in what order, the pins, the two orders of All apps and the record behind
 * *Recently opened*. The Android `actual` side (the package query, the launch, the preferences file) is `androidMain`'s apps package.
 * Nothing in this package reads from, or sends anything to, jellystructure (FR-11, FR-14).
 */

/** One app with a TV launcher entry (`MAIN` + `LEANBACK_LAUNCHER`). */
data class TvApp(val packageName: String, val label: String, val hasBanner: Boolean)

/** What Android knows and does on Apps' behalf. Registered by the Android TV build only; every other platform has none (FR-13). */
interface TvAppsPlatform {
    /** Ravilo's own package: never listed (FR-11). */
    val ownPackage: String

    /** FR-11 — every app with a TV launcher entry, read fresh from the TV each time. */
    fun installedApps(): List<TvApp>

    /** FR-11 — opens the app with its leanback launch intent. False when it could not be opened. */
    fun launch(packageName: String): Boolean

    /** FR-11 — opens `Settings.ACTION_SETTINGS`. */
    fun openSettings()

    /** The app's TV banner; null when it has none. Loaded off the main thread. */
    fun banner(packageName: String): ImageBitmap?

    /** The app's icon, for an app with no banner. */
    fun icon(packageName: String): ImageBitmap?
}

/** FR-14/FR-15 — what is kept on the TV: its own preferences file, shared by every profile, never synced, never in `RaviloConfig`. */
interface TvAppsStore {
    /** Pinned package names in the order the viewer set. */
    var pins: List<String>

    /** The order All apps was last left in. */
    var order: AllAppsOrder

    /** Epoch ms of when Ravilo last opened each app (FR-15) — Ravilo's own record, no usage-access permission. */
    var lastOpened: Map<String, Long>
}

/** FR-15 — the two orders of All apps. */
enum class AllAppsOrder { AZ, RECENT }

/** What the Apps screen draws, in FR-11's order. */
sealed interface AppsEntry {
    /** 1 — `Settings.ACTION_SETTINGS`. */
    data object Settings : AppsEntry

    /** 2 — the stock home, labelled with the name Android reports for it (FR-5). */
    data class StockHome(val label: String) : AppsEntry

    /** 3 — a pinned app. */
    data class Pinned(val app: TvApp) : AppsEntry

    /** 4 — opens All apps. */
    data object SeeAll : AppsEntry
}

/** A row of All apps: the app and whether it is pinned (FR-15 marks it). */
data class AllAppsRow(val app: TvApp, val pinned: Boolean)

/** FR-11 — the label order: case-insensitive, with the package name breaking a tie so the order never flickers. */
private val labelOrder = compareBy<TvApp>({ it.label.lowercase() }, { it.packageName })

/**
 * FR-11 — the apps the viewer may open: every TV app, Ravilo itself left out, one entry per package.
 * A–Z by label, so a screen that lists them straight from here is already in FR-15's default order.
 */
fun visibleApps(installed: List<TvApp>, ownPackage: String): List<TvApp> =
    installed.filter { it.packageName != ownPackage }.distinctBy { it.packageName }.sortedWith(labelOrder)

/** FR-15 — A–Z, or the apps Ravilo opened newest first and then every other app A–Z. */
fun orderedApps(apps: List<TvApp>, order: AllAppsOrder, lastOpened: Map<String, Long>): List<TvApp> = when (order) {
    AllAppsOrder.AZ -> apps.sortedWith(labelOrder)
    AllAppsOrder.RECENT -> {
        val (opened, rest) = apps.partition { lastOpened.containsKey(it.packageName) }
        opened.sortedWith(compareByDescending<TvApp> { lastOpened.getValue(it.packageName) }.then(labelOrder)) + rest.sortedWith(labelOrder)
    }
}

/** FR-14 — the move menu's two directions. A move off either end does nothing. */
enum class PinMove(val delta: Int) { LEFT(-1), RIGHT(1) }

/**
 * Apps and All apps over a [TvAppsPlatform] and a [TvAppsStore], with [now] injected (epoch ms). Every read asks the platform again
 * (FR-11): an app installed or removed shows up the next time a screen opens, with no restart.
 */
class TvAppsModel(
    private val platform: TvAppsPlatform,
    private val store: TvAppsStore,
    private val now: () -> Long,
    /** FR-5 — the stock home's own name as Android reports it; null when it cannot be found, and the entry is then left out. */
    private val stockHomeLabel: () -> String? = { null },
    private val openStockHomeAction: () -> Unit = {},
) {
    /** Moves whenever a pin, the order or the opened record changes, so a screen that read them can read them again. */
    var version by mutableIntStateOf(0)
        private set

    /** FR-11 — every app on the TV but Ravilo, A–Z. */
    fun apps(): List<TvApp> = visibleApps(platform.installedApps(), platform.ownPackage)

    /**
     * FR-14 — a pinned app that is uninstalled drops out, and it is dropped from the store too: installed again, it is not pinned.
     * Pruning happens on read, because an uninstall never tells Ravilo.
     */
    private fun livePins(apps: List<TvApp>): List<TvApp> {
        val byPackage = apps.associateBy { it.packageName }
        val kept = store.pins.distinct().filter { it in byPackage }
        if (kept != store.pins) store.pins = kept
        return kept.map { byPackage.getValue(it) }
    }

    /** FR-11 — Settings, the stock home, the pinned apps in the viewer's order, then See all apps. With no pins: items 1, 2 and 4. */
    fun entries(): List<AppsEntry> {
        val pinned = livePins(apps())
        return buildList {
            add(AppsEntry.Settings)
            stockHomeLabel()?.let { add(AppsEntry.StockHome(it)) }
            pinned.forEach { add(AppsEntry.Pinned(it)) }
            add(AppsEntry.SeeAll)
        }
    }

    /** FR-15 — the order All apps was last left in. */
    var order: AllAppsOrder
        get() = store.order
        set(v) { store.order = v; version++ }

    /** FR-15 — every app, pinned ones included and marked, in the chosen order (the remembered one by default). */
    fun allApps(order: AllAppsOrder = store.order): List<AllAppsRow> {
        val apps = apps()
        val pinned = livePins(apps).map { it.packageName }.toSet()
        return orderedApps(apps, order, store.lastOpened).map { AllAppsRow(it, it.packageName in pinned) }
    }

    /** FR-14 — from All apps. New pins go at the end; pinning twice changes nothing. */
    fun pin(packageName: String) {
        val apps = apps()
        if (apps.none { it.packageName == packageName }) return
        val pins = livePins(apps)
        if (pins.none { it.packageName == packageName }) store.pins = pins.map { it.packageName } + packageName
        version++
    }

    /** FR-14 — from Apps. */
    fun unpin(packageName: String) {
        store.pins = livePins(apps()).map { it.packageName }.filter { it != packageName }
        version++
    }

    /** FR-14 — from Apps, one place left or right among the pins. */
    fun move(packageName: String, move: PinMove) {
        val pins = livePins(apps()).map { it.packageName }.toMutableList()
        val at = pins.indexOf(packageName)
        val to = at + move.delta
        if (at < 0 || to !in pins.indices) return
        pins.add(to, pins.removeAt(at))
        store.pins = pins
        version++
    }

    /** FR-11/FR-15 — opens the app and, only when Android opened it, records when (Recently opened). */
    fun open(packageName: String): Boolean {
        if (!platform.launch(packageName)) return false
        // The record keeps only apps still on the TV, so it cannot grow with every app that was ever installed.
        val installed = apps().map { it.packageName }.toSet()
        store.lastOpened = store.lastOpened.filterKeys { it in installed } + (packageName to now())
        version++
        return true
    }

    fun openSettings() = platform.openSettings()

    fun openStockHome() = openStockHomeAction()

    fun banner(packageName: String): ImageBitmap? = platform.banner(packageName)

    fun icon(packageName: String): ImageBitmap? = platform.icon(packageName)
}

/** Where the Android side meets the common UI. Set on an Android TV only. */
object TvApps {
    private var modelField: TvAppsModel? = null

    /** Registered by the Android TV build; null on every other platform, which never shows the entry (FR-13). */
    val model: TvAppsModel? get() = modelField

    fun install(platform: TvAppsPlatform, store: TvAppsStore, now: () -> Long) {
        modelField = TvAppsModel(
            platform, store, now,
            stockHomeLabel = { HomeHook.controller?.stockHomeLabel() },
            openStockHomeAction = { HomeHook.controller?.openStockHome() },
        )
    }

    /** FR-10/FR-12 — Apps is reachable only on a TV that has it and only while the hook is acting. */
    fun reachable(): Boolean = appsEntryShows(isTvPlatform, modelField != null, HomeHook.active())
}

/** FR-10 — the header's Apps entry exists on a TV that registered Apps, and only while the hook is on. */
fun appsEntryShows(isTv: Boolean, registered: Boolean, hookActive: Boolean): Boolean = isTv && registered && hookActive
