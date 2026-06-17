package dev.jellystructure.media

import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

class ArtworkDownloader {
    private val http = HttpClient(Curl)

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
        }

        return ArtworkStatus(posterExists = posterOk, fanartExists = fanartOk, logoExists = logoExists)
    }

    private fun deleteIfExists(path: String) {
        val p = Path(path)
        if (SystemFileSystem.exists(p)) {
            runCatching { SystemFileSystem.delete(p) }
                .onSuccess { println("[INFO] Removed misplaced artwork: $path") }
                .onFailure { println("[WARN] Could not remove misplaced artwork $path: ${it.message}") }
        }
    }

    private suspend fun download(url: String, destPath: String): Boolean = runCatching {
        val bytes = http.get(url).readRawBytes()
        if (bytes.isEmpty()) return false
        val tmp = "$destPath.tmp"
        val sink = SystemFileSystem.sink(Path(tmp)).buffered()
        sink.write(bytes, 0, bytes.size)
        sink.flush()
        sink.close()
        platform.posix.rename(tmp, destPath)
        println("[INFO] Downloaded artwork: $destPath")
        true
    }.onFailure { println("[WARN] Failed to download $url: ${it.message}") }
     .getOrDefault(false)

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
}
