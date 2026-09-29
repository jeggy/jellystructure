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

    /** The app's full-screen switch (FR-R329-7); a no-op until the app installs it. */
    @Volatile var setFullScreenHandler: (Boolean) -> Unit = {}

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
}
