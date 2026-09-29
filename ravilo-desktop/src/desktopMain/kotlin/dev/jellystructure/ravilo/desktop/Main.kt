package dev.jellystructure.ravilo.desktop

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
import dev.jellystructure.ravilo.ui.components.AppCommand
import dev.jellystructure.ravilo.ui.components.AppCommands
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
    val quit: () -> Unit = remember {
        { SwingUtilities.invokeLater { DesktopShutdown.runAll(); exitApplication() } }
    }
    val bounds = remember { WindowBounds.load() }
    val state = rememberWindowState(position = bounds.position, size = bounds.size)
    fun close() { if (MusicEngine.state.value.playing) shown = false else quit() }
    fun openSettings() { shown = true; AppCommands.send(AppCommand.OPEN_SETTINGS) }
    fun setFullScreen(on: Boolean) { state.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating }

    LaunchedEffect(Unit) {
        MacAppHooks.install(
            onAbout = { aboutOpen = true },
            onSettings = ::openSettings,
            onQuit = quit,
            onReopen = { shown = true },
        )
    }

    Window(
        onCloseRequest = ::close,
        state = state,
        visible = shown,
        title = "Ravilo",
        icon = AppImages.icon,
        onPreviewKeyEvent = ::playerWindowKeys,
    ) {
        val focused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(960, 600)   // FR-R328-5
            DesktopWindow.awtWindow = window
            DesktopWindow.setFullScreenHandler = ::setFullScreen
        }
        // Shown again from the Dock or the menu: in front. Only then — raising on every focus change would bury
        // the About window under the main one.
        LaunchedEffect(shown) { if (shown) window.toFront() }
        LaunchedEffect(shown, state.isMinimized, focused, state.placement) {
            DesktopWindow.report(shown, state.isMinimized, focused, state.placement == WindowPlacement.Fullscreen)
        }
        LaunchedEffect(state) {
            snapshotFlow { Triple(state.position, state.size, state.placement) }
                .debounce(500)
                .collect { (position, size, placement) -> if (placement == WindowPlacement.Floating) WindowBounds.save(position, size) }
        }
        RaviloMenuBar(
            lang = lang,
            fullScreen = state.placement == WindowPlacement.Fullscreen,
            onAbout = { aboutOpen = true },
            onSettings = ::openSettings,
            onQuit = quit,
            onToggleFullScreen = { setFullScreen(state.placement != WindowPlacement.Fullscreen) },
            onMinimise = { state.isMinimized = true },
            onClose = ::close,
        )
        RaviloRoot()
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
