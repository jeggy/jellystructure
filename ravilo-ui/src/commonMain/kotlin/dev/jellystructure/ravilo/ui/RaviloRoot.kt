package dev.jellystructure.ravilo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.jellystructure.ravilo.ui.screens.MultiTokenStore
import dev.jellystructure.ravilo.ui.screens.ServerSetupScreen
import dev.jellystructure.shared.tv.TvApiClient

/** Platform-specific: creates the HttpClient configured for the given target. */
expect fun createTvApiClient(baseUrl: String, deviceTokenProvider: () -> String?): TvApiClient

/** Platform-provides the jellystructure server base URL. Returns empty string if not yet configured. */
expect fun raviloBaseUrl(): String

/** Persists a user-entered server URL. */
expect fun saveBaseUrl(url: String)

/** Token storage — platform-specific persist across restarts. */
expect object TokenStore {
    fun get(): String?
    fun set(token: String)
    fun clear()
}

@Composable
fun RaviloRoot() {
    var baseUrl by remember { mutableStateOf(raviloBaseUrl()) }

    if (baseUrl.isEmpty()) {
        ServerSetupScreen(onUrlSaved = { url ->
            saveBaseUrl(url)
            baseUrl = url
        })
        return
    }

    val apiClient = remember(baseUrl) {
        createTvApiClient(baseUrl) {
            MultiTokenStore.getActive()?.deviceToken ?: TokenStore.get()
        }
    }
    RaviloApp(
        apiClient = apiClient,
        onChangeServer = {
            saveBaseUrl("")
            baseUrl = ""
        },
    )
}
