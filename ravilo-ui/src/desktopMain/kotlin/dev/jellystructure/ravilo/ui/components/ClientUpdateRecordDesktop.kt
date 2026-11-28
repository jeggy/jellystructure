package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.ui.DesktopApp

/** DEB-5851 (FR-3) — the desktop prefs file; a null value removes the key. */
actual object ClientUpdateRecord {
    actual fun read(key: String): String? = DesktopApp.prefs.get("update.$key")
    actual fun write(key: String, value: String?) = DesktopApp.prefs.put("update.$key", value)
}
