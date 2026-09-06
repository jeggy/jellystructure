package dev.jellystructure.media

import dev.jellystructure.OutboundHttp
import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.tmdb.TmdbClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable

private const val TMDB_ORIGINAL = "https://image.tmdb.org/t/p/original"

/** Phase 192 (FR-192-1) — the image formats [sniffImageSignature] recognizes. SVG is a legitimate TMDB
 *  logo format (FR-192-3 handles rasterising it before it reaches a `.png`-named file); every other
 *  unrecognized signature is rejected outright — a status-2xx response with an image content type can
 *  still be an HTML error page some CDNs mislabel, and the magic bytes are the only check that catches it. */
internal enum class SniffedImage { PNG, JPEG, WEBP, GIF, SVG }

/** Reads only the magic bytes — never trusts the HTTP status or Content-Type alone (both can lie; see
 *  the 504-page-saved-as-clearlogo.png incident this phase fixes). Returns null for anything else. */
internal fun sniffImageSignature(bytes: ByteArray): SniffedImage? {
    fun matches(offset: Int, sig: ByteArray): Boolean {
        if (bytes.size < offset + sig.size) return false
        for (i in sig.indices) if (bytes[offset + i] != sig[i]) return false
        return true
    }
    fun ascii(s: String) = s.map { it.code.toByte() }.toByteArray()
    if (matches(0, byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))) return SniffedImage.PNG
    if (matches(0, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) return SniffedImage.JPEG
    if (matches(0, ascii("RIFF")) && matches(8, ascii("WEBP"))) return SniffedImage.WEBP
    if (matches(0, ascii("GIF8"))) return SniffedImage.GIF
    // SVG is textual and may be preceded by a BOM/whitespace/XML prolog before the root element — scan
    // the first 1KB for the literal root tag rather than requiring it at offset 0.
    val needle = ascii("<svg")
    val limit = minOf(bytes.size, 1024) - needle.size
    if (limit >= 0) {
        for (i in 0..limit) {
            var hit = true
            for (j in needle.indices) if (bytes[i + j] != needle[j]) { hit = false; break }
            if (hit) return SniffedImage.SVG
        }
    }
    return null
}

@Serializable
data class ArtworkStatus(
    val posterExists: Boolean,
    val fanartExists: Boolean,
    val logoExists: Boolean = false,
    // Phase 151: true when the on-disk file was explicitly picked/uploaded by an operator (`.manual`
    // marker sidecar) and is therefore never touched by an automatic download again.
    val posterManual: Boolean = false,
    val fanartManual: Boolean = false,
    val logoManual: Boolean = false,
)

@Serializable
data class EpisodeStillStatus(
    val stillExists: Boolean,
    val stillPath: String,
    val source: String? = null,  // R131: "tmdb" | "screengrab" | "manual" | null — from the .src sidecar
    val manual: Boolean = false, // Phase 151: operator-chosen — never auto-replaced (`.manual` marker)
)

/**
 * Phase 168 follow-up fix: a MOVIE/TV_SHOW keeps a fixed filename (`poster.jpg` etc.) inside the
 * item's own directory — safe, since each normally has its own folder. A MUSIC_VIDEO usually
 * **shares** its folder with sibling files (confirmed against a real library: an artist's folder
 * held 4+ music videos), so a fixed `poster.jpg` there is either nobody's poster or the wrong one.
 * Real pre-existing artwork for these files was already sitting on disk under the same per-basename
 * convention `episodeStillPath`/`NfoWriter.nfoPath` use elsewhere in this codebase (and that this
 * house's own library already had files named exactly this way) — `<video-basename>-poster.jpg` —
 * but nothing in this file ever looked there before this fix, so it was invisible to jellystructure
 * (and to Ravilo, which serves artwork from this exact path via `RaviloArtworkService`).
 */
internal fun assetFilePath(item: MediaItem, filename: String): String = when (item.kind) {
    MediaKind.MUSIC_VIDEO -> "${item.path.substringBeforeLast('.')}-$filename"
    MediaKind.MOVIE -> "${item.path.substringBeforeLast('/')}/$filename"
    MediaKind.TV_SHOW -> "${item.path}/$filename"
}

