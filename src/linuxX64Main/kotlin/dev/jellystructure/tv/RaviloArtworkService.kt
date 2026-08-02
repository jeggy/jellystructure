package dev.jellystructure.tv

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.OutboundHttp
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.FfmpegRunner
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.contentType
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * R133: serves Ravilo artwork from jellystructure's OWN on-disk files (poster.jpg / fanart.jpg /
 * clearlogo.png / <ep>-thumb.jpg), resized by ffmpeg and cached — **no Jellyfin call for media**.
 * Supersedes the R85 Jellyfin image proxy. Image URLs are keyed by `MediaItem.id` (full decouple).
 *
 * Avatars are the one exception: jellystructure fetches the Jellyfin user image once and caches it
 * durably, so both Ravilo and the admin pair-a-device UI can show it.
 *
 * Media cache: `$dataDir/artwork/tv/{key}` + `{key}.ct` = `"<source-byte-size>|<content-type>"`. The
 * source file's byte size is the change signal — any artwork rewrite (pipeline / manual pick / R131
 * screengrab→TMDB upgrade) changes the compressed size, so a cache entry auto-invalidates with no
 * explicit hooks to miss. Bounded by `behavior.tv_image_cache_mb` (R129/R130).
 * Avatar cache: `$dataDir/artwork/avatars/{userId}` — durable, not size-capped.
 *
 * Gate: the app-wide [OutboundHttp] permit keeps FD count well below the CIO select() ceiling
 * (avatar fetch + ffmpeg spawn both consume FDs from the same process-wide table).
 */
