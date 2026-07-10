@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

// R63: configure Coil for web — memory cache + crossfade (no disk cache on wasm/browser).
// R98: 64 → 128 MB. The browser has no disk-cache tier, so the memory cache is the only thing
// preventing a re-fetch on scroll-back; 64 MB thrashed on a 307-item library. Paired with R96
// (LANDSCAPE backdrops now ~640px, ~9× smaller), 128 MB holds several full viewports comfortably.
// Bug fix: no SvgDecoder was registered here (Android's RaviloAppContext.kt has always had one), so
// SVG channel logos silently failed to decode and never rendered in the browser.
private val imageLoaderInit = run {
    SingletonImageLoader.setSafe { ctx ->
        ImageLoader.Builder(ctx)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(128L * 1024 * 1024).build() }
            .crossfade(true)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
}

actual fun createTvApiClient(baseUrl: String, deviceTokenProvider: () -> String?): TvApiClient {
    imageLoaderInit // ensure loader is configured before any image request
    val httpClient = HttpClient(Js) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(WebSockets) // live config push (R33)
        // Bug fix (Android originally): no HttpTimeout plugin meant no client-side bound at all on a
        // stalled request; a genuinely unreachable/slow server surfaced as an apparently endless
        // loading shimmer instead of the retry/error UI within a reasonable time. Matches the Android
        // actual's bound.
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000L
            requestTimeoutMillis = 10_000L
            socketTimeoutMillis = 10_000L
        }
    }
    return TvApiClient(httpClient, baseUrl, deviceTokenProvider)
}

private fun jsOrigin(): String = js("window.location.origin")
private fun jsGetToken(): String? = js("localStorage.getItem('ravilo_token')")
private fun jsSetToken(token: String): Unit = js("localStorage.setItem('ravilo_token', token)")
private fun jsClearToken(): Unit = js("localStorage.removeItem('ravilo_token')")
private fun jsGetBaseUrl(): String? = js("localStorage.getItem('ravilo_base_url')")
private fun jsSetBaseUrl(url: String): Unit = js("localStorage.setItem('ravilo_base_url', url)")
private fun jsClearBaseUrl(): Unit = js("localStorage.removeItem('ravilo_base_url')")

// A persisted server override (set via "Change server") wins over the page origin. When the app is
// served by the backend itself, no override is stored and we fall back to the origin. Persisting is
// required because the session token is also persisted — without it a reload would point a cached
// session at the page origin (e.g. a dev server) instead of the paired backend.
actual fun raviloBaseUrl(): String = jsGetBaseUrl()?.takeIf { it.isNotBlank() } ?: jsOrigin()

actual fun saveBaseUrl(url: String) {
    val trimmed = url.trim().trimEnd('/')
    if (trimmed.isEmpty()) jsClearBaseUrl() else jsSetBaseUrl(trimmed)
}

actual object TokenStore {
    actual fun get(): String? = jsGetToken()
    actual fun set(token: String) = jsSetToken(token)
    actual fun clear() = jsClearToken()
}

private fun jsGetDeviceId(): String? = js("localStorage.getItem('ravilo_device_id')")
private fun jsSetDeviceId(id: String): Unit = js("localStorage.setItem('ravilo_device_id', id)")

actual object DeviceIdStore {
    actual fun get(): String {
        jsGetDeviceId()?.takeIf { it.isNotBlank() }?.let { return it }
        val id = randomDeviceId()
        jsSetDeviceId(id)
        return id
    }
}

actual fun deviceDisplayName(): String = "Ravilo Web"

// ─── R80: Browser history / URL navigation ────────────────────────────────────

private fun jsGetHash(): String = js("window.location.hash")
private fun jsPushState(hash: String): Unit = js("window.history.pushState(null,'',hash)")
private fun jsHistoryReplace(hash: String): Unit = js("window.history.replaceState(null,'',hash)")

// history.pushState does NOT fire hashchange or popstate — no spurious pop() on every click.
actual fun pushRoute(route: String) {
    jsPushState("#$route")
}

actual fun replaceRoute(route: String) {
    jsHistoryReplace("#$route")
}

// popstate fires only on browser Back/Forward (history traversal), not on pushState/replaceState.
actual fun installHashListener(onRoute: (String) -> Unit): () -> Unit {
    jsInstallPopStateListener { onRoute(jsGetHash().removePrefix("#")) }
    return {}
}

private fun jsInstallPopStateListener(callback: () -> Unit): Unit =
    js("window.addEventListener('popstate', function(){ callback() })")

// Browser back/forward already goes through installHashListener/popstate above — no separate system
// back gesture to bridge here.
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {}