/** R122: true when a `poster.jpg` artwork file exists on disk for [item] — the real (Jellyfin) poster
 *  image, as opposed to the TMDB `posterPath` metadata. Drives the Library "missing artwork" filter,
 *  so it counts manually-added artwork and excludes TMDB-matched items whose poster never downloaded. */
fun posterArtworkExists(item: MediaItem): Boolean =
    SystemFileSystem.exists(Path(assetFilePath(item, "poster.jpg")))

/**
 * Phase 176: pure staleness decision, split out from [ArtworkDownloader] so it's testable without a
 * real filesystem/TmdbClient/Screengrabber — same shape as [preserveLockedArtwork] and `clearTmdbMatch`.
 *
 * A file is stale when it is NOT manually locked, DOES carry a recorded `.src`, and that recorded value
 * disagrees with the item's current expected source (`posterPath`/`backdropPath`). A manual file is never
 * stale (Phase 151 always wins); a file with no `.src` at all (pre-dates this phase) is trusted, not
 * flagged — this deliberately avoids a mass one-time re-download across an existing library.
 */
fun isStaleArtworkSrc(recordedSrc: String?, expectedSrc: String?, manual: Boolean): Boolean =
    !manual && recordedSrc != null && expectedSrc != null && recordedSrc != expectedSrc

class ArtworkDownloader(private val tmdbClient: TmdbClient, private val screengrabber: Screengrabber) {
    // Phase 129 (FR-OPS1 §B.1) — shared client, one idle connection pool for all outbound callers.
    private val http = OutboundHttp.client

    fun check(item: MediaItem): ArtworkStatus {
        val poster = assetFilePath(item, "poster.jpg")
        val fanart = assetFilePath(item, "fanart.jpg")
        val logo = assetFilePath(item, "clearlogo.png")
        return ArtworkStatus(
            posterExists = SystemFileSystem.exists(Path(poster)),
            fanartExists = SystemFileSystem.exists(Path(fanart)),
            logoExists = SystemFileSystem.exists(Path(logo)),
            posterManual = isManual(poster),
            fanartManual = isManual(fanart),
            logoManual = isManual(logo),
        )
    }

    // --- Phase 151 (FR-ART2): the manual-artwork marker -------------------------------------------
    //
    // "<image>.manual" written next to an image whenever an operator explicitly picks, uploads or
    // generates it. Every automatic writer (the scan pipeline's download_artwork step, a TMDB re-pull,
    // realtime ingest, the legacy misplaced-artwork cleanup) must leave a marked file alone.
    //
    // A sidecar rather than a MediaItem field because it covers the assets that have no field to lock —
    // clearlogo, season posters and episode stills — and, living with the media, it survives a DB reset.
    // The item-level poster/backdrop additionally carry `MediaItem.lockedArtwork` (Phase 133), which is
    // what keeps the *metadata* path from reverting; this marker is what protects the *file*.

    private fun manualMarkerPath(imagePath: String) = "$imagePath.manual"

    /** True when [imagePath] was explicitly chosen by an operator and must never be auto-overwritten. */
    fun isManual(imagePath: String): Boolean = SystemFileSystem.exists(Path(manualMarkerPath(imagePath)))

    /** Marks [imagePath] as operator-chosen. Idempotent; failures are non-fatal (the image itself is
     *  already written, and skip-if-present still shields it from the ordinary gap-fill download). */
    fun markManual(imagePath: String) {
        runCatching { FileIo.writeText(Path(manualMarkerPath(imagePath)), "1") }
    }

    fun isAssetManual(item: MediaItem, asset: String): Boolean =
        assetPath(item, asset)?.let { isManual(it) } ?: false

    fun markAssetManual(item: MediaItem, asset: String) {
        assetPath(item, asset)?.let { markManual(it) }
    }

    fun isSeasonPosterManual(item: MediaItem, season: Int): Boolean = isManual(seasonPosterPath(item, season))

    fun isEpisodeStillManual(episode: Episode): Boolean = isManual(episodeStillPath(episode))

