@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.components

// Wasm-safe helpers: every js() call has to be a top-level function (the MultiTokenStoreWasm rule).
private fun lsGet(key: String): String? = js("localStorage.getItem(key)")
private fun lsSet(key: String, value: String): Unit = js("localStorage.setItem(key, value)")

/** R265 — localStorage; private browsing makes it throw, and a sheet that forgets is not worth a crash. */
actual object ScreensSheetPrefs {
    actual fun tier2Open(): Boolean = runCatching { lsGet("ravilo.screens.tier2") == "1" }.getOrDefault(false)
    actual fun setTier2Open(open: Boolean) { runCatching { lsSet("ravilo.screens.tier2", if (open) "1" else "0") } }
    actual fun lastDevice(): String? = runCatching { lsGet("ravilo.screens.last") }.getOrNull()
    actual fun setLastDevice(id: String) { runCatching { lsSet("ravilo.screens.last", id) } }
}
