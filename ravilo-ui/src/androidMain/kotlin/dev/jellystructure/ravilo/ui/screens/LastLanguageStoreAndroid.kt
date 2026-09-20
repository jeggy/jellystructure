package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.RaviloAppContext

/**
 * R279 — its own SharedPreferences file, not `ravilo_sessions`: signing out clears that one, and the
 * language must survive it (see [DeviceLanguageStore]).
 */
actual object DeviceLanguageStore : dev.jellystructure.ravilo.i18n.LastLanguageStore {
    private const val PREFS = "ravilo_locale"
    private const val KEY = "last_language"

    private val prefs get() = RaviloAppContext.get()
        .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    actual override fun read(): String? = runCatching { prefs.getString(KEY, null) }.getOrNull()

    actual override fun write(code: String) {
        runCatching { prefs.edit().putString(KEY, code).apply() }
    }
}