    suspend fun fetch(item: MediaItem): ArtworkStatus {
        val poster = assetFilePath(item, "poster.jpg")
        val fanart = assetFilePath(item, "fanart.jpg")
        val logo = assetFilePath(item, "clearlogo.png")
        var posterExists = SystemFileSystem.exists(Path(poster))
        var fanartExists = SystemFileSystem.exists(Path(fanart))
        val logoExists = SystemFileSystem.exists(Path(logo))

        // Phase 176: skip-if-present alone only protects an operator's chosen image (Phase 151's
        // `.manual` marker) — it can't tell a file left over from a SUPERSEDED match from a genuinely
        // current one, since presence is all it ever checked. A non-manual file whose recorded `.src`
        // (the TMDB file_path it was downloaded from) disagrees with the item's CURRENT posterPath/
        // backdropPath is stale: delete it first so the block below re-downloads from the new path,
        // exactly as if the slot had been empty. A file with no `.src` sidecar (pre-dates this phase)
        // is trusted as-is — see the spec for why this deliberately isn't a mass one-time re-download.
        if (posterExists && isStaleAutoAsset(item, "poster", item.posterPath)) {
            deleteIfExists(poster); deleteIfExists("$poster.src"); posterExists = false
        }
        if (fanartExists && isStaleAutoAsset(item, "backdrop", item.backdropPath)) {
            deleteIfExists(fanart); deleteIfExists("$fanart.src"); fanartExists = false
        }

        val posterOk: Boolean
        val fanartOk: Boolean
        coroutineScope {
            val posterJob = if (!posterExists && !item.posterPath.isNullOrBlank()) {
                async { download("$TMDB_ORIGINAL${item.posterPath}", poster).also { if (it) writeAssetSrc(item, "poster", item.posterPath) } }
            } else null
            val fanartJob = if (!fanartExists && !item.backdropPath.isNullOrBlank()) {
                async { download("$TMDB_ORIGINAL${item.backdropPath}", fanart).also { if (it) writeAssetSrc(item, "backdrop", item.backdropPath) } }
            } else null
            posterOk = posterJob?.await() ?: posterExists
            fanartOk = fanartJob?.await() ?: fanartExists
        }

        // Phase 192 (FR-192-4) — the logo joins poster/backdrop as an automatically-fetched asset. Like
        // a season poster (R126) it has no stored path on the item to key off, so pull the TMDB images
        // gallery and pick one: the resolved-language tier first, then textless, then anything at all —
        // and within whichever tier wins, skip any SVG candidate when a raster one of the same tier
        // exists (FR-192-3's rasterise path is the exception, not the norm). Never runs when a logo is
        // already on disk, manual or not — a `.manual` logo is protected by that alone, identically to
        // poster/backdrop above.
        var logoOk = logoExists
        if (!logoExists) {
            val tid = item.tmdbId
            if (tid != null) {
                val images = runCatching {
                    if (item.kind == MediaKind.TV_SHOW) tmdbClient.getTvImages(tid) else tmdbClient.getMovieImages(tid)
                }.getOrNull()
                val candidates = images?.logos.orEmpty()
                val tier = candidates.filter { it.languageCode == item.resolvedLanguage }
                    .ifEmpty { candidates.filter { it.languageCode == null } }
                    .ifEmpty { candidates }
                val (raster, svg) = tier.partition { !it.filePath.endsWith(".svg", ignoreCase = true) }
                val pick = raster.ifEmpty { svg }.maxByOrNull { it.voteAverage }
                if (pick != null) {
                    logoOk = download("$TMDB_ORIGINAL${pick.filePath}", logo)
                    if (logoOk) writeAssetSrc(item, "clearlogo", pick.filePath)
                }
            }
        }

        // For TV shows: clean up any artwork that was previously written to the wrong
        // location (parent of the series directory) due to the substringBeforeLast('/') bug.
        // Only delete the old file once the correct-path file is confirmed present.
        if (item.kind == MediaKind.TV_SHOW) {
            val oldDir = item.path.substringBeforeLast('/')
            // Phase 151: never sweep away an image an operator placed/picked deliberately — `oldDir` is
            // the PARENT of the series directory, i.e. usually the library root, where a hand-picked
            // library-level image can legitimately live.
            if (oldDir != item.path) {
                if (posterOk && !isManual("$oldDir/poster.jpg")) deleteIfExists("$oldDir/poster.jpg")
                if (fanartOk && !isManual("$oldDir/fanart.jpg")) deleteIfExists("$oldDir/fanart.jpg")
                if (logoOk && !isManual("$oldDir/clearlogo.png")) deleteIfExists("$oldDir/clearlogo.png")
            }
            // R125: episode stills are part of fetch() now — download any missing (each from the
            // episode's stored stillPath), bounded by the shared download gate. So every fetch()
            // caller (scan-pipeline "Download artwork" + the per-item fetch) populates stills too.
            if (item.episodes.isNotEmpty()) coroutineScope {
                item.episodes.forEach { ep -> launch { runCatching { fetchEpisodeStill(ep) } } }
            }
            // R126: season posters — for each season we actually have on disk, download a missing one.
            // Unlike poster/fanart/stills the chosen season poster isn't stored, so pull the season's
            // TMDB images and pick the best (resolved-language, then highest-voted).
            val tid = item.tmdbId
            if (tid != null) for (season in item.episodes.mapNotNull { it.seasonNumber }.distinct()) {
                if (checkSeasonPoster(item, season)) continue
                val posters = runCatching { tmdbClient.getSeasonImages(tid, season)?.posters }.getOrNull().orEmpty()
                val pick = posters.filter { it.languageCode == item.resolvedLanguage }.ifEmpty { posters }
                    .maxByOrNull { it.voteAverage }
                // Phase 151: manual = false — this is the automatic gap-fill, not an operator's pick.
                if (pick != null) runCatching { saveSeasonPoster(item, season, pick.filePath, manual = false) }
            }
        }

        return ArtworkStatus(
            posterExists = posterOk,
            fanartExists = fanartOk,
            logoExists = logoOk,
            posterManual = isManual(poster),
            fanartManual = isManual(fanart),
            logoManual = isManual(logo),
        )
    }

