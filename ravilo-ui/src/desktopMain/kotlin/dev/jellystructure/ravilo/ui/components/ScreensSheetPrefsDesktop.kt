package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.ui.DesktopApp

actual object ScreensSheetPrefs {
    actual fun tier2Open(): Boolean = DesktopApp.prefs.get("screens.tier2") == "1"
    actual fun setTier2Open(open: Boolean) = DesktopApp.prefs.put("screens.tier2", if (open) "1" else "0")
    actual fun lastDevice(): String? = DesktopApp.prefs.get("screens.last")
    actual fun setLastDevice(id: String) = DesktopApp.prefs.put("screens.last", id)
}
