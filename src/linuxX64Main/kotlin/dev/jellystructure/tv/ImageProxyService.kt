package dev.jellystructure.tv

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.contentType
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * R85: on-demand Jellyfin image proxy with disk cache. Every Ravilo image URL is now a relative
 * /api/tv/image/{itemId}/{type} path — this service fetches from Jellyfin on first request and
 * serves from disk on subsequent requests, keeping Jellyfin credentials off the app entirely.
 *
 * Cache lives at $dataDir/artwork/tv/{itemId}-{type} with a sidecar $cachePath.ct for the
 * content-type, so logos (PNG) and stills (JPEG) are served with the correct MIME type.
 *
 * Gate: Semaphore(8) mirrors LogoDownloader, keeping FD count well below the CIO select() ceiling.
 */
class ImageProxyService(
    private val dataDir: String,
    private val configStore: ConfigStore,
) {
    private val gate = Semaphore(8)
    private val http = HttpClient(Curl) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis  = 60_000
            requestTimeoutMillis = 60_000
        }
    }

    private val cacheDir = "$dataDir/artwork/tv"

    // R129: bound the on-disk proxy cache. It was unbounded (grew until the disk filled). Keep an in-memory
    // size index (seeded by a startup scan) and evict the oldest cached images on write once over the cap —
    // no per-serve overhead (eviction only runs on a cache miss, already behind the fetch gate).
    // R130: the cap is configurable (`behavior.tv_image_cache_mb`, MB; 0 = unlimited), read live so a config
    // edit applies without a restart.
    private fun maxCacheBytes(): Long {
        val mb = configStore.current.behavior.tvImageCacheMb
        return if (mb <= 0) Long.MAX_VALUE else mb.coerceAtLeast(50).toLong() * 1024 * 1024
    }
    private val cacheMutex = Mutex()
    private val cacheIndex = LinkedHashMap<String, Long>()         // cacheKey → bytes, oldest-first (insertion order)
    private var cacheBytes = 0L

    init {
        runCatching { SystemFileSystem.createDirectories(Path(cacheDir)) }
        runCatching { indexCache() }
    }

    /**
     * R93: optional [width] param — callers on small screens (phone/web) pass a lower value to
     * receive a smaller image. Default (null) preserves today's values for backward-compat.
     * Cache key includes width only when it differs from the type's default so existing disk-cache
     * entries are reused by TV (which always requests the default 1920px backdrop).
     */
    suspend fun serve(itemId: String, type: String, width: Int? = null): Pair<ByteArray, String>? {
        val cacheKey  = cacheKey(itemId, type, width)
        val cachePath = "$cacheDir/$cacheKey"
        val ctPath    = "$cachePath.ct"

        // Cache hit
        val cached = readCached(cachePath, ctPath)
        if (cached != null) return cached

        return gate.withPermit {
            // Double-check after acquiring permit (another coroutine may have fetched while waiting)
            val cached2 = readCached(cachePath, ctPath)
            if (cached2 != null) return@withPermit cached2

            val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
            val token = configStore.current.apiKeys.jellyfinToken
            if (jellyfinBase.isBlank() || token.isBlank()) return@withPermit null

            val url = jellyfinUrl(jellyfinBase, token, itemId, type, width) ?: return@withPermit null
            val response = runCatching { http.get(url) }.getOrElse {
                Logger.warn("ImageProxy: fetch failed $url — ${it.message}", "tv-image")
                return@withPermit null
            }
            val bytes = runCatching { response.readRawBytes() }.getOrElse {
                Logger.warn("ImageProxy: read failed $url — ${it.message}", "tv-image")
                return@withPermit null
            }
            if (bytes.isEmpty()) return@withPermit null
            val ct = response.contentType()?.toString() ?: "image/jpeg"

            atomicWrite(cachePath, bytes)
            atomicWrite(ctPath, ct.encodeToByteArray())
            recordWrite(cacheKey, bytes.size.toLong())   // R129: track size + evict if over cap

            Pair(bytes, ct)
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    // R129: one-time startup scan to seed the size index from whatever's already on disk (so old entries
    // from previous runs count toward the cap and are evictable too). Pre-existing files are seeded in
    // name order; entries written this session then append to the back, so eviction is oldest-write-first.
    private fun indexCache() {
        val files = SystemFileSystem.list(Path(cacheDir))
            .filter { val n = it.name; !n.endsWith(".ct") && !n.endsWith(".tmp") }
            .sortedBy { it.name }
        for (p in files) {
            val size = SystemFileSystem.metadataOrNull(p)?.size ?: continue
            cacheIndex[p.name] = size
            cacheBytes += size
        }
        val capMb = configStore.current.behavior.tvImageCacheMb
        println("[INFO] ImageProxy: cache index ${cacheIndex.size} files, ${cacheBytes / (1024 * 1024)} MB (cap ${if (capMb <= 0) "unlimited" else "$capMb MB"})")
    }

    // R129: record a freshly-written cache entry and evict the oldest entries if the cache is over the cap.
    private suspend fun recordWrite(key: String, size: Long) {
        val cap = maxCacheBytes()
        val low = cap / 10 * 8   // evict down to ~80% (cap/10*8 avoids overflow when cap = Long.MAX_VALUE)
        val victims = cacheMutex.withLock {
            cacheIndex.remove(key)?.let { cacheBytes -= it }   // re-write: drop the stale size first
            cacheIndex[key] = size                              // (re)insert at the newest end
            cacheBytes += size
            if (cacheBytes <= cap) return@withLock emptyList<String>()
            val out = ArrayList<String>()
            val it = cacheIndex.entries.iterator()
            while (cacheBytes > low && it.hasNext()) {
                val e = it.next()
                if (e.key == key) continue                      // never evict what we just wrote
                it.remove()
                cacheBytes -= e.value
                out.add(e.key)
            }
            out
        }
        if (victims.isEmpty()) return
        for (k in victims) {
            runCatching { SystemFileSystem.delete(Path("$cacheDir/$k"), false) }
            runCatching { SystemFileSystem.delete(Path("$cacheDir/$k.ct"), false) }
        }
        Logger.info("ImageProxy: evicted ${victims.size} cached image(s) — now ${cacheBytes / (1024 * 1024)} MB", "tv-image")
    }

    private fun readCached(cachePath: String, ctPath: String): Pair<ByteArray, String>? {
        val p = Path(cachePath)
        if (!SystemFileSystem.exists(p)) return null
        val bytes = runCatching { SystemFileSystem.source(p).buffered().readByteArray() }.getOrNull()
            ?: return null
        if (bytes.isEmpty()) return null
        val ct = runCatching {
            SystemFileSystem.source(Path(ctPath)).buffered().readByteArray().decodeToString()
        }.getOrDefault("image/jpeg")
        return Pair(bytes, ct)
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun atomicWrite(destPath: String, bytes: ByteArray) {
        val tmp = "$destPath.tmp"
        val result = runCatching {
            SystemFileSystem.sink(Path(tmp)).buffered().use { sink ->
                sink.write(bytes, 0, bytes.size)
            }
            platform.posix.rename(tmp, destPath)
        }
        if (result.isFailure) Logger.warn("ImageProxy: cache write failed $destPath — ${result.exceptionOrNull()?.message}", "tv-image")
    }

    // R93: include width in cache key only when non-default so existing TV cache entries are reused.
    private fun cacheKey(itemId: String, type: String, width: Int?): String {
        val defaultW = when (type) { "backdrop" -> 1920; "poster" -> 320; "still" -> 640; else -> null }
        return if (width != null && width > 0 && width != defaultW) "$itemId-$type-$width" else "$itemId-$type"
    }

    private fun jellyfinUrl(base: String, token: String, itemId: String, type: String, width: Int? = null): String? = when (type) {
        "poster"   -> "$base/Items/$itemId/Images/Primary?api_key=$token&fillHeight=480&fillWidth=320&quality=90"
        "backdrop" -> "$base/Items/$itemId/Images/Backdrop/0?api_key=$token&fillWidth=${width?.takeIf { it > 0 } ?: 1920}&quality=90"
        "logo"     -> "$base/Items/$itemId/Images/Logo?api_key=$token&fillHeight=300&format=png"
        "still"    -> "$base/Items/$itemId/Images/Primary?api_key=$token&fillWidth=${width?.takeIf { it > 0 } ?: 640}&quality=90"
        "avatar"   -> "$base/Users/$itemId/Images/Primary?api_key=$token&fillHeight=160"
        else       -> null  // unknown type — route handler returns 404
    }
}