    /** R126: true if any artwork the "Download artwork" step can fetch is missing on disk — poster/fanart
     *  for everything, plus episode stills + season posters for series. Drives the pipeline "missing" scope. */
    fun isArtworkIncomplete(item: MediaItem): Boolean {
        val st = check(item)
        if (!st.posterExists || !st.fanartExists) return true
        // Phase 176: a poster/backdrop left over from a SUPERSEDED TMDB match is present but wrong —
        // without this, a "Download artwork (missing)" scoped run would never even look at the item,
        // since presence alone made it look complete. Same "the fix reaches every trigger" concern
        // fetch()'s own stale check (above) exists for.
        if (isStaleAutoAsset(item, "poster", item.posterPath)) return true
        if (isStaleAutoAsset(item, "backdrop", item.backdropPath)) return true
        // Phase 192 (FR-192-4) — the logo joins this check exactly like poster/fanart; gated on tmdbId
        // since there's nothing to fetch without a match, matching fetch()'s own guard. Applies to
        // movies too, so this sits above the TV-only early return below.
        if (!st.logoExists && item.tmdbId != null) return true
        if (item.kind != MediaKind.TV_SHOW) return false
        // R131: a still is "incomplete" when missing OR a screen-grab that TMDB can now upgrade — so the
        // next scheduled "Download artwork (missing)" run re-processes the series and swaps in the real still.
        // Phase 151: a still an operator chose (uploaded/picked, or generated from a frame in the picker)
        // is NOT "incomplete" — without this a locked screen-grab would mark its series incomplete on
        // every run forever, since fetchEpisodeStill now refuses to upgrade it.
        if (item.episodes.any { ep ->
            val st = checkEpisodeStill(ep)
            !st.stillExists || (st.source == "screengrab" && !ep.stillPath.isNullOrBlank() && !st.manual)
        }) return true
        return item.episodes.mapNotNull { it.seasonNumber }.distinct().any { !checkSeasonPoster(item, it) }
    }

    private suspend fun deleteIfExists(path: String) {
        val p = Path(path)
        if (!SystemFileSystem.exists(p)) return
        val result = runCatching { SystemFileSystem.delete(p) }
        if (result.isSuccess) Logger.info("Removed misplaced artwork: $path")
        else Logger.warn("Could not remove misplaced artwork $path: ${result.exceptionOrNull()?.message}")
    }

