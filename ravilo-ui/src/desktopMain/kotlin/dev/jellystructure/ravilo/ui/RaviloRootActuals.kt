package dev.jellystructure.ravilo.ui

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.serviceLoaderEnabled
import coil3.svg.SvgDecoder
import dev.jellystructure.ravilo.ui.desktop.DesktopPaths
import dev.jellystructure.ravilo.ui.desktop.MacNative
import dev.jellystructure.ravilo.ui.desktop.PrefsFile
import dev.jellystructure.ravilo.ui.desktop.SecretStore
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * R328 — the desktop's app plumbing. One OkHttp client behind every REST call and every image (R316's rule on
 * Android, for the same reason: one connection pool), CIO for the events socket (R210), and `X-Ravilo-Platform`
 * saying `mac` (FR-R328-6) — or `linux` for the development build, which is honest and changes nothing on the wire.
 */
internal object DesktopApp {
    val okHttp: OkHttpClient by lazy { OkHttpClient() }

    val platform: String = if (DesktopPaths.isMac) "mac" else "linux"

    val prefs: PrefsFile by lazy { PrefsFile("ravilo_prefs") }

    val imageLoaderInit: Unit by lazy {
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .serviceLoaderEnabled(false)   // R316 (FR-R316-2) — the fetcher is named, never discovered
                .memoryCache { MemoryCache.Builder().maxSizePercent(ctx, 0.20).build() }
                .diskCache {
                    DiskCache.Builder()
                        .directory(DesktopPaths.cacheDir.resolve("images").toOkioPath())
                        .maxSizeBytes(300L * 1024 * 1024)
                        .build()
                }
                .crossfade(true)
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = { okHttp }))
                    add(SvgDecoder.Factory())
                }
                .build()
        }
    }
}

private val httpTimeoutConfig: HttpTimeoutConfig.() -> Unit = {
    connectTimeoutMillis = 5_000L
    requestTimeoutMillis = 10_000L
    socketTimeoutMillis = 10_000L
}

actual fun createTvApiClient(baseUrl: String, deviceTokenProvider: () -> String?): TvApiClient {
    DesktopApp.imageLoaderInit
    val restClient = HttpClient(OkHttp) {
        engine { preconfigured = DesktopApp.okHttp }
        install(ContentNegotiation) { json(dev.jellystructure.shared.tv.RaviloWireJson) }
        install(HttpTimeout, httpTimeoutConfig)
    }
    val wsClient = HttpClient(CIO) {
        install(WebSockets)
        install(HttpTimeout, httpTimeoutConfig)
    }
    return TvApiClient(restClient, baseUrl, deviceTokenProvider, wsClient = wsClient, platform = DesktopApp.platform)
}

actual fun raviloBaseUrl(): String = DesktopApp.prefs.get("base_url").orEmpty()

actual fun saveBaseUrl(url: String) = DesktopApp.prefs.put("base_url", url.ifBlank { null })

actual object TokenStore {
    private const val ACCOUNT = "device_token"
    actual fun get(): String? = SecretStore.get(ACCOUNT)
    actual fun set(token: String) = SecretStore.set(ACCOUNT, token)
    actual fun clear() = SecretStore.delete(ACCOUNT)
}

actual object DeviceIdStore {
    actual fun get(): String = synchronized(this) {
        DesktopApp.prefs.get("device_id") ?: randomDeviceId().also { DesktopApp.prefs.put("device_id", it) }
    }
}

/** FR-R328-3 — the Mac's *Computer Name* (System Settings → General → Sharing); the host name elsewhere. */
actual fun deviceDisplayName(): String = computerName

private val computerName: String by lazy {
    val native = MacNative.lib?.let { MacNative.take(it.ravilo_computer_name()) }?.trim()?.ifBlank { null }
    val scutil = if (native == null && DesktopPaths.isMac) scutilComputerName() else null
    // getLocalHost() can wait on DNS for seconds on a Mac, so it is only asked when nothing better answered.
    native ?: scutil
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.substringBefore('.')?.trim()?.ifBlank { null }
        ?: (if (DesktopPaths.isMac) "Mac" else "Ravilo")
}

private fun scutilComputerName(): String? = runCatching {
    val p = ProcessBuilder("scutil", "--get", "ComputerName").redirectErrorStream(true).start()
    if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroy(); return null }
    p.inputStream.bufferedReader().readText().trim().takeIf { p.exitValue() == 0 && it.isNotEmpty() }
}.getOrNull()

actual fun pushRoute(route: String) {}
actual fun replaceRoute(route: String) {}
actual fun installHashListener(onRoute: (String) -> Unit): () -> Unit = {}

/**
 * R328 (dev review 3) — nothing to bridge: a desktop has no system Back gesture that bypasses Compose, and every
 * `Key.Back` handler in common code also answers `Key.Escape` (checked 2026-09-29), so **Esc** is Back already.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {}

/**
 * R328 — Back at Home does nothing on a desktop. A Mac window is closed with ⌘W, ⌘Q or its red button (D7), never
 * by Esc: a viewer pressing Esc a few times to get back to Home must not lose the window.
 */
@Composable
actual fun rememberExitAction(): () -> Unit = {}
