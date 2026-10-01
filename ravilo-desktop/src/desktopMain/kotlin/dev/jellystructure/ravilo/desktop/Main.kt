package dev.jellystructure.ravilo.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.jellystructure.ravilo.i18n.LastLanguage
import dev.jellystructure.ravilo.ui.RaviloRoot
import dev.jellystructure.ravilo.ui.TeardownWork
import dev.jellystructure.ravilo.ui.components.AppCommand
import dev.jellystructure.ravilo.ui.components.AppCommands
import dev.jellystructure.ravilo.ui.desktop.DesktopLog
import dev.jellystructure.ravilo.ui.desktop.DesktopShutdown
import dev.jellystructure.ravilo.ui.desktop.DesktopWindow
import dev.jellystructure.ravilo.ui.desktop.SelfTest
import dev.jellystructure.ravilo.ui.desktop.UpdateCheck
import dev.jellystructure.ravilo.ui.music.MusicEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Dimension
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * R328 — Ravilo on the Mac. `--self-test` (R331 FR-R331-4) answers without opening a window; otherwise one window
 * shows ravilo-ui's whole app. Closing it quits unless music is playing, when the app stays in the Dock and the
 * music goes on (D7); ⌘Q always quits, after the stops have been reported (FR-R328-9).
 */
fun main(args: Array<String>) {
    if ("--self-test" in args) exitProcess(SelfTest.run())
    // R335 (FR-R335-11) — the Linux engine alone, no window: `Ravilo --mpv-bench <file> [seconds]`.
    args.indexOf("--mpv-bench").takeIf { it >= 0 }?.let { exitProcess(dev.jellystructure.ravilo.ui.desktop.MpvBench.run(args.drop(it + 1))) }
    // R335 (FR-R335-4) — path (b) draws the chrome over a native child window, which Compose does only with this set first.
    if (System.getProperty("ravilo.video") == "gpu") System.setProperty("compose.interop.blending", "true")
    args.indexOf("--mpv-window").takeIf { it >= 0 }?.let { dev.jellystructure.ravilo.ui.desktop.MpvBench.window(args.drop(it + 1)); exitProcess(0) }
    DesktopLog.install()
    // R337 (dev review 12) — one Ravilo per Linux session: a second launch shows the running one's window and leaves.
    if (!dev.jellystructure.ravilo.ui.desktop.SingleInstance.claim()) exitProcess(0)
    println("${DesktopLog.stamp()} Ravilo ${dev.jellystructure.shared.raviloVersion()} starting · Mac library: ${if (dev.jellystructure.ravilo.ui.desktop.MacNative.lib != null) "loaded" else "absent (${dev.jellystructure.ravilo.ui.desktop.MacNative.loadError})"}")
    // FR-R328-5 — before AWT loads: the menu bar in macOS's own bar, the app's name in it, a dark title bar.
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.application.name", "Ravilo")
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    UpdateCheck.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    application { RaviloDesktopApp() }
}

