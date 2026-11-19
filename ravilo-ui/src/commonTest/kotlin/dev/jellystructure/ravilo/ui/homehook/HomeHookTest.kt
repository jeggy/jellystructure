package dev.jellystructure.ravilo.ui.homehook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-4910 — the hook's decisions, as plain Kotlin with an injected clock. Spec: ravilo-can-be-the-tvs-home-screen.md. */
private class FakeStore : HomeHookStore {
    override var enabled = false
    override var paused = false
    override var launchedAt = 0L
    override var foreground = false
    override var closes: List<Long> = emptyList()
}

private class FakePlatform(var androidOn: Boolean = false, var label: String? = "Google TV", var lists: Boolean = true) : HomeHookPlatform {
    var opened = 0
    var settingsOpened = 0
    var stockOpened = 0
    override fun androidSwitchOn() = androidOn
    override fun androidListsService() = lists
    override fun openAndroidSettings() { settingsOpened++ }
    override fun openAndroidAccessibilitySettings() { opened++ }
    override fun stockHomeLabel() = label
    override fun openStockHome() { stockOpened++ }
}

class HomeHookStateTest {
    // FR-1 — the section exists on a TV that registered the hook, nowhere else.
    @Test fun sectionOnlyOnATvWithTheHook() {
        assertTrue(homeScreenSectionShows(isTv = true, platform = FakePlatform()))
        assertFalse(homeScreenSectionShows(isTv = false, platform = FakePlatform()))
        assertFalse(homeScreenSectionShows(isTv = true, platform = null))
        assertFalse(homeScreenSectionShows(isTv = false, platform = null))
    }

    // FR-2 — never On while Android's switch is off, whatever Ravilo's own says.
    @Test fun neverOnWhileAndroidIsOff() {
        assertEquals(HomeHookState.Off, homeHookState(false, false, false))
        assertEquals(HomeHookState.Off, homeHookState(false, true, false))
        assertEquals(HomeHookState.NeedsAndroidStep, homeHookState(true, false, false))
        assertEquals(HomeHookState.NeedsAndroidStep, homeHookState(true, false, true))
        assertEquals(HomeHookState.On, homeHookState(true, true, false))
    }

    // DEB-5061 (FR-2) — Android does not list the service yet: the restart state, ranked ahead of the one-step state, never On.
    @Test fun neverOnWhileAndroidDoesNotListTheService() {
        assertEquals(HomeHookState.Off, homeHookState(false, false, false, androidLists = false))
        assertEquals(HomeHookState.NeedsRestart, homeHookState(true, false, false, androidLists = false))
        assertEquals(HomeHookState.NeedsRestart, homeHookState(true, false, true, androidLists = false))
        assertEquals(HomeHookState.NeedsAndroidStep, homeHookState(true, false, false, androidLists = true))
    }

    @Test fun pausedShowsOnlyWhenBothSwitchesAreOn() = assertEquals(HomeHookState.Paused, homeHookState(true, true, true))

    // FR-3 / FR-4 — Back at the top of Home does nothing with the hook acting and leaves with it off.
    @Test fun backAtTheTopOfHome() {
        assertFalse(backAtHomeRootLeavesApp(hookActive = true))
        assertTrue(backAtHomeRootLeavesApp(hookActive = false))
    }

    // FR-3 — Home inside Ravilo.
    @Test fun homeInsideRavilo() {
        assertEquals(HomeRequestAction.ScrollToTop, homeRequestAction(stackSize = 1, topIsHomePage = true, topIsSignInOrPicker = false))
        assertEquals(HomeRequestAction.ResetToHome, homeRequestAction(stackSize = 3, topIsHomePage = false, topIsSignInOrPicker = false))   // a player under pages
        assertEquals(HomeRequestAction.ResetToHome, homeRequestAction(stackSize = 1, topIsHomePage = false, topIsSignInOrPicker = false))   // a page that replaced Home
        assertEquals(HomeRequestAction.ResetToHome, homeRequestAction(stackSize = 2, topIsHomePage = true, topIsSignInOrPicker = false))
        assertEquals(HomeRequestAction.Ignore, homeRequestAction(stackSize = 1, topIsHomePage = false, topIsSignInOrPicker = true))
    }
}

class HomeHookControllerTest {
    private val store = FakeStore()
    private val platform = FakePlatform()
    private val controller = HomeHookController(store, platform)

    @Test fun offByDefault() {
        assertEquals(HomeHookState.Off, controller.state())
        assertFalse(controller.active())
        assertFalse(controller.shownOn())
    }

    // FR-2 — turning it on while Android's switch is off asks for the plain-words screen, and never reads On.
    @Test fun onWhileAndroidIsOffNeedsTheDisclosure() {
        assertEquals(HomeHookToggle.NeedsDisclosure, controller.toggle())
        assertTrue(store.enabled)
        assertEquals(HomeHookState.NeedsAndroidStep, controller.state())
        assertFalse(controller.shownOn())
        assertFalse(controller.active())
        controller.continueToAndroid()
        assertEquals(1, platform.opened)
    }

