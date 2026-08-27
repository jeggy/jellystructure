package dev.jellystructure.ravilo.ui

import android.app.Activity
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import dev.jellystructure.shared.tv.TvApiClient

private val httpTimeoutConfig: HttpTimeoutConfig.() -> Unit = {
    // Bug fix: with no HttpTimeout plugin, Ktor enforces no client-side timeout at all — a
    // genuinely unreachable/slow server (phone off the home network, a TV whose WiFi hasn't
    // reconnected after sleep) fell through to the OS's raw TCP connect timeout, often 60-120+s.
    // HomeStore retries failed loads 4x, so this could compound into minutes of an apparently
    // "stuck forever" shimmer skeleton with no feedback. Bound every REST call so a real failure
    // surfaces (with retry) in well under 30s.
    connectTimeoutMillis = 5_000L
    requestTimeoutMillis = 10_000L
    socketTimeoutMillis = 10_000L
}

actual fun createTvApiClient(baseUrl: String, deviceTokenProvider: () -> String?): TvApiClient {
    // R210 — REST calls (everything except the WebSocket) go through the Android engine
    // (`HttpURLConnection`-based), not CIO: CIO's connect step was found to intermittently
    // fail/hang on real Android devices even when the same network path is instantly reachable via
    // a raw shell request from the same device at the same moment
    // (see bug-ravilo-tv-cio-connect-timeout). Combined with HomeStore's 10-attempt exponential
    // backoff, that made a client-side connect bug look exactly like "the app just hangs on
    // launch." The Android engine has no WebSocket support, which is fine here — it's never asked
    // to open one.
    val restClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(HttpTimeout, httpTimeoutConfig)
    }
    // CIO engine: kept solely for the WebSocket client used for live config push (R33); the
    // Android engine above has no WebSocket support at all.
    val wsClient = HttpClient(CIO) {
        install(WebSockets)
        install(HttpTimeout, httpTimeoutConfig)
    }
    return TvApiClient(restClient, baseUrl, deviceTokenProvider, wsClient = wsClient)
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
