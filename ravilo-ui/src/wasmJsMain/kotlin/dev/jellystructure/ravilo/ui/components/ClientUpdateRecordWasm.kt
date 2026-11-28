@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.components

private fun lsGetUpdate(key: String): String? = js("localStorage.getItem(key)")
private fun lsSetUpdate(key: String, value: String): Unit = js("localStorage.setItem(key, value)")
private fun lsRemoveUpdate(key: String): Unit = js("localStorage.removeItem(key)")

/** DEB-5851 — unused on the web (FR-8: the service worker is the only signal); present because the seam is common. */
actual object ClientUpdateRecord {
    actual fun read(key: String): String? = runCatching { lsGetUpdate("ravilo.update.$key") }.getOrNull()
    actual fun write(key: String, value: String?) { runCatching { if (value == null) lsRemoveUpdate("ravilo.update.$key") else lsSetUpdate("ravilo.update.$key", value) } }
}
