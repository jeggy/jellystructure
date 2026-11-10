package dev.jellystructure.ravilo.ui.downloads

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import dev.jellystructure.ravilo.ui.RaviloAppContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * DEB-4072 (DEB-3995 FR-16) — which stream URLs are a download's, so [dev.jellystructure.ravilo.ui.seams.RaviloPlayer] opens them through the
 * download's own cache and never the network. The URL is the one the downloader read (Media3's index keeps the latest, renewed ticket's);
 * the cache key does not depend on it (see [RaviloDownloadStack]).
 */
object DownloadedPlays {
    private val byUrl = ConcurrentHashMap<String, String>()
    fun register(url: String, downloadId: String) { byUrl[url] = downloadId }
    /** The download id a play of [url] reads from, or null: an ordinary stream. */
    fun idFor(url: String): String? = byUrl[url]
}

/** What is saved beside a download, read for an offline play: the stream from Media3's index, the text subtitles from the sidecar folder. */
@UnstableApi
class AndroidLocalMedia : LocalMediaSource {
    private val json = Json { ignoreUnknownKeys = true }

    override fun read(userId: String, itemId: String): SavedDownload? {
        val ctx = RaviloAppContext.get()
        val id = downloadId(userId, itemId)
        val request = runCatching { RaviloDownloadStack.manager(ctx).downloadIndex.getDownload(id)?.request }.getOrNull() ?: return null
        val url = request.uri.toString()
        DownloadedPlays.register(url, id)
        val dir = DownloadSidecars.dir(userId, itemId)
        val subs = runCatching {
            val f = File(dir, "subs.json")
            if (!f.isFile) emptyList() else json.decodeFromString(ListSerializer(DownloadSidecars.SavedSubtitle.serializer()), f.readText())
        }.getOrDefault(emptyList()).mapNotNull { s ->
            File(dir, s.file).takeIf { it.isFile && it.length() > 0 }?.let { SavedSubtitleFile(s.index, s.language, s.label, s.forced, s.isDefault, it.toURI().toString()) }
        }
        return SavedDownload(kind = if (request.mimeType == MimeTypes.APPLICATION_M3U8) "hls" else "file", url = url, subtitles = subs)
    }

    override fun artwork(userId: String, itemId: String, type: String): String? =
        File(DownloadSidecars.dir(userId, itemId), type).takeIf { it.isFile && it.length() > 0 }?.toURI()?.toString()
}
