package dev.jellystructure.ravilo.ui

import android.content.Context
import android.content.res.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.svg.SvgDecoder

object RaviloAppContext {
    private var ctx: Context? = null
    fun init(context: Context) {
        ctx = context.applicationContext
        // R63: memory + disk cache, crossfade, SVG decoder.
        SingletonImageLoader.setSafe { c ->
            ImageLoader.Builder(c)
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
                .components { add(SvgDecoder.Factory()) }
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
