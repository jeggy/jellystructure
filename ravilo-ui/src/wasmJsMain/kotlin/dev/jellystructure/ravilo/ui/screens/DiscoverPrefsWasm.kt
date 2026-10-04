@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.screens

// Wasm-safe helpers: every js() call has to be a top-level function (the MultiTokenStoreWasm rule).
private fun discoverLsGet(key: String): String? = js("localStorage.getItem(key)")
private fun discoverLsSet(key: String, value: String): Unit = js("localStorage.setItem(key, value)")
private fun discoverLsRemove(key: String): Unit = js("localStorage.removeItem(key)")

/** R366 — localStorage; private browsing makes it throw, and a forgotten answer only means Coming Soon first. */
actual object DiscoverPrefs {
    private const val KEY = "ravilo.discover.upcomingEmpty"

    actual fun upcomingEmpty(): Boolean? = runCatching {
        when (discoverLsGet(KEY)) { "1" -> true; "0" -> false; else -> null }
    }.getOrNull()

    actual fun setUpcomingEmpty(empty: Boolean?) {
        runCatching {
            if (empty == null) discoverLsRemove(KEY) else discoverLsSet(KEY, if (empty) "1" else "0")
        }
    }
}
