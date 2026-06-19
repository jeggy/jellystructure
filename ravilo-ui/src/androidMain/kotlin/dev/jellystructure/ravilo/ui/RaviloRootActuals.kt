package dev.jellystructure.ravilo.ui

import android.content.SharedPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import dev.jellystructure.shared.tv.TvApiClient

actual fun createTvApiClient(baseUrl: String, deviceTokenProvider: () -> String?): TvApiClient {
    val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }
    return TvApiClient(httpClient, baseUrl, deviceTokenProvider)
}

private fun prefs() = RaviloAppContext.get()
    .getSharedPreferences("ravilo_prefs", android.content.Context.MODE_PRIVATE)

actual fun raviloBaseUrl(): String = prefs().getString("base_url", "") ?: ""

actual fun saveBaseUrl(url: String) {
    prefs().edit().putString("base_url", url).apply()
}

actual object TokenStore {
    actual fun get(): String? = prefs().getString("device_token", null)
    actual fun set(token: String) { prefs().edit().putString("device_token", token).apply() }
    actual fun clear() { prefs().edit().remove("device_token").apply() }
}
