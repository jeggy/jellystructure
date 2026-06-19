@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import dev.jellystructure.shared.tv.TvApiClient

actual fun createTvApiClient(baseUrl: String, deviceTokenProvider: () -> String?): TvApiClient {
    val httpClient = HttpClient(Js) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }
    return TvApiClient(httpClient, baseUrl, deviceTokenProvider)
}

private fun jsOrigin(): String = js("window.location.origin")
private fun jsGetToken(): String? = js("localStorage.getItem('ravilo_token')")
private fun jsSetToken(token: String): Unit = js("localStorage.setItem('ravilo_token', token)")
private fun jsClearToken(): Unit = js("localStorage.removeItem('ravilo_token')")

actual fun raviloBaseUrl(): String = jsOrigin()

actual fun saveBaseUrl(url: String) { /* web always uses window.location.origin */ }

actual object TokenStore {
    actual fun get(): String? = jsGetToken()
    actual fun set(token: String) = jsSetToken(token)
    actual fun clear() = jsClearToken()
}
