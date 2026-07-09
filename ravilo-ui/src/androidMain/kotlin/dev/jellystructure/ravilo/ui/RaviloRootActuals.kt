package dev.jellystructure.ravilo.ui

import android.content.SharedPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import dev.jellystructure.shared.tv.TvApiClient

actual fun createTvApiClient(baseUrl: String, deviceTokenProvider: () -> String?): TvApiClient {
    // CIO engine: supports the WebSocket client used for live config push (R33); Android engine does not.
    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(WebSockets)
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

actual object DeviceIdStore {
    actual fun get(): String {
        prefs().getString("device_id", null)?.let { return it }
        val id = randomDeviceId()
        prefs().edit().putString("device_id", id).apply()
        return id
    }
}

actual fun deviceDisplayName(): String = android.os.Build.MODEL ?: "Ravilo TV"

// R80: no-ops on Android — navigation is handled by Key.Back key events.
actual fun pushRoute(route: String) {}
actual fun replaceRoute(route: String) {}
actual fun installHashListener(onRoute: (String) -> Unit): () -> Unit = {}
