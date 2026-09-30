package dev.jellystructure.ravilo.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.input.key.KeyShortcut
import dev.jellystructure.ravilo.ui.desktop.DesktopPaths
import dev.jellystructure.ravilo.ui.i18n.t
import dev.jellystructure.ravilo.ui.components.AppCommand
import dev.jellystructure.ravilo.ui.components.AppCommands

/**
 * FR-R328-5 — the menu bar, macOS's own and nothing custom:
 *
 * - **Ravilo:** About · Settings… (⌘,) · Hide (⌘H) · Quit (⌘Q) — drawn by macOS itself ([MacAppHooks]); on Linux,
 *   where nothing draws them, the same three items are a menu of our own.
 * - **View:** Enter Full Screen (⌃⌘F)
 * - **Window:** Minimise (⌘M) · Close (⌘W)
 */
@Composable
internal fun FrameWindowScope.RaviloMenuBar(
    lang: String,
    fullScreen: Boolean,
    onAbout: () -> Unit,
    onSettings: () -> Unit,
    onQuit: () -> Unit,
    onToggleFullScreen: () -> Unit,
    onMinimise: () -> Unit,
    onClose: () -> Unit,
) {
    val mac = DesktopPaths.isMac
    fun cmd(key: Key) = if (mac) KeyShortcut(key, meta = true) else KeyShortcut(key, ctrl = true)
    MenuBar {
        if (!MacAppHooks.systemAppMenu) {
            Menu("Ravilo") {
                Item(t("mac.menu_about", lang), onClick = onAbout)
                Item(t("mac.menu_settings", lang), shortcut = KeyShortcut(Key.Comma, ctrl = true), onClick = onSettings)
                Separator()
                Item(t("mac.menu_quit", lang), shortcut = KeyShortcut(Key.Q, ctrl = true), onClick = onQuit)
            }
        }
        Menu(t("mac.menu_view", lang)) {
            // R337 (FR-R337-10) — the modes, the sidebar, the queue and the lyrics.
            Item(t("mode.video", lang), shortcut = cmd(Key.One), onClick = { AppCommands.send(AppCommand.MODE_VIDEO) })
            Item(t("mode.music", lang), shortcut = cmd(Key.Two), onClick = { AppCommands.send(AppCommand.MODE_MUSIC) })
            Separator()
            Item(t("desk.hide_sidebar", lang), shortcut = KeyShortcut(Key.S, ctrl = true, meta = true), onClick = { AppCommands.send(AppCommand.TOGGLE_SIDEBAR) })
            Item(t("desk.show_queue", lang), shortcut = KeyShortcut(Key.U, alt = true, meta = true), onClick = { AppCommands.send(AppCommand.TOGGLE_QUEUE) })
            Item(t("desk.show_lyrics", lang), shortcut = KeyShortcut(Key.L, alt = true, meta = true), onClick = { AppCommands.send(AppCommand.SHOW_LYRICS) })
            Item(t("desk.shortcuts", lang), onClick = { AppCommands.send(AppCommand.SHORTCUTS) })
            Separator()
            Item(
                t(if (fullScreen) "mac.menu_exit_full_screen" else "mac.menu_enter_full_screen", lang),
                shortcut = if (mac) KeyShortcut(Key.F, ctrl = true, meta = true) else KeyShortcut(Key.F11),
                onClick = onToggleFullScreen,
            )
        }
        Menu(t("mac.menu_window", lang)) {
            Item(t("mac.menu_minimise", lang), shortcut = cmd(Key.M), onClick = onMinimise)
            Item(t("mac.menu_close", lang), shortcut = cmd(Key.W), onClick = onClose)
        }
    }
}
