package dev.jellystructure.ravilo.ui.homehook

import dev.jellystructure.ravilo.ui.isTvPlatform
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/*
 * DEB-4910 (specs/ravilo/requirements/ravilo-can-be-the-tvs-home-screen.md, FR-1–FR-9) — Ravilo as the TV's home screen
 * through an accessibility service, the *hook*. Everything that can be decided without Android is in this package, as plain
 * Kotlin with injected clocks: the switch's states, what counts as the stock home's home screen, and the crash guard. The
 * Android `actual` side (the service, the preferences file, Android's own switch) is `androidMain`'s homehook package.
 */

/** What Android knows and does on the hook's behalf. Registered by the Android TV build only; every other platform has none. */
interface HomeHookPlatform {
    /** Android's accessibility switch for Ravilo — the true state, read each time, never remembered. */
    fun androidSwitchOn(): Boolean

    /**
     * DEB-5061 (FR-2) — whether Android's accessibility manager lists Ravilo's service at all, on or off. Read each time from Android's
     * own list of installed services: on Android 14 and older it does not list a component enabled after its package was parsed
     * until the next app install, update or removal, or a boot.
     */
    fun androidListsService(): Boolean

    /** FR-2 — opens Android's accessibility settings (Ravilo's own page there, where the TV has one). The component is enabled at start (FR-7), not here. */
    fun openAndroidAccessibilitySettings()

    /** DEB-5061 (FR-2) — opens Android's settings, for the restart state: the accessibility page would not list Ravilo yet. */
    fun openAndroidSettings()

    /** FR-5 — the stock home's own label as Android reports it; null when it cannot be found. */
    fun stockHomeLabel(): String?

    /** FR-5 — opens the stock home and lets it stay until the viewer opens something else. */
    fun openStockHome()
}

/** What is kept on the TV: its own preferences file, never per viewer, never in `RaviloConfig`, never synced (FR-1). */
interface HomeHookStore {
    /** Ravilo's switch, off by default. */
    var enabled: Boolean

    /** FR-8 — the guard stopped the hook; only turning the switch off and on clears it. */
    var paused: Boolean

    /** Epoch ms of the last time the hook brought Ravilo up; 0 when it never has. */
    var launchedAt: Long

    /** True while a hook-launched Ravilo is on screen; a process that starts with this still true was closed under it. */
    var foreground: Boolean

    /** Epoch ms of the unexpected closes the guard still counts. */
    var closes: List<Long>
}

/** What the Home screen section shows. */
enum class HomeHookState {
    /** Ravilo's switch is off. */
    Off,

    /** DEB-5061 (FR-2) — Ravilo's switch is on and Android does not list Ravilo's service yet: one restart first, never shown as On. */
    NeedsRestart,

    /** Ravilo's switch is on and Android's is off: never shown as On (FR-2). */
    NeedsAndroidStep,

    /** FR-8. */
    Paused,

    /** Both switches on and the hook acting. */
    On,
}

/** FR-2 / FR-4 / FR-8 — one function, so the section and the Back rule cannot disagree about it. */
fun homeHookState(raviloSwitch: Boolean, androidSwitch: Boolean, paused: Boolean, androidLists: Boolean = true): HomeHookState = when {
    !raviloSwitch -> HomeHookState.Off
    !androidSwitch && !androidLists -> HomeHookState.NeedsRestart
    !androidSwitch -> HomeHookState.NeedsAndroidStep
    paused -> HomeHookState.Paused
    else -> HomeHookState.On
}

/** FR-1 — the section exists on an Android TV that registered the hook, and nowhere else. */
fun homeScreenSectionShows(isTv: Boolean, platform: HomeHookPlatform?): Boolean = isTv && platform != null

/** What pressing the switch asks the screen to do next. */
enum class HomeHookToggle { TurnedOff, TurnedOn, NeedsDisclosure }

