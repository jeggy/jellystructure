package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.shared.tv.DownloadTicket
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File

/**
 * DEB-4070 (DEB-3995 FR-7, FR-13) — what is saved **beside the media** for each download: the ticket's `item` payload verbatim (never
 * merged or edited), every text subtitle as the VTT the server sent, and the artwork the phone shows offline. One folder per viewer and
 * title: `downloads/<userId>/<itemId>/`. A file already there is not fetched again, so a resumed download costs nothing here.
 */
object DownloadSidecars {
    private val json = Json { encodeDefaults = true }

    fun dir(userId: String, itemId: String): File =
        File(File(RaviloDownloadStack.root(RaviloAppContext.get()), safe(userId)), safe(itemId))

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Delete one title's sidecars (and the viewer's folder when it was the last). */
    fun delete(userId: String, itemId: String) {
        val d = dir(userId, itemId)
        d.deleteRecursively()
        d.parentFile?.takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
    }

    private fun resolve(base: String, url: String) = if (url.startsWith("/")) base.trimEnd('/') + url else url

    /** Blocking (call on IO). A failure on any one file is skipped: a missing poster or subtitle must never fail a download. */
    fun save(userId: String, itemId: String, ticket: DownloadTicket, baseUrl: String) {
        val d = dir(userId, itemId).also { it.mkdirs() }
        runCatching { File(d, "item.json").writeText(ticket.item.toString()) }
        val index = ticket.subtitles.map { s ->
            SavedSubtitle(s.index, s.language, s.label, s.forced, s.isDefault, "sub-${s.index}.vtt")
        }
        runCatching { File(d, "subs.json").writeText(json.encodeToString(index)) }
        ticket.subtitles.forEach { s -> fetch(resolve(baseUrl, s.url), File(d, "sub-${s.index}.vtt")) }
        ticket.artwork.posterUrl?.let { fetch(resolve(baseUrl, it), File(d, "poster")) }
        ticket.artwork.backdropUrl?.let { fetch(resolve(baseUrl, it), File(d, "backdrop")) }
        ticket.artwork.stillUrl?.let { fetch(resolve(baseUrl, it), File(d, "still")) }
    }

    private fun fetch(url: String, to: File) {
        if (to.isFile && to.length() > 0) return
        runCatching {
            RaviloAppContext.okHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return
                val tmp = File(to.parentFile, to.name + ".part")
                r.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(to)
            }
        }
    }

    @kotlinx.serialization.Serializable
    data class SavedSubtitle(
        val index: Int,
        val language: String?,
        val label: String?,
        val forced: Boolean,
        @kotlinx.serialization.SerialName("is_default") val isDefault: Boolean,
        val file: String,
    )
}
