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

    /** DEB-4396 (FR-2) — Coil's own client: [okHttp]'s pool, its own dispatcher and call timeout. */
    private val artworkHttp: OkHttpClient by lazy { artworkClient(okHttp) }

    /** DEB-4396 (FR-3) — drop the shared pool's idle sockets, off the main thread (closing one may touch the TLS layer). */
    fun evictIdleConnections() {
        Thread { runCatching { okHttp.connectionPool.evictAll() } }.apply { isDaemon = true; name = "ravilo-pool-evict" }.start()
    }

    fun init(context: Context) {
        ctx = context.applicationContext
        // DEB-4396 (FR-1) — a network coming back retries every artwork tile that failed while it was gone.
        runCatching {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { dev.jellystructure.ravilo.ui.seams.ArtworkRetrySignal.nudge() }
            })
        }
        // DEB-2185 (FR-12.2) — a television reports and sets its media stream as the session's volume; a phone never does.
        runCatching { dev.jellystructure.ravilo.ui.seams.installTvStreamVolume(context.applicationContext, isTelevision) }
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
                        // DEB-4396 (FR-4.1) — 150 MB in cacheDir, deliberately: the OS reclaims cacheDir when storage runs
                        // low, so Ravilo can never fill a TV; and 150 MB holds several thousand 320 px posters.
                        .maxSizeBytes(150L * 1024 * 1024)
                        .build()
                }
                .crossfade(true)
                .components {
                    // R316; DEB-4396 — the artwork lane (FR-2), and a disk hit never revalidates (FR-4.2).
                    add(OkHttpNetworkFetcherFactory(callFactory = { artworkHttp }, cacheStrategy = { NeverRevalidateCacheStrategy }))
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
