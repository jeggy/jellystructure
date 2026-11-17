package dev.jellystructure.ravilo.ui.apps

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-4912 — Apps, pins and All apps, as plain Kotlin against a fake app list, a fake store and an injected clock. */
private class FakeStore(
    override var pins: List<String> = emptyList(),
    override var order: AllAppsOrder = AllAppsOrder.AZ,
    override var lastOpened: Map<String, Long> = emptyMap(),
) : TvAppsStore

private class FakeApps(var installed: List<TvApp>, val own: String = "dev.ravilo") : TvAppsPlatform {
    override val ownPackage = own
    val launched = mutableListOf<String>()
    var settingsOpened = 0
    var launchWorks = true
    override fun installedApps() = installed
    override fun launch(packageName: String): Boolean { if (launchWorks) launched += packageName; return launchWorks }
    override fun openSettings() { settingsOpened++ }
    override fun banner(packageName: String): ImageBitmap? = null
    override fun icon(packageName: String): ImageBitmap? = null
}

private fun app(label: String, pkg: String = "app." + label.lowercase().replace(" ", "")) = TvApp(pkg, label, hasBanner = true)

private val TV = listOf(app("Netflix"), app("YouTube"), app("DR TV"), app("Kodi"), app("Ravilo", "dev.ravilo"), app("VLC"))

private fun model(apps: FakeApps, store: FakeStore = FakeStore(), clock: () -> Long = { 0L }, stock: String? = "Google TV") =
    TvAppsModel(apps, store, clock, stockHomeLabel = { stock })

class TvAppsEntriesTest {
    // FR-10 — the entry exists on a TV that registered Apps and only while the hook is on.
    @Test fun entryOnlyWhileTheHookIsOn() {
        assertTrue(appsEntryShows(isTv = true, registered = true, hookActive = true))
        assertFalse(appsEntryShows(isTv = true, registered = true, hookActive = false))
        assertFalse(appsEntryShows(isTv = true, registered = false, hookActive = true))
        assertFalse(appsEntryShows(isTv = false, registered = true, hookActive = true))
    }

    // FR-11 — Ravilo is never listed, the rest are A–Z by label.
    @Test fun raviloIsLeftOutAndTheRestAreAlphabetical() {
        assertEquals(listOf("DR TV", "Kodi", "Netflix", "VLC", "YouTube"), visibleApps(TV, "dev.ravilo").map { it.label })
    }

    @Test fun labelOrderIgnoresCase() {
        val apps = listOf(app("zeta"), app("Alpha"), app("beta"))
        assertEquals(listOf("Alpha", "beta", "zeta"), visibleApps(apps, "x").map { it.label })
    }

    // FR-11 — settings, the stock home, the pins in the viewer's order, then See all apps.
    @Test fun entriesAreSettingsStockHomePinsThenSeeAll() {
        val m = model(FakeApps(TV), FakeStore(pins = listOf("app.youtube", "app.drtv")))
        assertEquals(
            listOf(
                AppsEntry.Settings, AppsEntry.StockHome("Google TV"),
                AppsEntry.Pinned(app("YouTube")), AppsEntry.Pinned(app("DR TV")),
                AppsEntry.SeeAll,
            ),
            m.entries(),
        )
    }

    // FR-11 — a TV with no pins yet shows items 1, 2 and 4 only.
    @Test fun noPinsShowsSettingsStockHomeAndSeeAll() {
        assertEquals(listOf(AppsEntry.Settings, AppsEntry.StockHome("Google TV"), AppsEntry.SeeAll), model(FakeApps(TV)).entries())
    }

    // FR-5 — the stock home that cannot be found is not offered, instead of a tile that opens nothing.
    @Test fun noStockHomeLeavesItOut() {
        assertEquals(listOf(AppsEntry.Settings, AppsEntry.SeeAll), model(FakeApps(TV), stock = null).entries())
    }

    // FR-11 — a pin on Ravilo itself can never show.
    @Test fun raviloCannotBePinned() {
        val store = FakeStore()
        model(FakeApps(TV), store).pin("dev.ravilo")
        assertEquals(emptyList(), store.pins)
    }

    // FR-11 — the list is read again each time: an installed app shows up with no restart.
    @Test fun anInstalledAppShowsUpOnTheNextRead() {
        val platform = FakeApps(TV)
        val m = model(platform)
        assertEquals(5, m.apps().size)
        platform.installed = TV + app("Plex")
        assertTrue(m.apps().any { it.label == "Plex" })
    }

    // FR-11 — Settings and an app open through the platform; the same screens need no network because nothing here asks for one.
    @Test fun settingsAndAppsOpenThroughThePlatform() {
        val platform = FakeApps(TV)
        val m = model(platform)
        m.openSettings()
        assertEquals(1, platform.settingsOpened)
        assertTrue(m.open("app.kodi"))
        assertEquals(listOf("app.kodi"), platform.launched)
    }
}

class TvAppsPinsTest {
    // FR-14 — new pins go at the end, and pinning twice changes nothing.
    @Test fun newPinsGoAtTheEnd() {
        val store = FakeStore()
        val m = model(FakeApps(TV), store)
        m.pin("app.netflix"); m.pin("app.kodi"); m.pin("app.netflix")
        assertEquals(listOf("app.netflix", "app.kodi"), store.pins)
    }

