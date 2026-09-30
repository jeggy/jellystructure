package dev.jellystructure.ravilo.ui.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * R328/R329 — what the shared UI needs to know about the app's one window, and the two things it may ask of it.
 * `:ravilo-desktop` owns the window and keeps this up to date; the seams in `ravilo-ui` read it. Defaults describe
 * a shown, focused window, so anything that runs without the app (a test) behaves as on screen.
 */
object DesktopWindow {
    private val _shown = MutableStateFlow(true)
    private val _minimised = MutableStateFlow(false)
    private val _focused = MutableStateFlow(true)
    private val _fullScreen = MutableStateFlow(false)

    /** The window is on screen: not hidden (D7 keeps the app alive with the window hidden) and not in the Dock. */
    val shown: StateFlow<Boolean> = _shown.asStateFlow()
    val minimised: StateFlow<Boolean> = _minimised.asStateFlow()
    val focused: StateFlow<Boolean> = _focused.asStateFlow()
    val fullScreen: StateFlow<Boolean> = _fullScreen.asStateFlow()

    /** The AWT window, for the cursor. */
    @Volatile var awtWindow: java.awt.Window? = null

    /** The Settings window while it is open (the test driver's `w2`). */
    @Volatile var secondWindow: java.awt.Window? = null

    /** The app's full-screen switch (FR-R329-7); a no-op until the app installs it. */
    @Volatile var setFullScreenHandler: (Boolean) -> Unit = {}

    /**
     * R337 (FR-R337-5, dev review 11) — Ravilo draws the window's frame itself: GNOME (every Linux desktop) gets an
     * undecorated window with our header bars; `-Dravilo.csd=false` falls back to the system's title bar above them.
     */
    val drawsOwnFrame: Boolean = !DesktopPaths.isMac && System.getProperty("ravilo.csd") != "false"

    /** R337 — the app's own close (the D7 rule), maximise toggle and About window; no-ops until the app installs them. */
    @Volatile var closeHandler: () -> Unit = {}
    @Volatile var maximizeHandler: () -> Unit = {}
    @Volatile var minimizeHandler: () -> Unit = {}
    fun requestMinimize() = minimizeHandler()

    /** The window fills the screen (maximised or full screen): its corners are square then, as GNOME's own are. */
    private val _filled = MutableStateFlow(false)
    val filled: StateFlow<Boolean> = _filled.asStateFlow()
    fun reportFilled(filled: Boolean) { _filled.value = filled }

    /**
     * R337 — which window buttons the desktop wants and on which side, where Ravilo draws the frame. GNOME says it in
     * `button-layout` (close alone on the right by default; Tweaks adds minimise and maximise, or moves them left);
     * the app follows it live. Where nothing says, GNOME's default.
     */
    data class Controls(val start: List<Control>, val end: List<Control>)
    enum class Control { MINIMIZE, MAXIMIZE, CLOSE }
    fun parseControls(layout: String?): Controls {
        fun side(s: String) = s.split(',').mapNotNull {
            when (it.trim()) { "close" -> Control.CLOSE; "minimize" -> Control.MINIMIZE; "maximize" -> Control.MAXIMIZE; else -> null }
        }
        val parts = (layout ?: "appmenu:close").split(':')
        val parsed = Controls(side(parts.getOrElse(0) { "" }), side(parts.getOrElse(1) { "" }))
        // A layout with no close button at all would leave the window without one: GNOME's default then.
        return if (Control.CLOSE in parsed.start || Control.CLOSE in parsed.end) parsed else Controls(parsed.start, parsed.end + Control.CLOSE)
    }
    private val _controls by lazy {
        MutableStateFlow(parseControls(if (drawsOwnFrame) runCatching { LinuxPortal.buttonLayout() }.getOrNull() else null)).also { flow ->
            if (drawsOwnFrame) runCatching { LinuxPortal.watchButtonLayout { flow.value = parseControls(it) } }
        }
    }
    val controls: StateFlow<Controls> get() = _controls
    @Volatile var aboutHandler: () -> Unit = {}
    fun requestClose() = closeHandler()
    fun toggleMaximized() = maximizeHandler()
    fun showAbout() = aboutHandler()

    /** True while a film's player is on screen (F, Esc and double-click mean something only then). */
    @Volatile var playerActive: Boolean = false

    /** FR-R329-7 — whether the player put the window into full screen; only then does leaving the player leave it. */
    @Volatile var fullScreenByPlayer: Boolean = false

    fun report(shown: Boolean, minimised: Boolean, focused: Boolean, fullScreen: Boolean) {
        _shown.value = shown
        _minimised.value = minimised
        _focused.value = focused
        if (!fullScreen) fullScreenByPlayer = false
        _fullScreen.value = fullScreen
    }

    /** The player's F key and double-click. */
    fun togglePlayerFullScreen() {
        val entering = !_fullScreen.value
        if (entering) fullScreenByPlayer = true
        setFullScreenHandler(entering)
    }

    /** Leaving the player: out of full screen only if the player went in (FR-R329-7). */
    fun leavePlayer() {
        playerActive = false
        if (fullScreenByPlayer && _fullScreen.value) setFullScreenHandler(false)
        fullScreenByPlayer = false
    }

    /** For the test driver: the Mac's traffic lights and who takes a click in the title area, as AppKit reports them. */
    fun chromeDebug(): String? = runCatching { MacNative.take(MacNative.lib?.ravilo_window_debug()) }.getOrNull()

    /**
     * For the test driver: what speaker and TV discovery sees right now — the Bonjour browser's state (1 = macOS
     * refused Local Network access), every device found, and whether each said our app can run on it.
     */
    fun castDebug(): String = runCatching {
        CastDiscovery.acquire()
        try {
            Thread.sleep(3_500)
            val raw = MacNative.lib?.let { MacNative.take(it.ravilo_bonjour_snapshot()) }?.lineSequence()?.firstOrNull() ?: "jmdns"
            val app = CastSenderDesktop.appIdForDebug()
            raw + " app=" + app + " | " + CastDiscovery.devices.value.joinToString(" | ") { d ->
                "${d.name} [${d.model}] ${d.host}:${d.port} ours=${app?.let { a -> CastAvailability.known(d, a) }}"
            }
        } finally { CastDiscovery.release() }
    }.getOrElse { "failed: ${it.message}" }
}
