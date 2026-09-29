package dev.jellystructure.ravilo.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.input.key.KeyShortcut
import dev.jellystructure.ravilo.ui.desktop.DesktopPaths
import dev.jellystructure.ravilo.ui.i18n.t

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
