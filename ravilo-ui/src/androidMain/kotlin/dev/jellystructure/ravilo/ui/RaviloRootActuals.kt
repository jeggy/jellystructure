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

actual fun raviloBaseUrl(): String {
    val prefs = RaviloAppContext.get()
        .getSharedPreferences("ravilo_prefs", android.content.Context.MODE_PRIVATE)
    return prefs.getString("base_url", "http://192.168.1.1:8097") ?: "http://192.168.1.1:8097"
}

actual object TokenStore {
    private val prefs: SharedPreferences
        get() = RaviloAppContext.get()
            .getSharedPreferences("ravilo_prefs", android.content.Context.MODE_PRIVATE)

    actual fun get(): String? = prefs.getString("device_token", null)
    actual fun set(token: String) { prefs.edit().putString("device_token", token).apply() }
    actual fun clear() { prefs.edit().remove("device_token").apply() }
}
