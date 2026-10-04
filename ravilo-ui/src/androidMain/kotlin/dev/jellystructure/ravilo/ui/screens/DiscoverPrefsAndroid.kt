package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.RaviloAppContext

/** R366 — its own SharedPreferences file, so signing out (which clears `ravilo_sessions`) keeps it. */
actual object DiscoverPrefs {
    private const val PREFS = "ravilo_discover"
    private const val KEY = "upcoming_empty"
    private val prefs get() = RaviloAppContext.get().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    actual fun upcomingEmpty(): Boolean? = runCatching {
        if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else null
    }.getOrNull()

    actual fun setUpcomingEmpty(empty: Boolean?) {
        runCatching {
            val e = prefs.edit()
            if (empty == null) e.remove(KEY) else e.putBoolean(KEY, empty)
            e.apply()
        }
    }
}
