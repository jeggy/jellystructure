@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.screens

// Wasm-safe helpers: every js() call has to be a top-level function (same rule MultiTokenStoreWasm
// is written to).
private fun langGet(): String? = js("localStorage.getItem('ravilo.lang')")
private fun langSet(value: String): Unit = js("localStorage.setItem('ravilo.lang', value)")

/**
 * R279 — `ravilo.lang`, the same key both receivers use, because the web app and a receiver can be
 * the same browser profile on a set-top box and agreeing costs nothing.
 *
 * Private browsing and blocked site data both make these throw; a language that fails to persist is
 * not worth failing a render over, so both sides swallow it and the ladder falls through to English.
 */
actual object DeviceLanguageStore : dev.jellystructure.ravilo.i18n.LastLanguageStore {
    actual override fun read(): String? = runCatching { langGet() }.getOrNull()
    actual override fun write(code: String) { runCatching { langSet(code) } }
}