    // DEB-5061 (FR-2) — Android does not list Ravilo yet: the restart state, and the button opens Android's settings, not the accessibility page.
    @Test fun notListedShowsTheRestartStateAndOpensAndroidSettings() {
        platform.lists = false
        assertEquals(HomeHookToggle.NeedsDisclosure, controller.toggle())
        assertEquals(HomeHookState.NeedsRestart, controller.state())
        assertFalse(controller.shownOn())
        assertFalse(controller.active())
        assertTrue(controller.needsRestart())
        controller.continueToAndroid()
        assertEquals(1, platform.settingsOpened)
        assertEquals(0, platform.opened)
    }

    // DEB-5061 (FR-2) — after the restart Android lists Ravilo: the one-step state as before, and the accessibility page opens.
    @Test fun onceListedTheOneStepStateShowsAsBefore() {
        platform.lists = false
        controller.toggle()
        platform.lists = true
        assertEquals(HomeHookState.NeedsAndroidStep, controller.state())
        assertFalse(controller.needsRestart())
        controller.continueToAndroid()
        assertEquals(1, platform.opened)
        assertEquals(0, platform.settingsOpened)
        // and Android's switch on returns the setting to On with no second press
        platform.androidOn = true
        assertEquals(HomeHookState.On, controller.state())
    }

    // DEB-5061 (FR-2) — pressing the setting again in the restart state asks for the plain-words screen again.
    @Test fun pressingAgainInTheRestartStateAsksForTheScreenAgain() {
        platform.lists = false
        controller.toggle()
        assertEquals(HomeHookToggle.NeedsDisclosure, controller.toggle())
        assertTrue(store.enabled)
    }

    // FR-2 — the viewer comes back with Android's switch on: the setting shows the true state, with no second press.
    @Test fun comingBackFromAndroidShowsTheTrueState() {
        controller.toggle()
        platform.androidOn = true
        assertEquals(HomeHookState.On, controller.state())
        assertTrue(controller.active())
    }

    @Test fun onWhileAndroidIsOnNeedsNoDisclosure() {
        platform.androidOn = true
        assertEquals(HomeHookToggle.TurnedOn, controller.toggle())
        assertTrue(controller.shownOn())
    }

    // FR-4 — off at once, and Android's switch is left as it was: turning it on again needs no trip to Android.
    @Test fun offAtOnceAndTurningItOnAgainNeedsNoTrip() {
        platform.androidOn = true
        controller.toggle()
        assertEquals(HomeHookToggle.TurnedOff, controller.toggle())
        assertEquals(HomeHookState.Off, controller.state())
        assertTrue(platform.androidOn)
        assertEquals(HomeHookToggle.TurnedOn, controller.toggle())
        assertEquals(0, platform.opened)
    }

    // FR-4 — Android's switch turned off while Ravilo's is on: the one-step state, not On.
    @Test fun androidTurnedOffShowsTheOneStepState() {
        platform.androidOn = true
        controller.toggle()
        platform.androidOn = false
        assertEquals(HomeHookState.NeedsAndroidStep, controller.state())
        assertFalse(controller.active())
    }

    // FR-8 — paused: the setting says so, and one press (off and on again) clears it.
    @Test fun pausedResumesOnlyWhenTurnedOffAndOn() {
        platform.androidOn = true
        controller.toggle()
        store.paused = true
        store.closes = listOf(1L, 2L)
        assertEquals(HomeHookState.Paused, controller.state())
        assertFalse(controller.active())
        assertEquals(HomeHookToggle.TurnedOn, controller.toggle())
        assertFalse(store.paused)
        assertTrue(store.closes.isEmpty())
        assertEquals(HomeHookState.On, controller.state())
    }

    @Test fun offThenOnAlsoClearsAPause() {
        platform.androidOn = true
        controller.toggle()
        store.paused = true
        // the hook state is Paused: pausing is cleared by the next off→on
        controller.toggle()
        assertFalse(store.paused)
    }

    // FR-5 — the stock home's own label.
    @Test fun stockHomeLabelIsAndroids() {
        assertEquals("Google TV", controller.stockHomeLabel())
        platform.label = null
        assertEquals(null, controller.stockHomeLabel())
    }
}

class HomeHookDeciderTest {
    private val own = "dev.jellystructure.ravilo"
    private val stock = StockHome("com.example.tvhome", "com.example.tvhome.HomeActivity")
    private val decider = HomeHookDecider(own)

    private fun home() = WindowChange(stock.packageName, stock.className)

    // FR-3 — the stock home's home screen comes forward: Ravilo comes up.
    @Test fun takesTheStockHomesHomeScreen() = assertTrue(decider.onWindowChange(home(), stock, hookActive = true))

    // FR-4 — off: nothing is taken.
    @Test fun leavesItAloneWhenOff() = assertFalse(decider.onWindowChange(home(), stock, hookActive = false))