class RaviloArtworkService(
    dataDir: String,
    private val configStore: ConfigStore,
    private val store: MediaStore,
    private val artwork: ArtworkDownloader,
) {
    // Phase 129 (FR-OPS1 §B.1) — shared client, one idle connection pool for all outbound callers.
    private val http = OutboundHttp.client

    private val cacheDir = "$dataDir/artwork/tv"
    private val avatarDir = "$dataDir/artwork/avatars"

    // R129/R130: bound the on-disk media cache (configurable; 0 = unlimited), read live so a config edit
    // applies without a restart. Eviction runs only on a cache miss (already behind the gate).
    private fun maxCacheBytes(): Long {
        val mb = configStore.current.behavior.tvImageCacheMb
        return if (mb <= 0) Long.MAX_VALUE else mb.coerceAtLeast(50).toLong() * 1024 * 1024
    }
    private val cacheMutex = Mutex()
    private val cacheIndex = LinkedHashMap<String, Long>()   // cacheKey → bytes, oldest-write-first
    private var cacheBytes = 0L

    init {
        runCatching { SystemFileSystem.createDirectories(Path(cacheDir)) }
        runCatching { SystemFileSystem.createDirectories(Path(avatarDir)) }
        runCatching { indexCache() }
    }

    /** Media artwork (poster | backdrop | logo) for [itemId] (a MediaItem.id). [width] override for phone/web. */
    suspend fun serve(itemId: String, type: String, width: Int? = null): Pair<ByteArray, String>? {
        val item = store.resolve(itemId) ?: return null
        val source = sourceFile(item, type) ?: return null
        return resizeServe(cacheKey(itemId, type, width), source, type, width)
    }

    /**
     * Episode still: addressed by the series id + the episode filename, disambiguated by [epNum] when
     * several episodes share that filename (a multi-episode file, Phase 149). Bug fix: this used to
     * always resolve `firstOrNull { it.filename == epFilename }` — every episode in a group has the same
     * filename, so it always served episode 1's still for the whole group, no matter which episode's
     * still was actually requested (reported: Ravilo's triptych card showed the same image 3 times).
     * Omitted [epNum] falls back to the first match, unchanged for the ordinary single-episode case.
     */
    suspend fun serveStill(seriesId: String, epFilename: String, epNum: Int? = null, width: Int? = null): Pair<ByteArray, String>? {
        val item = store.resolve(seriesId) ?: return null
        val ep = if (epNum != null) item.episodes.firstOrNull { it.filename == epFilename && it.episodeNumber == epNum }
            else item.episodes.firstOrNull { it.filename == epFilename }
        ep ?: return null
        val source = artwork.episodeStillPath(ep)
        val wSuffix = width?.takeIf { it > 0 && it != 640 }?.let { "-$it" } ?: ""
        // Bug fix: the cache key was filename-only too, so every episode in a group shared one cache
        // entry — whichever still got resized+cached first silently served for all of them afterward.
        val epSuffix = ep.episodeNumber?.let { "-e$it" } ?: ""
        return resizeServe("$seriesId-still-${epFilename.hashCode().toUInt()}$epSuffix$wSuffix", source, "still", width)
    }

    /** Avatar — the one remaining (cached) Jellyfin fetch. Durable cache; 404 when Jellyfin has no image. */
    suspend fun serveAvatar(userId: String): Pair<ByteArray, String>? {
        // Security fix (2026-08-02 review, finding C2) — this route is intentionally auth-exempt
        // (AuthPlugin's OPEN_API_PATHS: an <img>/Coil request can't attach a device token) and the
        // cache read below happens BEFORE any Jellyfin round-trip. Without this guard, a userId of
        // e.g. "..%2F..%2Fconfig.toml" decodes (Ktor routing decodes path params) to a literal `/`,
        // turning `$avatarDir/$userId` into a traversal out of the cache directory — confirmed live
        // during the audit to return config.toml's exact bytes with zero authentication. Same guard
        // ChannelLogoStore.read already uses correctly.
        if (".." in userId || "/" in userId || "\\" in userId) return null
        val cachePath = "$avatarDir/$userId"
        val ctPath = "$cachePath.ct"
        readSimple(cachePath, ctPath)?.let { return it }
        return OutboundHttp.withPermit {
            readSimple(cachePath, ctPath)?.let { return@withPermit it }
            val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
            val token = configStore.current.apiKeys.jellyfinToken
            if (base.isBlank() || token.isBlank()) return@withPermit null
            val resp = runCatching { http.get("$base/Users/$userId/Images/Primary?api_key=$token&fillHeight=160&quality=90") }
                .getOrElse { Logger.warn("RaviloArtwork: avatar fetch failed $userId — ${it.message}", "tv-image"); return@withPermit null }
            val bytes = runCatching { resp.readRawBytes() }.getOrNull() ?: return@withPermit null
            val ct = resp.contentType()?.toString() ?: "image/jpeg"
            // R132: never cache a non-image (Jellyfin returns a JSON body when a user has no avatar).
            if (resp.status.value !in 200..299 || bytes.isEmpty() || !ct.startsWith("image/")) return@withPermit null
            atomicWrite(cachePath, bytes)
            atomicWrite(ctPath, ct.encodeToByteArray())
            Pair(bytes, ct)
        }
    }

    // ─── core: resolve source → size-keyed cache → ffmpeg resize ──────────────────
    private suspend fun resizeServe(cacheKey: String, sourcePath: String, type: String, width: Int?): Pair<ByteArray, String>? {
        val srcSize = SystemFileSystem.metadataOrNull(Path(sourcePath))?.size ?: return null  // no source file → no art
        val cachePath = "$cacheDir/$cacheKey"
        val ctPath = "$cachePath.ct"
        readFresh(cachePath, ctPath, srcSize)?.let { return it }
        return OutboundHttp.withPermit {
            readFresh(cachePath, ctPath, srcSize)?.let { return@withPermit it }
            val isPng = type == "logo"
            val ct = if (isPng) "image/png" else "image/jpeg"
            val tmpOut = "$cacheDir/.rsz_$cacheKey.${if (isPng) "png" else "jpg"}"
            val ok = when (type) {
                "poster"   -> FfmpegRunner.resizeImage(sourcePath, tmpOut, width = width?.takeIf { it > 0 } ?: 320)
                "backdrop" -> FfmpegRunner.resizeImage(sourcePath, tmpOut, width = width?.takeIf { it > 0 } ?: 1920)
                "still"    -> FfmpegRunner.resizeImage(sourcePath, tmpOut, width = width?.takeIf { it > 0 } ?: 640)
                "logo"     -> FfmpegRunner.resizeImage(sourcePath, tmpOut, height = 300)
                else       -> false
            }
            val bytes = if (ok) runCatching { FileIo.readBytes(Path(tmpOut)) }.getOrNull() else null
            runCatching { SystemFileSystem.delete(Path(tmpOut), false) }
            if (bytes == null || bytes.isEmpty()) {
                Logger.warn("RaviloArtwork: resize failed $sourcePath ($type)", "tv-image")
                return@withPermit null
            }
            atomicWrite(cachePath, bytes)
            atomicWrite(ctPath, "$srcSize|$ct".encodeToByteArray())
            recordWrite(cacheKey, bytes.size.toLong())
            Pair(bytes, ct)
        }
    }

    private fun sourceFile(item: MediaItem, type: String): String? = when (type) {
        "poster"   -> artwork.assetPath(item, "poster")
        "backdrop" -> artwork.assetPath(item, "backdrop")
        "logo"     -> artwork.assetPath(item, "clearlogo")
        else       -> null
    }

    // ─── cache helpers ────────────────────────────────────────────────────────────

    // R129: one-time startup scan to seed the size index so old entries count toward the cap + are evictable.
    private fun indexCache() {
        val files = SystemFileSystem.list(Path(cacheDir))
            .filter { val n = it.name; !n.endsWith(".ct") && !n.endsWith(".tmp") && !n.startsWith(".rsz_") }
            .sortedBy { it.name }
        for (p in files) {
            val size = SystemFileSystem.metadataOrNull(p)?.size ?: continue
            cacheIndex[p.name] = size
            cacheBytes += size
        }
        val capMb = configStore.current.behavior.tvImageCacheMb
        println("[INFO] RaviloArtwork: cache index ${cacheIndex.size} files, ${cacheBytes / (1024 * 1024)} MB (cap ${if (capMb <= 0) "unlimited" else "$capMb MB"})")
    }

    // R129: record a freshly-written entry and evict the oldest if over the cap.
    private suspend fun recordWrite(key: String, size: Long) {
        val cap = maxCacheBytes()
        val low = cap / 10 * 8   // evict down to ~80% (cap/10*8 avoids overflow when cap = Long.MAX_VALUE)
        val victims = cacheMutex.withLock {
            cacheIndex.remove(key)?.let { cacheBytes -= it }
            cacheIndex[key] = size
            cacheBytes += size
            if (cacheBytes <= cap) return@withLock emptyList()
            val out = ArrayList<String>()
            val it = cacheIndex.entries.iterator()
            while (cacheBytes > low && it.hasNext()) {
                val e = it.next()
                if (e.key == key) continue
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
        Logger.info("RaviloArtwork: evicted ${victims.size} cached image(s) — now ${cacheBytes / (1024 * 1024)} MB", "tv-image")
    }

    /** Media cache read with size-staleness check (R133 auto-invalidation). */
    private fun readFresh(cachePath: String, ctPath: String, srcSize: Long): Pair<ByteArray, String>? {
        if (!SystemFileSystem.exists(Path(cachePath))) return null
        val meta = runCatching { FileIo.readBytes(Path(ctPath)).decodeToString() }.getOrNull() ?: return null
        val parts = meta.split('|', limit = 2)
        if (parts.getOrNull(0)?.toLongOrNull() != srcSize) return null   // source changed → stale
        val ct = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "image/jpeg"
        val bytes = runCatching { FileIo.readBytes(Path(cachePath)) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return Pair(bytes, ct)
    }

    /** Avatar cache read — durable, no staleness check. */
    private fun readSimple(cachePath: String, ctPath: String): Pair<ByteArray, String>? {
        if (!SystemFileSystem.exists(Path(cachePath))) return null
        val bytes = runCatching { FileIo.readBytes(Path(cachePath)) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val ct = runCatching { FileIo.readBytes(Path(ctPath)).decodeToString() }.getOrDefault("image/jpeg")
        return Pair(bytes, ct)
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun atomicWrite(destPath: String, bytes: ByteArray) {
        val tmp = "$destPath.tmp"
        val result = runCatching {
            SystemFileSystem.sink(Path(tmp)).buffered().use { sink -> sink.write(bytes, 0, bytes.size) }
            platform.posix.rename(tmp, destPath)
        }
        if (result.isFailure) Logger.warn("RaviloArtwork: cache write failed $destPath — ${result.exceptionOrNull()?.message}", "tv-image")
    }

    // R93: include width in the cache key only when non-default so default-size entries are shared.
    private fun cacheKey(itemId: String, type: String, width: Int?): String {
        val defaultW = when (type) { "backdrop" -> 1920; "poster" -> 320; "still" -> 640; else -> null }
        return if (width != null && width > 0 && width != defaultW) "$itemId-$type-$width" else "$itemId-$type"
    }
}
