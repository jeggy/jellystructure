package dev.jellystructure.ravilo.ui

import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.Options
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * DEB-4396 (DEB-4390, FR-2) — artwork's own OkHttp lane. [rest] is the one client behind REST (R316); this is
 * derived from it with `newBuilder()`, so the connection pool stays shared, but it has its own [Dispatcher]:
 * 8 calls per host, and a 15 s call timeout (images had none — a socket a standby left dead held its slot for
 * the whole 10 s read timeout, and a launch's tiles, row prefetches and hero slides queued behind it). A REST call
 * can no longer hold a slot an image is waiting for, nor the other way round.
 */
internal fun artworkClient(rest: OkHttpClient): OkHttpClient =
    rest.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

/**
 * DEB-4396 (FR-4.2) — a disk-cache hit draws without a network round trip. Coil's default strategy revalidates a
 * cached image the server's headers call stale; here a cached image is used as it is, because R214's `?v=` changes
 * the URL when the bytes change, so a URL that is cached is the right image. Writes are Coil's own.
 */
internal object NeverRevalidateCacheStrategy : CacheStrategy {
    override suspend fun read(cacheResponse: NetworkResponse, networkRequest: NetworkRequest, options: Options) =
        CacheStrategy.ReadResult(cacheResponse)

    override suspend fun write(cacheResponse: NetworkResponse?, networkRequest: NetworkRequest, networkResponse: NetworkResponse, options: Options) =
        CacheStrategy.DEFAULT.write(cacheResponse, networkRequest, networkResponse, options)
}
