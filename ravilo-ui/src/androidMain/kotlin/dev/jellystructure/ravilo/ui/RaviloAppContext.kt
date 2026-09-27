package dev.jellystructure.ravilo.ui

import android.content.Context
import android.content.res.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.serviceLoaderEnabled
import coil3.svg.SvgDecoder
import okhttp3.OkHttpClient

object RaviloAppContext {
    private var ctx: Context? = null

    /**
     * R316 (FR-R316-1) — the ONE OkHttp client behind every image and every REST call on Android, so
     * both share a connection pool. It replaced Ktor's Android engine (`HttpURLConnection`, the
     * platform's internal OkHttp 2 fork): a fast scroll recycles grid cells, Coil cancels the image
     * that cell was reading on the main thread, and that engine's cancel drained the rest of the body
     * from the cancelling thread while the IO thread was still reading it. The platform's okio threw
     * *Unbalanced enter/exit* inside a cancellation handler, which rethrew into the Compose frame and
     * killed the app (reproduced on the Pixel 9 in 20 s). OkHttp cancels by closing the socket, from any
     * thread. The events socket stays on CIO (R210).
     */
    val okHttp: OkHttpClient by lazy { OkHttpClient() }

    fun init(context: Context) {
        ctx = context.applicationContext
        // R63: memory + disk cache, crossfade, SVG decoder.
        SingletonImageLoader.setSafe { c ->
            ImageLoader.Builder(c)
                // R316 (FR-R316-2) — the network fetcher is named, never discovered from the classpath.
                .serviceLoaderEnabled(false)
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(c, 0.20)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(c.cacheDir.resolve("ravilo_image_cache").toOkioPath())
                        .maxSizeBytes(150L * 1024 * 1024) // 150 MB
                        .build()
                }
                .crossfade(true)
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = { okHttp }))  // R316
                    add(SvgDecoder.Factory())
                }
                .build()
        }
    }
    fun get(): Context = checkNotNull(ctx) { "RaviloAppContext not initialized — call init() from Application.onCreate()" }

    /**
     * R192 — detects Android TV hardware at runtime (not a build flavor check), so the SAME shared
     * `androidMain` code (used by both `:ravilo-android` and `:ravilo-phone`) can tell which app it's
     * actually running inside. Used to gate the player's OS `MediaSession` to TV only: the TV's
     * playback is meant to be visible/controllable from a household member's phone, but a phone's own
     * playback must never be advertised to other devices the same way.
     */
    val isTelevision: Boolean
        get() = (get().resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
}
