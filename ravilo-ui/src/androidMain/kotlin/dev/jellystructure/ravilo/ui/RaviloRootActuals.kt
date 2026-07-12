package dev.jellystructure.ravilo.ui

import android.app.Activity
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
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
        // Bug fix: with no HttpTimeout plugin, Ktor enforces no client-side timeout at all — a
        // genuinely unreachable/slow server (phone off the home network, a TV whose WiFi hasn't
        // reconnected after sleep) fell through to the OS's raw TCP connect timeout, often 60-120+s.
        // HomeStore retries failed loads 4x, so this could compound into minutes of an apparently
        // "stuck forever" shimmer skeleton with no feedback. Bound every REST call so a real failure
        // surfaces (with retry) in well under 30s.
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000L
            requestTimeoutMillis = 10_000L
            socketTimeoutMillis = 10_000L
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

// Bug fix: bridges Android's system back gesture/button (which bypasses Compose's Key.Back KeyEvent
// path under gesture navigation) into the app's existing pop() logic. See RaviloRoot.kt's doc comment.
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

// See RaviloRoot.kt's doc comment. finishAndRemoveTask() (not finish()) also drops the Recents/
// overview entry — the app is gone, not just backgrounded, matching what the user asked for.
@Composable
actual fun rememberExitAction(): () -> Unit {
    val activity = LocalContext.current as? Activity
    return { activity?.finishAndRemoveTask() }
}
