package dev.jellystructure.ravilo.ui.seams

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.shared.tv.HlsWarm
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * DEB-3171 — see the expect's doc. One small cache of its own (LRU, [CACHE_BYTES]) written only by [keep]; the player reads it
 * only while loading the stream [begin] named ([wrap]), and by the segment's URL, so any other stream never touches it.
 */
@OptIn(UnstableApi::class)
actual object WarmedSegments {
    private const val CACHE_BYTES = 64L shl 20
    private const val MAX_AGE_MS = 5 * 60_000L

    private class Entry(val masterUrl: String, val at: Long, val keys: MutableList<String> = mutableListOf())
    @Volatile private var entry: Entry? = null

    private val cacheLazy = lazy {
        val ctx = RaviloAppContext.get()
        SimpleCache(File(ctx.cacheDir, "deb3171-warm"), LeastRecentlyUsedCacheEvictor(CACHE_BYTES), StandaloneDatabaseProvider(ctx))
    }
    private val cache: SimpleCache get() = cacheLazy.value

    private fun now() = android.os.SystemClock.elapsedRealtime()

    actual fun begin(masterUrl: String) {
        if (entry?.masterUrl == masterUrl) return
        dropKept()
        entry = Entry(masterUrl, now())
    }

    actual suspend fun keep(segmentUrl: String, body: ByteReadChannel) {
        val e = entry
        if (e == null) { HlsWarm.drain(body); return }
        write(cache, segmentUrl, body)
        e.keys += segmentUrl
    }

    /** True when segments kept for [streamUrl] are waiting to be read. */
    fun holds(streamUrl: String): Boolean {
        val e = entry ?: return false
        return e.masterUrl == streamUrl && e.keys.isNotEmpty() && now() - e.at <= MAX_AGE_MS
    }

    /** The data source [streamUrl] is loaded through: [upstream] behind the kept segments when this is the stream warmed, else [upstream]. */
    fun wrap(streamUrl: String, upstream: DataSource.Factory): DataSource.Factory {
        if (!holds(streamUrl)) return upstream
        return CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null)   // read only: the player never writes this cache
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun dropKept() {
        val e = entry ?: return
        entry = null
        if (cacheLazy.isInitialized()) for (k in e.keys) runCatching { cache.removeResource(k) }
    }

    /** Streams [body] into [cache] under the key the player's data source will look [url] up by (its URL), and records its length. */
    internal suspend fun write(cache: Cache, url: String, body: ByteReadChannel) = withContext(Dispatchers.IO) {
        val sink = CacheDataSink(cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE)
        // The cache's lock on the bytes being written; CacheDataSource takes it the same way before its sink.
        val hole = cache.startReadWrite(url, 0, C.LENGTH_UNSET.toLong())
        var total = 0L
        try {
            sink.open(DataSpec.Builder().setUri(android.net.Uri.parse(url)).setKey(url).build())
            try {
                val chunk = ByteArray(HlsWarm.DRAIN_CHUNK)
                while (true) {
                    val n = body.readAvailable(chunk, 0, chunk.size)
                    if (n < 0) break
                    if (n > 0) { sink.write(chunk, 0, n); total += n }
                }
            } finally {
                sink.close()
            }
        } finally {
            cache.releaseHoleSpan(hole)
        }
        cache.applyContentMetadataMutations(url, ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, total) })
    }
}