    private suspend fun download(url: String, destPath: String): Boolean {
        // Security fix (2026-08-02 review, finding M5) — see UrlSafety's doc comment. `source` here can
        // be an admin-pasted URL (the Artwork manager's "pick from URL" flow); without this check the
        // server would fetch and WRITE TO DISK, then re-serve via /api/tv/image/**, whatever an
        // attacker-controlled admin session (or a stolen cookie) pointed it at — a full-read SSRF.
        if (!dev.jellystructure.util.UrlSafety.isSafeExternalUrl(url)) {
            Logger.warn("Refusing to download artwork from disallowed URL: $url", "artwork")
            return false
        }
        return downloadUnchecked(url, destPath)
    }

    // Phase 192 (FR-192-1) — a status-2xx, content-type-image/* response can still be a CDN error page
    // (the live incident this fixes: a 504 Gateway Timeout HTML page, served with neither of those two
    // things wrong, was written to disk as clearlogo.png and stayed there for weeks — nothing after this
    // point ever re-checked it). All three checks must pass before a single byte reaches disk; a reject
    // writes nothing at all, not even a `.tmp`.
    private suspend fun downloadUnchecked(url: String, destPath: String): Boolean = OutboundHttp.withPermit {
        val result = runCatching {
            val response = http.get(url)
            if (!response.status.isSuccess()) {
                Logger.warn("Rejected artwork download (HTTP ${response.status.value}): $url", "artwork")
                return@withPermit false
            }
            val contentType = response.contentType()
            if (contentType?.contentType != "image") {
                Logger.warn("Rejected artwork download (Content-Type ${contentType ?: "<none>"}): $url", "artwork")
                return@withPermit false
            }
            val bytes = response.readRawBytes()
            if (bytes.isEmpty()) return@withPermit false
            val sniffed = sniffImageSignature(bytes)
            if (sniffed == null) {
                Logger.warn("Rejected artwork download (unrecognized image signature, ${bytes.size} bytes): $url", "artwork")
                return@withPermit false
            }

            if (sniffed == SniffedImage.SVG && destPath.endsWith(".png")) {
                // FR-192-3 — a saved logo must be a real PNG matching its filename. resizeImage operates
                // on files, so the SVG bytes need a throwaway path to rasterise FROM; the throwaway is
                // always removed below, success or failure, so nothing but the final PNG (or nothing at
                // all) survives this branch.
                val tmpSvg = "$destPath.svg.tmp"
                val rasterTmp = "$destPath.tmp"
                FileIo.writeBytes(Path(tmpSvg), bytes)
                val rasterOk = runCatching { FfmpegRunner.resizeImage(tmpSvg, rasterTmp, height = 300) }.getOrDefault(false)
                deleteIfExists(tmpSvg)
                if (!rasterOk) {
                    deleteIfExists(rasterTmp)
                    Logger.warn("Rejected artwork download (SVG rasterise failed): $url", "artwork")
                    return@withPermit false
                }
                platform.posix.rename(rasterTmp, destPath)
                Logger.info("Downloaded + rasterised SVG artwork: $destPath", "artwork")
                return@withPermit true
            }

            val tmp = "$destPath.tmp"
            FileIo.writeBytes(Path(tmp), bytes)   // Phase 134: use{}-scoped — no FD leak on a mid-write throw
            platform.posix.rename(tmp, destPath)
            Logger.info("Downloaded artwork: $destPath", "artwork")
            true
        }
        if (result.isFailure) Logger.warn("Failed to download $url: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(false)
    }

    fun checkEpisodeStill(episode: Episode): EpisodeStillStatus {
        val destPath = episodeStillPath(episode)
        val exists = SystemFileSystem.exists(Path(destPath))
        return EpisodeStillStatus(
            stillExists = exists,
            stillPath = destPath,
            source = if (exists) readStillSrc(destPath) else null,
            manual = exists && isManual(destPath),
        )
    }

    suspend fun fetchEpisodeStill(episode: Episode): EpisodeStillStatus {
        val destPath = episodeStillPath(episode)
        val stillUrl = episode.stillPath
        if (SystemFileSystem.exists(Path(destPath))) {
            val src = readStillSrc(destPath)
            val manual = isManual(destPath)
            // R131: a screen-grab is the lowest priority — once TMDB has a real still, upgrade to it.
            // Phase 151: …unless the operator chose that frame themselves (picker "Generate from frame"),
            // in which case it's locked like any other manual pick.
            if (src == "screengrab" && !stillUrl.isNullOrBlank() && !manual) {
                val ok = download("$TMDB_ORIGINAL$stillUrl", destPath)
                if (ok) writeStillSrc(destPath, "tmdb")
                return EpisodeStillStatus(stillExists = true, stillPath = destPath, source = if (ok) "tmdb" else src)
            }
            return EpisodeStillStatus(stillExists = true, stillPath = destPath, source = src, manual = manual)
        }
        // Nothing on disk: prefer the real TMDB still; otherwise grab a frame as a placeholder.
        if (!stillUrl.isNullOrBlank()) {
            val ok = download("$TMDB_ORIGINAL$stillUrl", destPath)
            if (ok) writeStillSrc(destPath, "tmdb")
            return EpisodeStillStatus(stillExists = ok, stillPath = destPath, source = if (ok) "tmdb" else null)
        }
        val ok = screengrabber.grabEpisodeStill(episode, destPath)
        if (ok) writeStillSrc(destPath, "screengrab")
        return EpisodeStillStatus(stillExists = ok, stillPath = destPath, source = if (ok) "screengrab" else null)
    }

    /** R131: regenerate a screen-grab still on demand (the picker's "Generate from frame" button). Keeps
     *  the `screengrab` source label, but (Phase 151) locks the file — an operator pressing the button is
     *  choosing this frame, so no automatic run may upgrade it to TMDB's still behind their back. */
    suspend fun screengrabEpisodeStill(episode: Episode): EpisodeStillStatus {
        val destPath = episodeStillPath(episode)
        val ok = screengrabber.grabEpisodeStill(episode, destPath)
        if (ok) {
            writeStillSrc(destPath, "screengrab")
            // Phase 151: pressing "Generate from frame" IS an explicit operator choice — keep the source
            // as `screengrab` (so the picker still labels it honestly) but lock the file so the next
            // "Download artwork" run can't silently swap in TMDB's still.
            markManual(destPath)
        }
        return EpisodeStillStatus(
            stillExists = ok || SystemFileSystem.exists(Path(destPath)),
            stillPath = destPath,
            source = if (ok) "screengrab" else readStillSrc(destPath),
            manual = isManual(destPath),
        )
    }

    // R131: provenance sidecar next to each still ("<base>-thumb.jpg.src"), mirroring the image-proxy `.ct`.
    // Phase 134: this was the incident's leak — called per episode on every scan/rescan pass, and the
    // old `source(...).buffered().readString()` never closed the fd.
    private fun readStillSrc(destPath: String): String? = runCatching {
        FileIo.readText(Path("$destPath.src")).trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun writeStillSrc(destPath: String, source: String) {
        runCatching { FileIo.writeText(Path("$destPath.src"), source) }
    }

    /** Phase 121: stamps `Episode.hasStill` from an on-disk check so triage/Library filtering can stay
     *  O(1) in-memory instead of statting the filesystem per request. Call wherever a scan/rescan/sync
     *  (re)builds a TV show's episode list, right before the result is persisted. No-op for movies. */
    fun stampHasStill(item: MediaItem): MediaItem {
        if (item.kind != MediaKind.TV_SHOW || item.episodes.isEmpty()) return item
        return item.copy(episodes = item.episodes.map { ep -> ep.copy(hasStill = checkEpisodeStill(ep).stillExists) })
    }

    fun episodeStillPath(episode: Episode): String {  // R133: public so RaviloArtworkService can resolve stills
        val dir = episode.path.substringBeforeLast('/')
        val baseName = episode.filename.substringBeforeLast('.')
        // Bug fix (Phase 149 dev-review addendum §4): a multi-episode file's N episodes all share
        // path/filename, so keying purely off baseName collided every episode in the group onto the
        // SAME disk path — fetching E02's still silently overwrote E01's. Fold the episode number into
        // the filename whenever the file has more than one contained episode; a normal single-episode
        // file (the overwhelming majority) keeps today's exact path unchanged, so nothing needs
        // migrating for existing libraries.
        val suffix = if (episode.partCount > 1) "-e${episode.episodeNumber ?: episode.partIndex}" else ""
        return "$dir/$baseName-thumb$suffix.jpg"
    }

    private fun mediaDir(item: MediaItem) = when (item.kind) {
        MediaKind.MOVIE, MediaKind.MUSIC_VIDEO -> item.path.substringBeforeLast('/')
        MediaKind.TV_SHOW -> item.path  // item.path IS the series directory
    }

    // --- Phase 47: write a specific chosen candidate (TMDB file_path or full URL) ---

    /** Filename on disk for each rail asset, per the constitution. */
    private fun assetFilename(asset: String): String? = when (asset) {
        "poster" -> "poster.jpg"
        "backdrop" -> "fanart.jpg"
        "clearlogo" -> "clearlogo.png"
        else -> null
    }

    fun assetPath(item: MediaItem, asset: String): String? =
        assetFilename(asset)?.let { assetFilePath(item, it) }

    /** R214: a cheap version stamp for [asset]'s current on-disk bytes — its file size, the same signal
     *  [RaviloArtworkService]'s own resize-cache already trusts to detect a changed source file (R133's
     *  doc comment: "any artwork rewrite... changes the compressed size, so a cache entry auto-invalidates
     *  with no explicit hooks to miss"). Read fresh from disk at call time rather than a counter some
     *  writer has to remember to bump — Phase 176 already showed that kind of "remember to invalidate"
     *  gap is exactly how a stale image survives a fix. 0 when the asset doesn't exist yet. */
    fun assetVersion(item: MediaItem, asset: String): Long =
        assetPath(item, asset)?.let { SystemFileSystem.metadataOrNull(Path(it))?.size } ?: 0L

    /** R214: same version stamp as [assetVersion], for a season's own poster (R194). */
    fun seasonPosterVersion(item: MediaItem, season: Int): Long =
        SystemFileSystem.metadataOrNull(Path(seasonPosterPath(item, season)))?.size ?: 0L

    /** Read the TMDB file_path (or upload/URL sentinel) recorded for [asset]'s current on-disk file,
     *  or null if no `.src` sidecar exists — either nothing is on disk yet, or the file pre-dates
     *  Phase 176 (poster/backdrop) / Phase 47 (clearlogo) provenance tracking. */
    fun readAssetSrc(item: MediaItem, asset: String): String? =
        assetPath(item, asset)?.let { p -> runCatching { FileIo.readText(Path("$p.src")).trim() }.getOrNull()?.takeIf { it.isNotBlank() } }

    /** Record the source (TMDB file_path, or an "upload"/pasted-URL sentinel) that produced [asset]'s
     *  current on-disk file — Phase 176 extended this from clearlogo-only to poster/backdrop too, so
     *  staleness (a file left over from a superseded TMDB match) can be detected. */
    fun writeAssetSrc(item: MediaItem, asset: String, source: String?) {
        if (source.isNullOrBlank()) return
        assetPath(item, asset)?.let { p -> runCatching { FileIo.writeText(Path("$p.src"), source) } }
    }

    /** Phase 176: true when [asset]'s on-disk file is non-manual and its recorded `.src` no longer
     *  matches [expectedSrc] (the item's CURRENT posterPath/backdropPath) — i.e. it belongs to a match
     *  this item no longer has. No `.src` sidecar (pre-dates this phase) is trusted, not flagged stale. */
    private fun isStaleAutoAsset(item: MediaItem, asset: String, expectedSrc: String?): Boolean =
        isStaleArtworkSrc(readAssetSrc(item, asset), expectedSrc, isAssetManual(item, asset))

    /** `source` is either a TMDB file_path (leading "/") or a full http(s) URL.
     *  Phase 151: [manual] marks the result operator-chosen (the default — every caller is an explicit
     *  pick today), so no automatic path overwrites it afterwards. */
    suspend fun saveAsset(item: MediaItem, asset: String, source: String, manual: Boolean = true): Boolean {
        val dest = assetPath(item, asset) ?: return false
        val ok = download(toUrl(source), dest)
        if (ok && manual) markManual(dest)
        return ok
    }

    /** Phase 174: removes an on-disk asset entirely — the image itself, its `.manual` lock marker (if
     *  any) and its `.src` provenance sidecar (if any), so nothing is left to re-appear or confuse a
     *  later "is this manual" check. Used by the "Clear TMDB match" action (a wrong match's downloaded
     *  poster/backdrop had no removal path at all before this) and available standalone for any asset
     *  an operator simply wants gone. Returns true when an image was actually there to remove (the
     *  sidecars are best-effort either way), so a caller can tell a real removal from a no-op. */
    suspend fun clearAsset(item: MediaItem, asset: String): Boolean {
        val dest = assetPath(item, asset) ?: return false
        val existed = SystemFileSystem.exists(Path(dest))
        deleteIfExists(dest)
        deleteIfExists(manualMarkerPath(dest))
        deleteIfExists("$dest.src")
        return existed
    }

    /**
     * Phase 192 (FR-192-2) — a one-time maintenance sweep for artwork saved before [downloadUnchecked]'s
     * validation existed: check every known artwork path for [item] against its magic bytes (not its
     * status/content-type at download time, both long gone) and remove — image, `.manual` lock, `.src`
     * sidecar — any file that isn't a real image. The `.manual` marker must go too, or the repair is
     * undone by the very lock that was protecting the corrupt file. Read-only when everything checks
     * out (the overwhelming common case); a fresh `fetch_artwork` run repopulates whatever was removed.
     * Returns the removed paths so the caller can log/record History per item.
     */
    suspend fun repairCorruptArtwork(item: MediaItem): List<String> {
        val candidates = buildList {
            add(assetFilePath(item, "poster.jpg"))
            add(assetFilePath(item, "fanart.jpg"))
            add(assetFilePath(item, "clearlogo.png"))
            if (item.kind == MediaKind.TV_SHOW) {
                addAll(item.episodes.mapNotNull { it.seasonNumber }.distinct().map { seasonPosterPath(item, it) })
                addAll(item.episodes.map { episodeStillPath(it) })
            }
        }
        val removed = mutableListOf<String>()
        for (path in candidates) {
            val p = Path(path)
            if (!SystemFileSystem.exists(p)) continue
            val bytes = runCatching { FileIo.readBytes(p) }.getOrNull() ?: continue
            if (sniffImageSignature(bytes) != null) continue
            Logger.warn("Removing corrupt artwork (not a recognized image): $path", "artwork")
            deleteIfExists(path)
            deleteIfExists(manualMarkerPath(path))
            deleteIfExists("$path.src")
            removed += path
        }
        return removed
    }

    /** Jellyfin local naming for a season poster at the series root. R194: `internal`, not `private` —
     *  `RaviloArtworkService` (same module, `dev.jellystructure.tv`) needs it to serve season posters. */
    internal fun seasonPosterPath(item: MediaItem, season: Int): String {
        val name = if (season == 0) "season-specials-poster.jpg"
        else "season${season.toString().padStart(2, '0')}-poster.jpg"
        return "${mediaDir(item)}/$name"
    }

    fun checkSeasonPoster(item: MediaItem, season: Int): Boolean =
        SystemFileSystem.exists(Path(seasonPosterPath(item, season)))

    /** Phase 151: [manual] is false only for the automatic gap-fill inside [fetch]. */
    suspend fun saveSeasonPoster(item: MediaItem, season: Int, source: String, manual: Boolean = true): Boolean {
        val dest = seasonPosterPath(item, season)
        val ok = download(toUrl(source), dest)
        if (ok && manual) markManual(dest)
        return ok
    }

    suspend fun saveEpisodeStill(episode: Episode, source: String): Boolean {
        val dest = episodeStillPath(episode)
        val ok = download(toUrl(source), dest)
        if (ok) {
            writeStillSrc(dest, "manual")  // R131: a manual pick is permanent — never auto-upgraded
            markManual(dest)              // Phase 151: same lock every other manual asset carries
        }
        return ok
    }

    private fun toUrl(source: String): String =
        if (source.startsWith("http://") || source.startsWith("https://")) source else "$TMDB_ORIGINAL$source"
}
