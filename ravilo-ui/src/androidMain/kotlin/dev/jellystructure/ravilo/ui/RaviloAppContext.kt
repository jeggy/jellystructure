package dev.jellystructure.ravilo.ui

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder

object RaviloAppContext {
    private var ctx: Context? = null
    fun init(context: Context) {
        ctx = context.applicationContext
        // Register SVG decoder so Coil can render SVG channel logos (R63).
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx).components { add(SvgDecoder.Factory()) }.build()
        }
    }
    fun get(): Context = checkNotNull(ctx) { "RaviloAppContext not initialized — call init() from Application.onCreate()" }
}
