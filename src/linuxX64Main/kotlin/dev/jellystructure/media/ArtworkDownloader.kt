package dev.jellystructure.media

import dev.jellystructure.log.Logger
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.io.buffered
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
data class EpisodeStillStatus(val stillExists: Boolean, val stillPath: String)

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

class ArtworkDownloader {
    private val downloadGate = Semaphore(8)
    private val http = HttpClient(Curl) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis  = 120_000
            requestTimeoutMillis = 120_000
        }
    }

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
        }

        return ArtworkStatus(posterExists = posterOk, fanartExists = fanartOk, logoExists = logoExists)
    }

    private suspend fun deleteIfExists(path: String) {
        val p = Path(path)
        if (!SystemFileSystem.exists(p)) return
        val result = runCatching { SystemFileSystem.delete(p) }
        if (result.isSuccess) Logger.info("Removed misplaced artwork: $path")
        else Logger.warn("Could not remove misplaced artwork $path: ${result.exceptionOrNull()?.message}")
    }

    private suspend fun download(url: String, destPath: String): Boolean = downloadGate.withPermit {
        val result = runCatching {
            val bytes = http.get(url).readRawBytes()
            if (bytes.isEmpty()) return@withPermit false
            val tmp = "$destPath.tmp"
            val sink = SystemFileSystem.sink(Path(tmp)).buffered()
            sink.write(bytes, 0, bytes.size)
            sink.flush()
            sink.close()
            platform.posix.rename(tmp, destPath)
            Logger.info("Downloaded artwork: $destPath", "artwork")
            true
        }
        if (result.isFailure) Logger.warn("Failed to download $url: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(false)
    }

    fun checkEpisodeStill(episode: Episode): EpisodeStillStatus {
        val destPath = episodeStillPath(episode)
        return EpisodeStillStatus(
            stillExists = SystemFileSystem.exists(Path(destPath)),
            stillPath = destPath,
        )
    }

    suspend fun fetchEpisodeStill(episode: Episode): EpisodeStillStatus {
        val destPath = episodeStillPath(episode)
        val stillUrl = episode.stillPath
        return if (!stillUrl.isNullOrBlank() && !SystemFileSystem.exists(Path(destPath))) {
            val ok = download("$TMDB_ORIGINAL$stillUrl", destPath)
            EpisodeStillStatus(stillExists = ok, stillPath = destPath)
        } else {
            EpisodeStillStatus(stillExists = SystemFileSystem.exists(Path(destPath)), stillPath = destPath)
        }
    }

    private fun episodeStillPath(episode: Episode): String {
        val dir = episode.path.substringBeforeLast('/')
        val baseName = episode.filename.substringBeforeLast('.')
        return "$dir/$baseName-thumb.jpg"
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
        "banner" -> "banner.jpg"
        else -> null
    }

    fun assetPath(item: MediaItem, asset: String): String? =
        assetFilename(asset)?.let { "${mediaDir(item)}/$it" }

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

    suspend fun saveEpisodeStill(episode: Episode, source: String): Boolean =
        download(toUrl(source), episodeStillPath(episode))

    private fun toUrl(source: String): String =
        if (source.startsWith("http://") || source.startsWith("https://")) source else "$TMDB_ORIGINAL$source"
}