@OptIn(FlowPreview::class)
@Composable
private fun ApplicationScope.RaviloDesktopApp() {
    var shown by remember { mutableStateOf(true) }
    var aboutOpen by remember { mutableStateOf(false) }
    // The menu bar speaks the device's language (R279's middle rung), refreshed as the viewer changes it.
    val lang by produceState(LastLanguage.read() ?: "en") {
        while (true) { delay(2_000); value = LastLanguage.read() ?: "en" }
    }
    // FR-R328-9 — quitting first takes the app out of the window, so a playing film's screen disposes and sends its
    // stop (phase 180); then those stops get two seconds to reach the server before the process ends.
    var quitting by remember { mutableStateOf(false) }
    val quit: () -> Unit = remember { { SwingUtilities.invokeLater { quitting = true } } }
    LaunchedEffect(quitting) {
        if (!quitting) return@LaunchedEffect
        delay(150)
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(2_000) { TeardownWork.awaitAll() }
            DesktopShutdown.runAll()
        }
        exitApplication()
    }
    val bounds = remember { WindowBounds.load() }
    val state = rememberWindowState(position = bounds.position, size = bounds.size)
    fun close() {
        if (MusicEngine.state.value.playing) {
            shown = false
            // FR-R337-12 — on Linux the first close while music plays asks the Background portal once.
            dev.jellystructure.ravilo.ui.desktop.DesktopBackground.onCloseWhilePlaying(quit)
        } else quit()
    }
    fun openSettings() { shown = true; AppCommands.send(AppCommand.OPEN_SETTINGS) }
    fun setFullScreen(on: Boolean) { state.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating }

    LaunchedEffect(Unit) {
        MacAppHooks.install(
            onAbout = { aboutOpen = true },
            onSettings = ::openSettings,
            onQuit = quit,
            onReopen = { shown = true },
        )
        // R342 — AWT (and so NSApp) is up: the running Dock icon starts following the mode and the icon style.
        dev.jellystructure.ravilo.ui.desktop.DesktopDock.start()
    }

    Window(
        onCloseRequest = ::close,
        state = state,
        visible = shown,
        title = "Ravilo",
        icon = AppImages.icon,
        // R337 (FR-R337-5, dev review 11) — GNOME's shape is drawn by Ravilo: an undecorated window with our header bars.
        undecorated = DesktopWindow.drawsOwnFrame,
        // …with libadwaita's round corners: the window is see-through and the app is clipped to the rounded shape.
        transparent = DesktopWindow.drawsOwnFrame,
        onPreviewKeyEvent = { playerWindowKeys(it) || desktopKeys(it, quit) },
    ) {
        val focused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(360, 600)   // R337 FR-R337-11 (was 960 × 600): a window can reach the phone layout
            DesktopWindow.awtWindow = window
            TestDriver.start(window)   // off unless RAVILO_TESTDRIVER names a directory
            DesktopWindow.setFullScreenHandler = ::setFullScreen
            // R337 — what the frame the app draws asks of the window.
            DesktopWindow.closeHandler = { SwingUtilities.invokeLater { close() } }
            DesktopWindow.maximizeHandler = {
                state.placement = if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
            }
            DesktopWindow.minimizeHandler = { state.isMinimized = true }
            DesktopWindow.aboutHandler = { aboutOpen = true }
            dev.jellystructure.ravilo.ui.desktop.SingleInstance.onShowRequest { SwingUtilities.invokeLater { shown = true; window.toFront() } }
            // R337 (FR-R337-5, dev review 10) — the Mac: a transparent, full-size-content title bar, so the traffic lights
            // sit in the sidebar and the page runs up under them.
            if (dev.jellystructure.ravilo.ui.desktop.DesktopPaths.isMac) window.rootPane.run {
                putClientProperty("apple.awt.fullWindowContent", true)
                putClientProperty("apple.awt.transparentTitleBar", true)
                putClientProperty("apple.awt.windowTitleVisible", false)
            }
        }
        // Shown again from the Dock or the menu: in front. Only then — raising on every focus change would bury
        // the About window under the main one.
        LaunchedEffect(shown) { if (shown) window.toFront() }
        LaunchedEffect(shown, state.isMinimized, focused, state.placement) {
            DesktopWindow.report(shown, state.isMinimized, focused, state.placement == WindowPlacement.Fullscreen)
        }
        // R337 — a window that fills the screen has square corners; a floating one has libadwaita's 12 dp.
        val filled = state.placement != WindowPlacement.Floating
        LaunchedEffect(filled) { DesktopWindow.reportFilled(filled) }
        LaunchedEffect(state) {
            snapshotFlow { Triple(state.position, state.size, state.placement) }
                .debounce(500)
                .collect { (position, size, placement) -> if (placement == WindowPlacement.Floating) WindowBounds.save(position, size) }
        }
        // R337 — the menu bar is the Mac's; on Linux the primary menu ☰ and the keys below replace it (dev review 11).
        if (dev.jellystructure.ravilo.ui.desktop.DesktopPaths.isMac) RaviloMenuBar(
            lang = lang,
            fullScreen = state.placement == WindowPlacement.Fullscreen,
            onAbout = { aboutOpen = true },
            onSettings = ::openSettings,
            onQuit = quit,
            onToggleFullScreen = { setFullScreen(state.placement != WindowPlacement.Fullscreen) },
            onMinimise = { state.isMinimized = true },
            onClose = ::close,
        )
        if (!quitting) {
            if (DesktopWindow.drawsOwnFrame) Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(if (filled) 0.dp else dev.jellystructure.ravilo.ui.seams.WINDOW_CORNER)),
            ) { RaviloRoot() }
            else RaviloRoot()
        }
    }

    if (aboutOpen) AboutWindow(lang = lang, onClose = { aboutOpen = false })
}

