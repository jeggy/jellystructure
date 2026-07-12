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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable

private const val TMDB_ORIGINAL = "https://image.tmdb.org/t/p/original"

@Serializable
data class ArtworkStatus(
    val posterExists: Boolean,
    val fanartExists: Boolean,
    val logoExists: Boolean = false,
)

@Serializable
data class EpisodeStillStatus(
    val stillExists: Boolean,
    val stillPath: String,
    val source: String? = null,  // R131: "tmdb" | "screengrab" | "manual" | null — from the .src sidecar
)

/** R122: true when a `poster.jpg` artwork file exists on disk for [item] — the real (Jellyfin) poster
 *  image, as opposed to the TMDB `posterPath` metadata. Drives the Library "missing artwork" filter,
 *  so it counts manually-added artwork and excludes TMDB-matched items whose poster never downloaded. */
fun posterArtworkExists(item: MediaItem): Boolean {
    val dir = when (item.kind) {
        MediaKind.MOVIE -> item.path.substringBeforeLast('/')
        MediaKind.TV_SHOW -> item.path
    }
    return SystemFileSystem.exists(Path("$dir/poster.jpg"))
}

class ArtworkDownloader(private val tmdbClient: TmdbClient, private val screengrabber: Screengrabber) {
    // Phase 129 (FR-OPS1 §B.1) — shared client, one idle connection pool for all outbound callers.
    private val http = OutboundHttp.client

    fun check(item: MediaItem): ArtworkStatus {
        val dir = mediaDir(item)
        return ArtworkStatus(
            posterExists = SystemFileSystem.exists(Path("$dir/poster.jpg")),
            fanartExists = SystemFileSystem.exists(Path("$dir/fanart.jpg")),
            logoExists = SystemFileSystem.exists(Path("$dir/clearlogo.png")),
        )
    }

    suspend fun fetch(item: MediaItem): ArtworkStatus {
        val dir = mediaDir(item)
        val posterExists = SystemFileSystem.exists(Path("$dir/poster.jpg"))
        val fanartExists = SystemFileSystem.exists(Path("$dir/fanart.jpg"))
        val logoExists = SystemFileSystem.exists(Path("$dir/clearlogo.png"))

        val posterOk: Boolean
        val fanartOk: Boolean
        coroutineScope {
            val posterJob = if (!posterExists && !item.posterPath.isNullOrBlank()) {
                async { download("$TMDB_ORIGINAL${item.posterPath}", "$dir/poster.jpg") }
            } else null
            val fanartJob = if (!fanartExists && !item.backdropPath.isNullOrBlank()) {
                async { download("$TMDB_ORIGINAL${item.backdropPath}", "$dir/fanart.jpg") }
            } else null
            posterOk = posterJob?.await() ?: posterExists
            fanartOk = fanartJob?.await() ?: fanartExists
        }

        // For TV shows: clean up any artwork that was previously written to the wrong
        // location (parent of the series directory) due to the substringBeforeLast('/') bug.
        // Only delete the old file once the correct-path file is confirmed present.
        if (item.kind == MediaKind.TV_SHOW) {
            val oldDir = item.path.substringBeforeLast('/')
            if (oldDir != dir) {
                if (posterOk) deleteIfExists("$oldDir/poster.jpg")
                if (fanartOk) deleteIfExists("$oldDir/fanart.jpg")
                if (logoExists) deleteIfExists("$oldDir/clearlogo.png")
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
                if (pick != null) runCatching { saveSeasonPoster(item, season, pick.filePath) }
            }
        }

        return ArtworkStatus(posterExists = posterOk, fanartExists = fanartOk, logoExists = logoExists)
    }