    @Test fun unpinRemovesOnlyThatApp() {
        val store = FakeStore(pins = listOf("app.netflix", "app.kodi", "app.vlc"))
        model(FakeApps(TV), store).unpin("app.kodi")
        assertEquals(listOf("app.netflix", "app.vlc"), store.pins)
    }

    @Test fun moveLeftAndRight() {
        val store = FakeStore(pins = listOf("app.netflix", "app.kodi", "app.vlc"))
        val m = model(FakeApps(TV), store)
        m.move("app.vlc", PinMove.LEFT)
        assertEquals(listOf("app.netflix", "app.vlc", "app.kodi"), store.pins)
        m.move("app.netflix", PinMove.RIGHT)
        assertEquals(listOf("app.vlc", "app.netflix", "app.kodi"), store.pins)
    }

    // FR-14 — a move off either end does nothing.
    @Test fun moveOffTheEndsDoesNothing() {
        val store = FakeStore(pins = listOf("app.netflix", "app.kodi"))
        val m = model(FakeApps(TV), store)
        m.move("app.netflix", PinMove.LEFT)
        m.move("app.kodi", PinMove.RIGHT)
        assertEquals(listOf("app.netflix", "app.kodi"), store.pins)
    }

    // FR-14 — an uninstalled pin drops out; installed again, it is not pinned.
    @Test fun anUninstalledPinDropsOutAndIsNotPinnedWhenInstalledAgain() {
        val platform = FakeApps(TV)
        val store = FakeStore(pins = listOf("app.netflix", "app.kodi"))
        val m = model(platform, store)
        platform.installed = TV.filter { it.packageName != "app.kodi" }
        assertEquals(listOf(AppsEntry.Settings, AppsEntry.StockHome("Google TV"), AppsEntry.Pinned(app("Netflix")), AppsEntry.SeeAll), m.entries())
        assertEquals(listOf("app.netflix"), store.pins)
        platform.installed = TV
        assertTrue(m.entries().none { it is AppsEntry.Pinned && it.app.packageName == "app.kodi" })
        assertTrue(m.allApps().first { it.app.packageName == "app.kodi" }.pinned.not())
    }

    // FR-15 — All apps lists every app, the pinned ones marked.
    @Test fun allAppsMarksThePinnedOnes() {
        val m = model(FakeApps(TV), FakeStore(pins = listOf("app.kodi")))
        val rows = m.allApps()
        assertEquals(5, rows.size)
        assertEquals(listOf("Kodi"), rows.filter { it.pinned }.map { it.app.label })
    }
}

class TvAppsOrderTest {
    // FR-15 — A–Z is the default.
    @Test fun azIsTheDefault() {
        val m = model(FakeApps(TV))
        assertEquals(AllAppsOrder.AZ, m.order)
        assertEquals(listOf("DR TV", "Kodi", "Netflix", "VLC", "YouTube"), m.allApps().map { it.app.label })
    }

    // FR-15 — Recently opened: what Ravilo opened newest first, then every other app A–Z.
    @Test fun recentlyOpenedIsNewestFirstThenTheRestAlphabetical() {
        var t = 1_000L
        val m = model(FakeApps(TV), clock = { t })
        m.open("app.youtube"); t += 10
        m.open("app.drtv"); t += 10
        m.open("app.kodi"); t += 10
        m.open("app.youtube")
        assertEquals(listOf("YouTube", "Kodi", "DR TV", "Netflix", "VLC"), m.allApps(AllAppsOrder.RECENT).map { it.app.label })
    }

    // FR-15 — with nothing recorded yet it is A–Z.
    @Test fun recentlyOpenedWithNoRecordIsAlphabetical() {
        assertEquals(listOf("DR TV", "Kodi", "Netflix", "VLC", "YouTube"), model(FakeApps(TV)).allApps(AllAppsOrder.RECENT).map { it.app.label })
    }

    // FR-15 — the screen remembers the last order used, through the store.
    @Test fun theLastOrderIsRemembered() {
        val store = FakeStore()
        model(FakeApps(TV), store).order = AllAppsOrder.RECENT
        val later = model(FakeApps(TV), store)
        assertEquals(AllAppsOrder.RECENT, later.order)
        assertEquals(AllAppsOrder.RECENT, later.allApps().let { store.order })
    }

    // FR-15 — only an app Android opened moves up.
    @Test fun anAppThatDidNotOpenIsNotRecorded() {
        val platform = FakeApps(TV).also { it.launchWorks = false }
        val store = FakeStore()
        assertFalse(model(platform, store).open("app.kodi"))
        assertEquals(emptyMap(), store.lastOpened)
    }

    // FR-15 — the record carries only apps still on the TV.
    @Test fun theRecordDropsUninstalledApps() {
        val platform = FakeApps(TV)
        val store = FakeStore(lastOpened = mapOf("app.gone" to 1L))
        model(platform, store, clock = { 5L }).open("app.kodi")
        assertEquals(mapOf("app.kodi" to 5L), store.lastOpened)
    }
}