class HomeHookController(private val store: HomeHookStore, private val platform: HomeHookPlatform) {
    fun state(): HomeHookState {
        val androidOn = platform.androidSwitchOn()
        // Android's switch on means Android lists the service: the list is only asked while the switch is off.
        return homeHookState(store.enabled, androidOn, store.paused, androidOn || platform.androidListsService())
    }

    /** True only while the hook is acting: Back at the top of Home then does nothing (FR-3). */
    fun active(): Boolean = state() == HomeHookState.On

    /** The switch's pill: On only while the hook is really acting. */
    fun shownOn(): Boolean = active()

    /**
     * FR-2/FR-4/FR-8. Off or on-but-waiting-for-Android turns it on (with FR-2's screen first when Android's switch is off); On turns
     * it off; Paused is the viewer's "off and on again" in one press: the pause is cleared and the hook runs again.
     */
    fun toggle(): HomeHookToggle = when (state()) {
        HomeHookState.On -> { store.enabled = false; HomeHookToggle.TurnedOff }
        HomeHookState.Paused -> { store.paused = false; store.closes = emptyList(); HomeHookToggle.TurnedOn }
        HomeHookState.Off, HomeHookState.NeedsRestart, HomeHookState.NeedsAndroidStep -> {
            store.enabled = true
            store.paused = false
            store.closes = emptyList()
            if (platform.androidSwitchOn()) HomeHookToggle.TurnedOn else HomeHookToggle.NeedsDisclosure
        }
    }

    /** DEB-5061 (FR-2) — whether the plain-words screen asks for the restart first; read each time it is drawn. */
    fun needsRestart(): Boolean = !platform.androidSwitchOn() && !platform.androidListsService()

    /** FR-2 — the disclosure screen's button: Android's accessibility page once Android lists Ravilo, Android's settings before that. */
    fun continueToAndroid() = if (needsRestart()) platform.openAndroidSettings() else platform.openAndroidAccessibilitySettings()

    fun stockHomeLabel(): String? = platform.stockHomeLabel()

    /** FR-5. */
    fun openStockHome() = platform.openStockHome()
}

/** FR-3/FR-4 — Back at the top of Home leaves the app only while the hook is not acting: with it acting, leaving would bring it back. */
fun backAtHomeRootLeavesApp(hookActive: Boolean): Boolean = !hookActive

/** What Ravilo does when the hook brings it up while it is already running (Home pressed inside Ravilo). */
enum class HomeRequestAction { Ignore, ScrollToTop, ResetToHome }

/**
 * FR-3 — Home inside Ravilo lands on the top of Home. Sign-in and the profile picker have no Home to go to and stay as they are;
 * Home itself (alone on the stack) goes to its top; anything else, a player and every page above Home included, is closed to Home.
 */
fun homeRequestAction(stackSize: Int, topIsHomePage: Boolean, topIsSignInOrPicker: Boolean): HomeRequestAction = when {
    topIsSignInOrPicker -> HomeRequestAction.Ignore
    stackSize == 1 && topIsHomePage -> HomeRequestAction.ScrollToTop
    else -> HomeRequestAction.ResetToHome
}

/** A window that came forward, as the service hears it. */
data class WindowChange(val packageName: String, val className: String?)

/** The stock home: whatever Android resolves the Home intent to (FR-6), never a hard-coded package. */
data class StockHome(val packageName: String, val className: String)

/**
 * FR-6 and FR-5 — decides, per window change, whether Ravilo should come up. It acts only when the stock home's *home screen*
 * comes forward: the stock home's search and voice results, its panels, the input picker, the screensaver, Cast and the
 * Assistant are other classes or other packages and are left alone.
 *
 * FR-5's pass-through: after Ravilo opens the stock home for the viewer ([letStockHomeStay]) the home screen is let through
 * until a window of some other package comes forward — the viewer opened something else — and then the next stock home screen
 * is taken again. Ravilo's own windows never count as "something else": they are what the viewer just left.
 */
class HomeHookDecider(private val ownPackage: String) {
    var passThrough: Boolean = false
        private set

