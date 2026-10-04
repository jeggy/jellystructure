package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.DesktopApp

/** R366 — the desktop's own preferences file. */
actual object DiscoverPrefs {
    private const val KEY = "discover.upcoming_empty"

    actual fun upcomingEmpty(): Boolean? = runCatching {
        when (DesktopApp.prefs.get(KEY)) { "1" -> true; "0" -> false; else -> null }
    }.getOrNull()

    actual fun setUpcomingEmpty(empty: Boolean?) {
        runCatching { DesktopApp.prefs.put(KEY, empty?.let { if (it) "1" else "0" }) }
    }
}
