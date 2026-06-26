package dev.jellystructure.tv

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.contentType
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Semaphore
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
    private val http = HttpClient(Curl)

    private val cacheDir = "$dataDir/artwork/tv"

    init {
        runCatching { SystemFileSystem.createDirectories(Path(cacheDir)) }
    }

    suspend fun serve(itemId: String, type: String): Pair<ByteArray, String>? {
        val cachePath = "$cacheDir/$itemId-$type"
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

            val url = jellyfinUrl(jellyfinBase, token, itemId, type) ?: return@withPermit null
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

            Pair(bytes, ct)
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

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

    private fun jellyfinUrl(base: String, token: String, itemId: String, type: String): String? = when (type) {
        "poster"   -> "$base/Items/$itemId/Images/Primary?api_key=$token&fillHeight=480&fillWidth=320&quality=90"
        "backdrop" -> "$base/Items/$itemId/Images/Backdrop/0?api_key=$token&fillWidth=1920&quality=90"
        "logo"     -> "$base/Items/$itemId/Images/Logo?api_key=$token&fillHeight=300"
        "still"    -> "$base/Items/$itemId/Images/Primary?api_key=$token&fillWidth=640&quality=90"
        "avatar"   -> "$base/Users/$itemId/Images/Primary?api_key=$token&fillHeight=160"
        else       -> null  // unknown type — route handler returns 404
    }
}
