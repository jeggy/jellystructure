package dev.jellystructure.ravilo.ui

import androidx.compose.runtime.Composable
import dev.jellystructure.shared.tv.TvApiClient

/** Platform-specific: creates the HttpClient configured for the given target. */
expect fun createTvApiClient(baseUrl: String, deviceTokenProvider: () -> String?): TvApiClient

/** Platform-provides the jellystructure server base URL (e.g. the current browser origin or a user-set pref). */
expect fun raviloBaseUrl(): String

/** Token storage — platform-specific persist across restarts. */
expect object TokenStore {
    fun get(): String?
    fun set(token: String)
    fun clear()
}

@Composable
fun RaviloRoot() {
    val baseUrl = raviloBaseUrl()
    // Active token comes from MultiTokenStore so it switches per profile without recreating the client
    val apiClient = createTvApiClient(baseUrl) {
        dev.jellystructure.ravilo.ui.screens.MultiTokenStore.getActive()?.deviceToken
            ?: TokenStore.get()
    }
    RaviloApp(apiClient = apiClient)
}
