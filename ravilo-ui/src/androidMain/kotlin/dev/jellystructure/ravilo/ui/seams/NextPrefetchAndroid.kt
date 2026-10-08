package dev.jellystructure.ravilo.ui.seams

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.shared.tv.PreparedStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * R381 — see the expect's doc. One small cache of its own (LRU, [CACHE_BYTES]), written only here; the player reads it
 * only for the prepared stream ([cacheKeyFor]), so a stream that was not prefetched never touches it.
 */
@OptIn(UnstableApi::class)
actual object NextPrefetch {
    private const val TAG = "R381"
    private const val HEAD_BYTES = 16L shl 20
    private const val TAIL_BYTES = 4L shl 20
    private const val CACHE_BYTES = 64L shl 20
    /** Dev review item 9 — a prefetch unused for five minutes is gone. */
    private const val MAX_AGE_MS = 5 * 60_000L

    private class Entry(val url: String, val key: String, val at: Long)

    @Volatile private var entry: Entry? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cacheLazy = lazy {
        val ctx = RaviloAppContext.get()
        SimpleCache(File(ctx.cacheDir, "r381-next"), LeastRecentlyUsedCacheEvictor(CACHE_BYTES), StandaloneDatabaseProvider(ctx))
    }
    internal val cache: SimpleCache get() = cacheLazy.value

    private fun now() = android.os.SystemClock.elapsedRealtime()

    actual fun prefetch(stream: PreparedStream) {
        val url = stream.url ?: return
        if (!stream.directPlay || stream.startPositionMs > 0) return   // only a direct play from its start
        if (entry?.url == url) return
        discard()
        val key = "r381:${stream.itemId}"
        val e = Entry(url, key, now())
        entry = e
        job = scope.launch {
            val t0 = now()
            runCatching {
                val upstream = DefaultDataSource.Factory(RaviloAppContext.get())
                val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).createDataSource()
                CacheWriter(source, DataSpec.Builder().setUri(url).setKey(key).setPosition(0).setLength(HEAD_BYTES).build(), null, null).cache()
                val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                if (length > HEAD_BYTES + TAIL_BYTES) {
                    val tail = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).createDataSource()
                    CacheWriter(tail, DataSpec.Builder().setUri(url).setKey(key).setPosition(length - TAIL_BYTES).setLength(TAIL_BYTES).build(), null, null).cache()
                }
                Log.i(TAG, "prefetched item=${stream.itemId} head+tail in ${now() - t0} ms (length $length)")
            }.onFailure { Log.i(TAG, "prefetch of item=${stream.itemId} not made: ${it.message}") }
        }
    }

    /** The cache key to read [url] from, when it is the prefetched stream and still fresh; null otherwise. */
    fun cacheKeyFor(url: String): String? {
        val e = entry ?: return null
        if (e.url != url) return null
        if (now() - e.at > MAX_AGE_MS) { discard(); return null }
        return e.key
    }

    /** Another item is loading: a prefetch made for a different stream is dropped. */
    fun keepOnlyFor(url: String) {
        val e = entry ?: return
        if (e.url != url) discard()
    }

    actual fun discard() {
        job?.cancel()
        job = null
        val e = entry ?: return
        entry = null
        if (cacheLazy.isInitialized()) scope.launch { runCatching { cache.removeResource(e.key) } }
    }
}