    // FR-6 — the stock home's search and voice results, its panels, are other windows of the same package.
    @Test fun leavesSearchResultsAndPanelsAlone() {
        for (cls in listOf("com.example.tvhome.SearchResultsActivity", "com.example.tvhome.VoiceResults", "com.example.tvhome.QuickSettingsPanel", "android.app.Dialog", null)) {
            assertFalse(decider.onWindowChange(WindowChange(stock.packageName, cls), stock, true), "class $cls")
        }
    }

    // FR-6 — the input picker, the screensaver, Cast, Android's settings, the Assistant: other packages.
    @Test fun leavesOtherPackagesAlone() {
        for (pkg in listOf("com.android.tv.settings", "com.android.systemui", "com.google.android.katniss", "com.google.android.dreams.basic", "com.google.android.apps.mediashell")) {
            assertFalse(decider.onWindowChange(WindowChange(pkg, "x.Y"), stock, true), pkg)
        }
    }

    // FR-6 — the stock home is whatever Android resolved: with none resolved, nothing is taken.
    @Test fun noStockHomeNothingTaken() = assertFalse(decider.onWindowChange(home(), null, true))

    // Ravilo's own windows are never the stock home, and never "something else".
    @Test fun ravilosOwnWindowsAreIgnored() = assertFalse(decider.onWindowChange(WindowChange(own, "x.MainActivity"), stock, true))

    // FR-5 — let through until the viewer opens something else, then taken again.
    @Test fun passThroughHoldsUntilSomethingElseOpens() {
        decider.letStockHomeStay()
        assertFalse(decider.onWindowChange(home(), stock, true))                                                   // the stock home itself
        assertFalse(decider.onWindowChange(WindowChange(stock.packageName, "x.SearchResults"), stock, true))       // browsing inside it
        assertFalse(decider.onWindowChange(home(), stock, true))                                                   // back to its home screen: still let through
        assertTrue(decider.passThrough)
        assertFalse(decider.onWindowChange(WindowChange("com.example.video", "x.Player"), stock, true))             // the viewer opened an app
        assertFalse(decider.passThrough)
        assertTrue(decider.onWindowChange(home(), stock, true))                                                    // next time, Ravilo takes it
    }

    @Test fun ravilosOwnWindowDoesNotEndThePassThrough() {
        decider.letStockHomeStay()
        assertFalse(decider.onWindowChange(WindowChange(own, "x.MainActivity"), stock, true))
        assertTrue(decider.passThrough)
        assertFalse(decider.onWindowChange(home(), stock, true))
    }
}

class HomeHookGuardTest {
    private val store = FakeStore()
    private var now = 1_000_000L
    private val guard = HomeHookGuard(store) { now }

    private fun crashAfterHookLaunch(secondsAfterLaunch: Int = 10) {
        guard.hookLaunched()
        guard.foregroundChanged(true)
        now += secondsAfterLaunch * 1000L
        // the process dies; the next start finds the mark still set
    }

    // FR-8 — one unexpected close is not enough.
    @Test fun oneCloseDoesNotPause() {
        crashAfterHookLaunch()
        assertFalse(guard.processStarted())
        assertFalse(store.paused)
    }

    // FR-8 — two within five minutes pause it.
    @Test fun twoClosesWithinFiveMinutesPause() {
        crashAfterHookLaunch()
        guard.processStarted()
        crashAfterHookLaunch()
        assertTrue(guard.processStarted())
        assertTrue(store.paused)
    }

    // FR-8 — two closes more than five minutes apart do not.
    @Test fun closesFarApartDoNotPause() {
        crashAfterHookLaunch()
        guard.processStarted()
        now += 6 * 60_000L
        crashAfterHookLaunch()
        assertFalse(guard.processStarted())
        assertFalse(store.paused)
    }

    // A clean leave (Home, another app) clears the mark: it was not a close.
    @Test fun aCleanLeaveIsNotAClose() {
        crashAfterHookLaunch()
        guard.foregroundChanged(false)
        assertFalse(guard.processStarted())
        assertTrue(store.closes.isEmpty())
    }

    // A Ravilo the viewer opened themselves is not watched.
    @Test fun aLaunchByTheViewerIsNotWatched() {
        guard.foregroundChanged(true)
        assertFalse(store.foreground)
        assertFalse(guard.processStarted())
    }

    // The launch must be within five minutes of the close: an old hooked launch is not "just brought up".
    @Test fun aCloseLongAfterTheLaunchDoesNotCount() {
        guard.hookLaunched()
        guard.foregroundChanged(true)
        now += 10 * 60_000L
        assertFalse(guard.processStarted())
        assertTrue(store.closes.isEmpty())
    }

    @Test fun staysPausedUntilTheSettingClearsIt() {
        crashAfterHookLaunch(); guard.processStarted()
        crashAfterHookLaunch(); guard.processStarted()
        now += 60 * 60_000L
        assertTrue(guard.processStarted())   // a later start does not resume it
    }
}