/**
 * FR-R329-6 — the player's window keys, above everything in the tree: **F** toggles full screen and **Esc** leaves
 * a full screen before it means Back. Everything else (Space, the arrows, Return) is the player's own.
 */
private fun playerWindowKeys(ev: KeyEvent): Boolean {
    if (ev.type != KeyEventType.KeyDown || !DesktopWindow.playerActive) return false
    return when {
        ev.key == Key.Escape && DesktopWindow.fullScreen.value -> { DesktopWindow.setFullScreenHandler(false); true }
        ev.key == Key.F && !ev.isMetaPressed && !ev.isCtrlPressed && !ev.isAltPressed -> { DesktopWindow.togglePlayerFullScreen(); true }
        else -> false
    }
}

/**
 * R337 (FR-R337-10) — the desktop's keys, for both platforms (⌘ on the Mac, Ctrl elsewhere). On the Mac the View menu
 * carries ⌘1/⌘2, ⌃⌘S, ⌥⌘U and ⌥⌘L itself; everything else is here. Linux has no menu bar, so all of them are here.
 * Esc = Back is the app's own (R328), and the mode keys work in the phone layout too.
 */
private fun desktopKeys(ev: KeyEvent, quit: () -> Unit): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    val mac = dev.jellystructure.ravilo.ui.desktop.DesktopPaths.isMac
    val cmd = if (mac) ev.isMetaPressed else ev.isCtrlPressed
    fun send(c: AppCommand): Boolean { AppCommands.send(c); return true }
    if (!mac && ev.key == Key.F11) { DesktopWindow.setFullScreenHandler(!DesktopWindow.fullScreen.value); return true }
    if (!cmd) return false
    return when {
        ev.key == Key.F -> send(AppCommand.SEARCH)
        ev.key == Key.DirectionUp -> send(AppCommand.VOLUME_UP)
        ev.key == Key.DirectionDown -> send(AppCommand.VOLUME_DOWN)
        ev.key == Key.DirectionRight && !DesktopWindow.playerActive -> send(AppCommand.NEXT_SONG)
        ev.key == Key.DirectionLeft && !DesktopWindow.playerActive -> send(AppCommand.PREVIOUS_SONG)
        mac -> false
        ev.key == Key.One -> send(AppCommand.MODE_VIDEO)
        ev.key == Key.Two -> send(AppCommand.MODE_MUSIC)
        ev.key == Key.U -> send(AppCommand.TOGGLE_QUEUE)
        ev.key == Key.L -> send(AppCommand.SHOW_LYRICS)
        ev.key == Key.S && ev.isShiftPressed -> send(AppCommand.TOGGLE_SIDEBAR)
        ev.key == Key.Comma -> send(AppCommand.OPEN_SETTINGS)
        ev.key == Key.Slash && ev.isShiftPressed -> send(AppCommand.SHORTCUTS)   // Ctrl+?
        ev.key == Key.Q -> { quit(); true }
        else -> false
    }
}