    fun letStockHomeStay() { passThrough = true }

    fun onWindowChange(change: WindowChange, stock: StockHome?, hookActive: Boolean): Boolean {
        if (change.packageName == ownPackage) return false
        if (stock == null) return false
        if (change.packageName != stock.packageName) {
            passThrough = false
            return false
        }
        if (change.className != stock.className) return false
        if (!hookActive || passThrough) return false
        return true
    }
}

/** How long, and how many: FR-8's constants. */
const val HOME_HOOK_GUARD_WINDOW_MS = 5 * 60 * 1000L
const val HOME_HOOK_GUARD_CLOSES = 2

/**
 * FR-8 — a TV must never be left in a loop where every Home press opens an app that closes. A close is unexpected when a Ravilo
 * the hook brought up was on screen when its process ended: [foregroundChanged] marks that, a clean [foregroundChanged]`(false)`
 * clears it, and [processStarted] finds the mark still set. The close counts when the process starts again within five minutes
 * of the hook's launch; two counted closes within five minutes of each other pause the hook.
 */
class HomeHookGuard(private val store: HomeHookStore, private val now: () -> Long) {
    fun hookLaunched() { store.launchedAt = now() }

    /** Only a Ravilo the hook brought up within the last five minutes is watched: any other launch is the viewer's own. */
    fun foregroundChanged(foreground: Boolean) {
        store.foreground = foreground && store.launchedAt > 0 && now() - store.launchedAt <= HOME_HOOK_GUARD_WINDOW_MS
    }

    /** Called when the process starts (the service is rebound at once after a crash). Returns whether the hook is now paused. */
    fun processStarted(): Boolean {
        val t = now()
        if (store.foreground) {
            store.foreground = false
            if (t - store.launchedAt in 0..HOME_HOOK_GUARD_WINDOW_MS) {
                store.closes = (store.closes.filter { t - it in 0..HOME_HOOK_GUARD_WINDOW_MS } + t)
                if (store.closes.size >= HOME_HOOK_GUARD_CLOSES) store.paused = true
            }
        }
        return store.paused
    }
}

/** Where the Android side meets the common UI. Set on an Android TV only. */
object HomeHook {
    private var platformField: HomeHookPlatform? = null
    private var controllerField: HomeHookController? = null
    private var guardField: HomeHookGuard? = null

    val platform: HomeHookPlatform? get() = platformField
    val controller: HomeHookController? get() = controllerField

    /** Registered by [dev.jellystructure.ravilo.ui.RaviloAppContext] on a TV; null on every other platform. */
    fun install(platform: HomeHookPlatform, store: HomeHookStore, now: () -> Long) {
        platformField = platform
        controllerField = HomeHookController(store, platform)
        guardField = HomeHookGuard(store, now)
    }

    /** Tests only: a test that installs the hook must take it away again, or every later screen test in the JVM sees a TV with the hook. */
    internal fun uninstallForTest() { platformField = null; controllerField = null; guardField = null }

    /** Tests only: forgets what [install] registered, so a test that wired the hook cannot leak it into the next one in the same JVM. */
    fun resetForTest() { platformField = null; controllerField = null; guardField = null }

    /** FR-8 — the process started: a Ravilo the hook brought up that was on screen when the last one ended counts as a close. */
    fun processStarted() { guardField?.processStarted() }

    /** FR-8 — the TV activity came to / left the screen. */
    fun activityForeground(foreground: Boolean) { guardField?.foregroundChanged(foreground) }

    /** FR-1. */
    fun sectionShows(): Boolean = homeScreenSectionShows(isTvPlatform, platformField)

    /** FR-3 — whether Back at the top of Home leaves the app (off) or does nothing (the hook is acting). */
    fun active(): Boolean = controllerField?.active() == true

    private val goHome = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** FR-3 — the hook brought Ravilo up while it was already running: land on the top of Home. */
    val homeRequests: SharedFlow<Unit> get() = goHome

    fun requestHome() { goHome.tryEmit(Unit) }
}
