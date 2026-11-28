package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.ui.RaviloAppContext

/** DEB-5851 (FR-3) — its own SharedPreferences file, so signing out (which clears `ravilo_sessions`) keeps it. */
actual object ClientUpdateRecord {
    private val prefs get() = RaviloAppContext.get().getSharedPreferences("ravilo_client_update", android.content.Context.MODE_PRIVATE)
    actual fun read(key: String): String? = runCatching { prefs.getString(key, null) }.getOrNull()
    actual fun write(key: String, value: String?) {
        runCatching { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
    }
}
