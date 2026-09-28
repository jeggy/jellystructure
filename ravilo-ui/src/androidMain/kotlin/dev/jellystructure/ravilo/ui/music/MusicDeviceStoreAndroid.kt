package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.RaviloAppContext

/** Its own SharedPreferences file (`ravilo_music`), like [dev.jellystructure.ravilo.ui.screens.DeviceLanguageStore]. */
actual object MusicDeviceStore {
    private val prefs get() = RaviloAppContext.get().getSharedPreferences("ravilo_music", android.content.Context.MODE_PRIVATE)
    actual fun get(key: String): String? = runCatching { prefs.getString(key, null) }.getOrNull()
    actual fun put(key: String, value: String?) {
        runCatching { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
    }
}
