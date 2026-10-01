package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.ravilo.ui.desktop.DesktopDock

/** R342 — the Mac's running Dock icon follows the mode ([DesktopDock]); Linux does nothing there. */
actual fun reportListeningMode(music: Boolean) = DesktopDock.report(music)
