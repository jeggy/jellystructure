@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

// R63: configure Coil for web — memory cache + crossfade (no disk cache on wasm/browser).
private val imageLoaderInit = run {
    SingletonImageLoader.setSafe { ctx ->
        ImageLoader.Builder(ctx)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(64L * 1024 * 1024).build() }
            .crossfade(true)
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
