@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.jellystructure.ravilo.ui.screens.SsoAttempt
import dev.jellystructure.ravilo.ui.screens.SsoListener
import dev.jellystructure.ravilo.ui.screens.SsoPlatform

// Wasm-safe helpers: every js() call has to be a top-level function (same rule MultiTokenStoreWasm is written to).
private fun ssoStorageGet(): String? = js("sessionStorage.getItem('ravilo.sso.attempt')")
private fun ssoStorageSet(value: String): Unit = js("sessionStorage.setItem('ravilo.sso.attempt', value)")
private fun ssoStorageClear(): Unit = js("sessionStorage.removeItem('ravilo.sso.attempt')")
private fun ssoNavigate(url: String): Unit = js("window.location.assign(url)")

@Composable
actual fun rememberSsoPlatform(): SsoPlatform? = remember { WebSsoPlatform }

/**
 * This tab goes to the identity provider and the server's success page redirects it back to Ravilo Web's own address with `?signin=…&h=…`. The page
 * is loaded afresh by then, so the attempt's secret waits in `sessionStorage` (FR-11): per tab, gone when the tab is, and never `localStorage`.
 */
internal object WebSsoPlatform : SsoPlatform {
    private val web = object : SsoListener { override val returnTo = "web" }

    override fun listen(closeTabText: String): SsoListener = web

    override fun open(authorizeUrl: String) = ssoNavigate(authorizeUrl)

    override fun persist(attempt: SsoAttempt?) {
        runCatching { if (attempt == null) ssoStorageClear() else ssoStorageSet(attempt.encode()) }
    }

    override fun recall(): SsoAttempt? = SsoAttempt.decode(runCatching { ssoStorageGet() }.getOrNull())
}
