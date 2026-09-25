package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.ui.RaviloAppContext

/** R265 — its own SharedPreferences file, so signing out (which clears `ravilo_sessions`) keeps it. */
actual object ScreensSheetPrefs {
    private const val PREFS = "ravilo_screens_sheet"
    private val prefs get() = RaviloAppContext.get().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    actual fun tier2Open(): Boolean = runCatching { prefs.getBoolean("tier2_open", false) }.getOrDefault(false)
    actual fun setTier2Open(open: Boolean) { runCatching { prefs.edit().putBoolean("tier2_open", open).apply() } }
    actual fun lastDevice(): String? = runCatching { prefs.getString("last_device", null) }.getOrNull()
    actual fun setLastDevice(id: String) { runCatching { prefs.edit().putString("last_device", id).apply() } }
}
