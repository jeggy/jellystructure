package dev.jellystructure.ravilo.ui.downloads

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloaderFactory
import java.io.File
import java.util.concurrent.Executors

/**
 * DEB-4070 (DEB-3995 FR-7) — Media3's download machinery for the phone, built once per process. The media lives under `filesDir`, **never**
 * `cacheDir` (the two evictable 64 MB caches in `NextPrefetch` / `WarmedSegments` are a different thing), so the system never evicts it,
 * and nothing evicts it but the viewer ([NoOpCacheEvictor]).
 *
 * The cache key of a download is **stable across tickets**: a progressive file is keyed by its download id (the ticket's URL carries a
 * per-ticket value), and a smaller copy's segments by the download id plus the segment's path with the stream's `dl-<id>` folded away.
 * That is what lets a paused or expired download ask `start` again, get a different URL, and resume where it stopped (FR-4).
 * Slice 4's offline play must read through [cacheKeyFactoryFor] too.
 */
@UnstableApi
object RaviloDownloadStack {
    const val FOREGROUND_ID = 4070
    const val CHANNEL_ID = "ravilo_downloads"
    const val SCHEDULER_JOB_ID = 4071

    @Volatile private var manager: DownloadManager? = null
    @Volatile private var cacheInstance: SimpleCache? = null
    @Volatile private var dbProvider: DatabaseProvider? = null

    /** `filesDir/downloads` — the media, the index and (per viewer) the saved sidecars live under here. */
    fun root(ctx: Context): File = File(ctx.applicationContext.filesDir, "downloads").also { it.mkdirs() }

    private fun database(ctx: Context): DatabaseProvider =
        dbProvider ?: synchronized(this) { dbProvider ?: StandaloneDatabaseProvider(ctx.applicationContext).also { dbProvider = it } }

    fun cache(ctx: Context): Cache =
        cacheInstance ?: synchronized(this) {
            cacheInstance ?: SimpleCache(File(root(ctx), "media"), NoOpCacheEvictor(), database(ctx)).also { cacheInstance = it }
        }

    /** A segment's cache key: the download's own id, then the path with `/dl-<id>/` folded to `/dl/`, no query. */
    fun stableKey(downloadId: String, uri: Uri): String {
        val path = (uri.path ?: uri.toString()).replace(Regex("/dl-[^/]+/"), "/dl/")
        return "$downloadId#$path"
    }

    fun cacheKeyFactoryFor(downloadId: String): CacheKeyFactory = CacheKeyFactory { spec -> spec.key ?: stableKey(downloadId, spec.uri) }

    private val upstream = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)

    /** The one [DownloadManager]. Construct it on the main thread: it binds to the looper it is created on. */
    fun manager(ctx: Context): DownloadManager =
        manager ?: synchronized(this) {
            manager ?: run {
                val app = ctx.applicationContext
                val c = cache(app)
                val executor = Executors.newFixedThreadPool(3)
                val factory = DownloaderFactory { request ->
                    val cacheSource = CacheDataSource.Factory()
                        .setCache(c)
                        .setCacheKeyFactory(cacheKeyFactoryFor(request.id))
                        .setUpstreamDataSourceFactory(upstream)
                    DefaultDownloaderFactory(cacheSource, executor).createDownloader(request)
                }
                DownloadManager(app, DefaultDownloadIndex(database(app)), factory).also {
                    it.maxParallelDownloads = 1   // FR-7: one at a time, in the order they were asked for
                    manager = it
                }
            }
        }
}
