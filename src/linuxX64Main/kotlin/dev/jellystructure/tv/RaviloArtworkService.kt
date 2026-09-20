package dev.jellystructure.tv

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.OutboundHttp
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.FfmpegRunner
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.auth.jellyfinAuth
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
/** Phase 220 (FR-220-2) — the largest original served in place of a resize under a saturated gate. */
internal const val ORIGINAL_FALLBACK_MAX_BYTES = 2L * 1024 * 1024

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

    companion object {
        // Phase 187 (FR-187-6) — the read path's own intent was 160px (RaviloArtworkService's prior
        // fillHeight=160 request, before FR-187-1's probe found Jellyfin ignores it); 320 covers every
        // surface at 2x. This is the only bound anywhere in the chain (Jellyfin stores + re-serves the
        // upload verbatim, per that same probe), so it is a real ceiling, not a suggestion.
        private const val AVATAR_SIZE_PX = 320
    }

    // R129/R130: bound the on-disk media cache (configurable; 0 = unlimited), read live so a config edit
    // applies without a restart. Eviction runs only on a cache miss (already behind the gate).
    private fun maxCacheBytes(): Long {
        val mb = configStore.current.behavior.tvImageCacheMb
        return if (mb <= 0) Long.MAX_VALUE else mb.coerceAtLeast(50).toLong() * 1024 * 1024
    }
    private val cacheMutex = Mutex()
    private val cacheIndex = LinkedHashMap<String, Long>()   // cacheKey → bytes, oldest-write-first
    private var cacheBytes = 0L

    // Phase 220 (FR-220-3/5) — one in-flight resize per cache key (two viewers scrolling the same season
    // must not spawn the same ffmpeg twice), and a count of resizes that happened ON THE REQUEST PATH in
    // the last hour, so a regression of FR-220-1 is visible on /api/health.
    private val inFlightMutex = Mutex()
    private val inFlight = HashMap<String, Mutex>()
    private val requestResizeStamps = ArrayDeque<Long>()
    private var presizedTotal = 0L
    /** Test hook — makes the request path behave as if the process gate were saturated (FR-220-2). */
    internal var forceGateTimeoutForTests = false

    class ImageStats(val resizesOnRequestLastHour: Int, val presizedTotal: Long) {
        fun toJson(): String = """{"resizes_on_request_last_hour":$resizesOnRequestLastHour,"presized_total":$presizedTotal}"""
    }
    suspend fun stats(): ImageStats = inFlightMutex.withLock {
        val cutoff = nowEpochMs() - 3_600_000L
        while (requestResizeStamps.isNotEmpty() && requestResizeStamps.first() < cutoff) requestResizeStamps.removeFirst()
        ImageStats(requestResizeStamps.size, presizedTotal)
    }

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
        return resizeServe(stillCacheKey(seriesId, ep, width), source, "still", width)
    }

    /** Phase 220 (FR-220-1) — ONE key derivation for the request path and the pre-sizer, so what the
     *  pipeline writes is exactly what [serveStill] reads (the spec's verification 1). Bug fix kept from
     *  Phase 149: the episode number is part of the key, so a multi-episode file's episodes never share one. */
    internal fun stillCacheKey(seriesId: String, ep: dev.jellystructure.model.Episode, width: Int? = null): String {
        val wSuffix = width?.takeIf { it > 0 && it != 640 }?.let { "-$it" } ?: ""
        val epSuffix = ep.episodeNumber?.let { "-e$it" } ?: ""
        return "$seriesId-still-${ep.filename.hashCode().toUInt()}$epSuffix$wSuffix"
    }
    internal fun seasonPosterCacheKey(itemId: String, season: Int, width: Int? = null): String {
        val wSuffix = width?.takeIf { it > 0 && it != 320 }?.let { "-$it" } ?: ""
        return "$itemId-season$season-poster$wSuffix"
    }

    /**
     * R194 — a season's own poster (`seasonNN-poster.jpg`, R126/Phase 151's manual-lock asset), for the
     * player's OS media-session artwork: seasons read better there than an individual episode still.
     * Same resize/cache treatment as [serve]'s `"poster"` type; 404s (via [resizeServe]'s existing
     * missing-source guard) when the season simply has no poster on disk — the client falls back to the
     * series' own poster in that case (R193's existing `posterUrl` fallback chain).
     */
    suspend fun serveSeasonPoster(itemId: String, season: Int, width: Int? = null): Pair<ByteArray, String>? {
        val item = store.resolve(itemId) ?: return null
        val source = artwork.seasonPosterPath(item, season)
        return resizeServe(seasonPosterCacheKey(itemId, season, width), source, "poster", width)
    }

    /**
     * Avatar — the one remaining (cached) Jellyfin fetch. Durable cache; 404 when Jellyfin has no image.
     *
     * Phase 187 (FR-187-7): [requestedTag] is Jellyfin's own `PrimaryImageTag`, carried on the URL as
     * `?v=` ([RaviloImageUrl.avatar]) exactly like [dev.jellystructure.media.ArtworkDownloader]'s size-
     * keyed versioning does for library art (R214's fix for the *other* half of this bug class). The
     * sidecar `.ct` file now stores `"$tag|$contentType"`; a cached entry only counts as fresh when its
     * stored tag matches what was requested — a mismatch (new photo, new tag) is a cache MISS, not
     * something needing an explicit eviction call from the write path. `requestedTag == null` (an old
     * cached client, or a caller with no tag to hand) always accepts whatever is cached, unchanged from
     * before this phase.
     *
     * Also moved off the undocumented `/Users/{userId}/Images/Primary` legacy alias onto the documented
     * `/UserImage?userId=` route — verified live 2026-09-05 (FR-187-1's probe) to return byte-identical
     * output; the alias simply isn't in Jellyfin's own OpenAPI document, so relying on it was one
     * upgrade away from silently breaking. `fillHeight`/`quality` are dropped from the request for the
     * same probe's other finding: Jellyfin ignores both and always returns the full original — they were
     * never doing anything.
     */
    suspend fun serveAvatar(userId: String, requestedTag: String? = null): Pair<ByteArray, String>? {
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
        readTagged(cachePath, ctPath, requestedTag)?.let { return it }
        return OutboundHttp.withPermit {
            readTagged(cachePath, ctPath, requestedTag)?.let { return@withPermit it }
            val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
            val token = configStore.current.apiKeys.jellyfinToken
            if (base.isBlank() || token.isBlank()) return@withPermit null
            // Phase 239 (FR-239-1) — an ordinary outbound fetch: the credential is a header, never a
            // query parameter. `api_key` is not honoured at all on 12.1 (measured 2026-09-20).
            val resp = runCatching { http.get("$base/UserImage?userId=$userId") { jellyfinAuth(token) } }
                .getOrElse { Logger.warn("RaviloArtwork: avatar fetch failed $userId — ${it.message}", "tv-image"); return@withPermit null }
            val bytes = runCatching { resp.readRawBytes() }.getOrNull() ?: return@withPermit null
            val ct = resp.contentType()?.toString() ?: "image/jpeg"
            // R132: never cache a non-image (Jellyfin returns a JSON body when a user has no avatar).
            if (resp.status.value !in 200..299 || bytes.isEmpty() || !ct.startsWith("image/")) return@withPermit null
            atomicWrite(cachePath, bytes)
            atomicWrite(ctPath, "${requestedTag.orEmpty()}|$ct".encodeToByteArray())
            Pair(bytes, ct)
        }
    }

    /**
     * Phase 187 (FR-187-6/7) — the write half: centre-crops+bounds server-side, forwards to Jellyfin,
     * and returns the new tag so the caller can hand back a change-keyed URL immediately (no waiting on
     * the next cache read to pick up the new photo — FR-R234-8). [rawBytes] must already be validated
     * (allowed content type, size cap) by the route.
     */
    suspend fun setAvatar(userId: String, jellyfinClient: dev.jellystructure.auth.JellyfinClient, rawBytes: ByteArray): String? {
        val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = configStore.current.apiKeys.jellyfinToken
        if (base.isBlank() || token.isBlank()) return null
        val tmpIn = "$avatarDir/.upload_$userId.src"
        val tmpOut = "$avatarDir/.upload_$userId.jpg"
        return OutboundHttp.withPermit {
            runCatching { FileIo.writeBytes(Path(tmpIn), rawBytes) }.onFailure { return@withPermit null }
            val cropped = FfmpegRunner.centerCropSquareJpeg(tmpIn, tmpOut, AVATAR_SIZE_PX)
            val bytes = if (cropped) runCatching { FileIo.readBytes(Path(tmpOut)) }.getOrNull() else null
            runCatching { SystemFileSystem.delete(Path(tmpIn), false) }
            runCatching { SystemFileSystem.delete(Path(tmpOut), false) }
            if (bytes == null || bytes.isEmpty()) { Logger.warn("RaviloArtwork: avatar crop failed for $userId", "tv-image"); return@withPermit null }
            val tag = jellyfinClient.setUserImage(base, token, userId, bytes, "image/jpeg") ?: return@withPermit null
            // Prime the cache with exactly what we just uploaded (already the right size/format) so the
            // very next read is a hit at the new tag, rather than an avoidable round-trip back to Jellyfin.
            atomicWrite("$avatarDir/$userId", bytes)
            atomicWrite("$avatarDir/$userId.ct", "$tag|image/jpeg".encodeToByteArray())
            tag
        }
    }

    /** Phase 187 (FR-187-3) — remove the caller's own avatar; clears the local cache too so a stale
     *  file can't outlive the Jellyfin-side delete (same discipline as [setAvatar]). */
    suspend fun deleteAvatar(userId: String, jellyfinClient: dev.jellystructure.auth.JellyfinClient): Boolean {
        val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = configStore.current.apiKeys.jellyfinToken
        if (base.isBlank() || token.isBlank()) return false
        val ok = jellyfinClient.deleteUserImage(base, token, userId)
        if (ok) {
            runCatching { SystemFileSystem.delete(Path("$avatarDir/$userId"), false) }
            runCatching { SystemFileSystem.delete(Path("$avatarDir/$userId.ct"), false) }
        }
        return ok
    }

    // ─── core: resolve source → size-keyed cache → ffmpeg resize ──────────────────
    private suspend fun resizeServe(cacheKey: String, sourcePath: String, type: String, width: Int?): Pair<ByteArray, String>? {
        val srcSize = SystemFileSystem.metadataOrNull(Path(sourcePath))?.size ?: return null  // no source file → no art
        val cachePath = "$cacheDir/$cacheKey"
        val ctPath = "$cachePath.ct"
        readFresh(cachePath, ctPath, srcSize)?.let { return it }
        // Phase 220 (FR-220-3) — this used to hold an OutboundHttp permit around a LOCAL process, coupling
        // two unrelated pools; ProcessGate (inside FfmpegRunner) is the only gate a local ffmpeg holds now.
        // One in-flight resize per key coalesces a burst of identical requests.
        val keyMutex = inFlightMutex.withLock { inFlight.getOrPut(cacheKey) { Mutex() } }
        try {
            return keyMutex.withLock {
                readFresh(cachePath, ctPath, srcSize)?.let { return@withLock it }
                val produced = try {
                    if (forceGateTimeoutForTests) throw dev.jellystructure.ops.ProcessGate.GateTimeoutException("test: gate saturated")
                    resizeInto(cacheKey, sourcePath, type, width, srcSize)
                } catch (e: dev.jellystructure.ops.ProcessGate.GateTimeoutException) {
                    // FR-220-2 — an image is never worth a 503: under a saturated gate serve the ORIGINAL
                    // (bounded — a still or poster is small; a 4K backdrop original is not), else let the
                    // 503 stand for the one case the cap excludes.
                    if (srcSize <= ORIGINAL_FALLBACK_MAX_BYTES) {
                        Logger.info("RaviloArtwork: gate busy — serving the original for $cacheKey (${srcSize / 1024} KB)", "tv-image")
                        val bytes = runCatching { FileIo.readBytes(Path(sourcePath)) }.getOrNull() ?: return@withLock null
                        return@withLock Pair(bytes, if (sourcePath.endsWith(".png", true)) "image/png" else "image/jpeg")
                    }
                    throw e
                }
                inFlightMutex.withLock { requestResizeStamps.addLast(nowEpochMs()) }
                produced
            }
        } finally {
            inFlightMutex.withLock { if (!keyMutex.isLocked) inFlight.remove(cacheKey) }
        }
    }

    /** The resize itself; shared by the request path and [presize]. Null when ffmpeg produced nothing. */
    private suspend fun resizeInto(cacheKey: String, sourcePath: String, type: String, width: Int?, srcSize: Long): Pair<ByteArray, String>? {
        val cachePath = "$cacheDir/$cacheKey"
        val ctPath = "$cachePath.ct"
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
            return null
        }
        atomicWrite(cachePath, bytes)
        atomicWrite(ctPath, "$srcSize|$ct".encodeToByteArray())
        recordWrite(cacheKey, bytes.size.toLong())
        return Pair(bytes, ct)
    }

    /**
     * Phase 220 (FR-220-1/4) — produce, at pipeline time, every variant the TV will ask for: poster 320,
     * backdrop 1920, logo h300 (under BOTH ids a card can carry — the Jellyfin id the browse/home cards
     * use and the slug the detail page uses), each season's poster, and every episode still at 640.
     * Keys are the exact ones the request path computes. Already-fresh entries are skipped, so a
     * re-run after a restart resumes rather than repeats. Returns how many files were produced.
     */
    suspend fun presize(item: MediaItem): Int {
        var produced = 0
        suspend fun ensure(key: String, source: String?, type: String) {
            source ?: return
            val srcSize = SystemFileSystem.metadataOrNull(Path(source))?.size ?: return
            val cachePath = "$cacheDir/$key"
            if (readFresh(cachePath, "$cachePath.ct", srcSize) != null) return
            if (resizeInto(key, source, type, null, srcSize) != null) { produced++; inFlightMutex.withLock { presizedTotal++ } }
        }
        val ids = listOfNotNull(item.jellyfinId, item.id).distinct()
        for (id in ids) {
            ensure(cacheKey(id, "poster", null), sourceFile(item, "poster"), "poster")
            ensure(cacheKey(id, "backdrop", null), sourceFile(item, "backdrop"), "backdrop")
            ensure(cacheKey(id, "logo", null), sourceFile(item, "logo"), "logo")
        }
        val seasons = item.episodes.mapNotNull { it.seasonNumber }.distinct()
        for (season in seasons) ensure(seasonPosterCacheKey(item.id, season), artwork.seasonPosterPath(item, season), "poster")
        for (ep in item.episodes) ensure(stillCacheKey(item.id, ep), artwork.episodeStillPath(ep), "still")
        return produced
    }

    /** Phase 220 (FR-220-4) — the one-time backfill's marker, next to the cache it fills. */
    fun backfillDone(): Boolean = SystemFileSystem.exists(Path("$cacheDir/.presize-done"))
    suspend fun markBackfillDone() = atomicWrite("$cacheDir/.presize-done", nowEpochMs().toString().encodeToByteArray())

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
    /** Phase 187 (FR-187-7) — [requestedTag] absent means "accept whatever's cached" (pre-Phase-187
     *  callers, or Jellyfin genuinely has no tag for this user); present means the cached tag must
     *  match or this is treated as a miss. Sidecar format: `"$tag|$contentType"`. */
    private fun readTagged(cachePath: String, ctPath: String, requestedTag: String?): Pair<ByteArray, String>? {
        if (!SystemFileSystem.exists(Path(cachePath))) return null
        val bytes = runCatching { FileIo.readBytes(Path(cachePath)) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val meta = runCatching { FileIo.readBytes(Path(ctPath)).decodeToString() }.getOrDefault("|image/jpeg")
        val parts = meta.split('|', limit = 2)
        val cachedTag = parts.getOrNull(0).orEmpty()
        val ct = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "image/jpeg"
        if (requestedTag != null && cachedTag != requestedTag) return null
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

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun nowEpochMs(): Long = platform.posix.time(null) * 1000L
