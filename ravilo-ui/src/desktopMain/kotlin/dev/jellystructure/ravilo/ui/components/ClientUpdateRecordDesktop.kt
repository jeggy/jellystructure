package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.ui.DesktopApp

/** DEB-5851 (FR-3) — the desktop prefs file; a null value removes the key. */
actual object ClientUpdateRecord {
    actual fun read(): String? = DesktopApp.prefs.get("update.first_seen")
    actual fun write(value: String?) = DesktopApp.prefs.put("update.first_seen", value)
}
