package dev.jellystructure.media

import dev.jellystructure.model.MediaItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable

private const val TMDB_ORIGINAL = "https://image.tmdb.org/t/p/original"

@Serializable
data class ArtworkStatus(val posterExists: Boolean, val fanartExists: Boolean)

class ArtworkDownloader {
    private val http = HttpClient(Curl)

    fun check(item: MediaItem): ArtworkStatus {
        val dir = mediaDir(item)
        return ArtworkStatus(
            posterExists = SystemFileSystem.exists(Path("$dir/poster.jpg")),
            fanartExists = SystemFileSystem.exists(Path("$dir/fanart.jpg")),
        )
    }

    suspend fun fetch(item: MediaItem): ArtworkStatus {
        if (item.languageMix) {
            println("[INFO] Artwork download skipped for '${item.title}': language mix detected")
            return check(item)
        }
        val dir = mediaDir(item)
        var posterOk = SystemFileSystem.exists(Path("$dir/poster.jpg"))
        var fanartOk = SystemFileSystem.exists(Path("$dir/fanart.jpg"))

        if (!posterOk && !item.posterPath.isNullOrBlank()) {
            posterOk = download("$TMDB_ORIGINAL${item.posterPath}", "$dir/poster.jpg")
        }
        if (!fanartOk && !item.backdropPath.isNullOrBlank()) {
            fanartOk = download("$TMDB_ORIGINAL${item.backdropPath}", "$dir/fanart.jpg")
        }
        return ArtworkStatus(posterExists = posterOk, fanartExists = fanartOk)
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

    private fun mediaDir(item: MediaItem) = item.path.substringBeforeLast('/')
}
