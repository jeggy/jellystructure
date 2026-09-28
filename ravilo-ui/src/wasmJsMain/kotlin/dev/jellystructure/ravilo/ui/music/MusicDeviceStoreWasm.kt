@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.music

private fun musicGet(key: String): String? = js("localStorage.getItem('ravilo.music.' + key)")
private fun musicSet(key: String, value: String): Unit = js("localStorage.setItem('ravilo.music.' + key, value)")
private fun musicRemove(key: String): Unit = js("localStorage.removeItem('ravilo.music.' + key)")

/** `ravilo.music.*` in localStorage; private browsing makes these throw, and a lost preference is not worth a failed render. */
actual object MusicDeviceStore {
    actual fun get(key: String): String? = runCatching { musicGet(key) }.getOrNull()
    actual fun put(key: String, value: String?) { runCatching { if (value == null) musicRemove(key) else musicSet(key, value) } }
}
