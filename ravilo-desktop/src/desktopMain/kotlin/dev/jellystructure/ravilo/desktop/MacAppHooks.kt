package dev.jellystructure.ravilo.desktop

import java.awt.Desktop
import java.awt.desktop.AppHiddenListener
import java.awt.desktop.AppReopenedListener
import javax.swing.SwingUtilities

/**
 * FR-R328-5 / FR-R328-9 (dev review 6) — macOS's own app menu: *About Ravilo*, *Settings…* (⌘,) and *Quit* (⌘Q) are
 * drawn and translated by macOS; the JDK hands their clicks to these handlers. Clicking the Dock icon while the
 * window is hidden brings it back where it was. On a desktop without these actions (Linux) nothing is installed and
 * the in-window menu carries About, Settings and Quit instead.
 */
internal object MacAppHooks {
    val systemAppMenu: Boolean by lazy {
        runCatching { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_ABOUT) }.getOrDefault(false)
    }

    fun install(onAbout: () -> Unit, onSettings: () -> Unit, onQuit: () -> Unit, onReopen: () -> Unit, onHidden: (Boolean) -> Unit = {}) {
        if (!Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        fun supported(action: Desktop.Action) = runCatching { desktop.isSupported(action) }.getOrDefault(false)
        if (supported(Desktop.Action.APP_ABOUT)) desktop.setAboutHandler { SwingUtilities.invokeLater(onAbout) }
        if (supported(Desktop.Action.APP_PREFERENCES)) desktop.setPreferencesHandler { SwingUtilities.invokeLater(onSettings) }
        if (supported(Desktop.Action.APP_QUIT_HANDLER)) desktop.setQuitHandler { _, response ->
            // Our own quit reports the stops first; the system's would end the process at once.
            response.cancelQuit()
            onQuit()
        }
        if (supported(Desktop.Action.APP_EVENT_REOPENED)) {
            desktop.addAppEventListener(AppReopenedListener { SwingUtilities.invokeLater(onReopen) })
        }
        // R383 (FR-R383-3) — ⌘H / `open -j` hides the app without a window event: Java still thinks the window is showing
        // and Compose goes on drawing at the display rate into a window nobody sees (120 frames/s measured, DEB-1466).
        if (supported(Desktop.Action.APP_EVENT_HIDDEN)) {
            desktop.addAppEventListener(object : AppHiddenListener {
                override fun appHidden(e: java.awt.desktop.AppHiddenEvent) { SwingUtilities.invokeLater { onHidden(true) } }
                override fun appUnhidden(e: java.awt.desktop.AppHiddenEvent) { SwingUtilities.invokeLater { onHidden(false) } }
            })
        }
    }
}