    /** R126: true if any artwork the "Download artwork" step can fetch is missing on disk — poster/fanart
     *  for everything, plus episode stills + season posters for series. Drives the pipeline "missing" scope. */
    fun isArtworkIncomplete(item: MediaItem): Boolean {
        val st = check(item)
        if (!st.posterExists || !st.fanartExists) return true
        if (item.kind != MediaKind.TV_SHOW) return false
        // R131: a still is "incomplete" when missing OR a screen-grab that TMDB can now upgrade — so the
        // next scheduled "Download artwork (missing)" run re-processes the series and swaps in the real still.
        if (item.episodes.any { ep ->
            val st = checkEpisodeStill(ep)
            !st.stillExists || (st.source == "screengrab" && !ep.stillPath.isNullOrBlank())
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

    private suspend fun download(url: String, destPath: String): Boolean = OutboundHttp.withPermit {
        val result = runCatching {
            val bytes = http.get(url).readRawBytes()
            if (bytes.isEmpty()) return@withPermit false
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
        return EpisodeStillStatus(stillExists = exists, stillPath = destPath, source = if (exists) readStillSrc(destPath) else null)
    }

    suspend fun fetchEpisodeStill(episode: Episode): EpisodeStillStatus {
        val destPath = episodeStillPath(episode)
        val stillUrl = episode.stillPath
        if (SystemFileSystem.exists(Path(destPath))) {
            val src = readStillSrc(destPath)
            // R131: a screen-grab is the lowest priority — once TMDB has a real still, upgrade to it.
            if (src == "screengrab" && !stillUrl.isNullOrBlank()) {
                val ok = download("$TMDB_ORIGINAL$stillUrl", destPath)
                if (ok) writeStillSrc(destPath, "tmdb")
                return EpisodeStillStatus(stillExists = true, stillPath = destPath, source = if (ok) "tmdb" else src)
            }
            return EpisodeStillStatus(stillExists = true, stillPath = destPath, source = src)
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

    /** R131: regenerate a screen-grab still on demand (the picker's "Generate from frame" button). Always
     *  marks it `screengrab` (lowest priority) — a manual upload/candidate is the way to lock a custom frame. */
    suspend fun screengrabEpisodeStill(episode: Episode): EpisodeStillStatus {
        val destPath = episodeStillPath(episode)
        val ok = screengrabber.grabEpisodeStill(episode, destPath)
        if (ok) writeStillSrc(destPath, "screengrab")
        return EpisodeStillStatus(stillExists = ok || SystemFileSystem.exists(Path(destPath)), stillPath = destPath, source = if (ok) "screengrab" else readStillSrc(destPath))
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
        MediaKind.MOVIE -> item.path.substringBeforeLast('/')
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
        assetFilename(asset)?.let { "${mediaDir(item)}/$it" }

    /** Read the TMDB file_path recorded when a clearlogo candidate was picked, or null. */
    fun readAssetSrc(item: MediaItem, asset: String): String? =
        assetPath(item, asset)?.let { p -> runCatching { FileIo.readText(Path("$p.src")).trim() }.getOrNull()?.takeIf { it.isNotBlank() } }

    /** Record the TMDB file_path for the chosen clearlogo. */
    fun writeAssetSrc(item: MediaItem, asset: String, source: String) {
        assetPath(item, asset)?.let { p -> runCatching { FileIo.writeText(Path("$p.src"), source) } }
    }

    /** `source` is either a TMDB file_path (leading "/") or a full http(s) URL. */
    suspend fun saveAsset(item: MediaItem, asset: String, source: String): Boolean {
        val dest = assetPath(item, asset) ?: return false
        return download(toUrl(source), dest)
    }

    /** Jellyfin local naming for a season poster at the series root. */
    private fun seasonPosterPath(item: MediaItem, season: Int): String {
        val name = if (season == 0) "season-specials-poster.jpg"
        else "season${season.toString().padStart(2, '0')}-poster.jpg"
        return "${mediaDir(item)}/$name"
    }

    fun checkSeasonPoster(item: MediaItem, season: Int): Boolean =
        SystemFileSystem.exists(Path(seasonPosterPath(item, season)))

    suspend fun saveSeasonPoster(item: MediaItem, season: Int, source: String): Boolean =
        download(toUrl(source), seasonPosterPath(item, season))

    suspend fun saveEpisodeStill(episode: Episode, source: String): Boolean {
        val dest = episodeStillPath(episode)
        val ok = download(toUrl(source), dest)
        if (ok) writeStillSrc(dest, "manual")  // R131: a manual pick is permanent — never auto-upgraded
        return ok
    }

    private fun toUrl(source: String): String =
        if (source.startsWith("http://") || source.startsWith("https://")) source else "$TMDB_ORIGINAL$source"
}
