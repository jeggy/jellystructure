package dev.jellystructure.nfo

import dev.jellystructure.log.Logger
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Person
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

object NfoWriter {
    fun buildXml(item: MediaItem, serverUrl: String? = null): String = when (item.kind) {
        MediaKind.MOVIE -> buildMovieXml(item, serverUrl)
        MediaKind.TV_SHOW -> buildTvShowXml(item, serverUrl)
    }

    suspend fun write(item: MediaItem, serverUrl: String? = null): Result<String> {
        return runCatching {
            val dir = when (item.kind) {
                MediaKind.MOVIE -> item.path.substringBeforeLast('/')
                MediaKind.TV_SHOW -> item.path  // item.path IS the series directory
            }
            val filename = when (item.kind) {
                MediaKind.MOVIE -> "movie.nfo"
                MediaKind.TV_SHOW -> "tvshow.nfo"
            }
            val nfoPath = "$dir/$filename"
            Logger.info("NfoWriter.write: id='${item.id}' kind=${item.kind} item.path='${item.path}' nfoPath='$nfoPath'")
            val dirExists = SystemFileSystem.exists(Path(dir))
            Logger.info("NfoWriter.write: dir exists=$dirExists")
            val xml = buildXml(item, serverUrl)
            writeAtomically(nfoPath, xml)
            Logger.info("Wrote NFO: $nfoPath", "nfo")
            nfoPath
        }
    }

    /** On-disk path of the item-level NFO (movie.nfo / tvshow.nfo). Display only. */
    fun nfoPath(item: MediaItem): String {
        val dir = when (item.kind) {
            MediaKind.MOVIE -> item.path.substringBeforeLast('/')
            MediaKind.TV_SHOW -> item.path
        }
        val filename = when (item.kind) {
            MediaKind.MOVIE -> "movie.nfo"
            MediaKind.TV_SHOW -> "tvshow.nfo"
        }
        return "$dir/$filename"
    }

    /** On-disk path of an episode's `episodedetails.nfo` (basename.nfo next to the video). */
    fun episodeNfoPath(episode: Episode): String {
        val dir = episode.path.substringBeforeLast('/')
        val baseName = episode.filename.substringBeforeLast('.')
        return "$dir/$baseName.nfo"
    }

    fun exists(item: MediaItem): Boolean {
        val dir = when (item.kind) {
            MediaKind.MOVIE -> item.path.substringBeforeLast('/')
            MediaKind.TV_SHOW -> item.path
        }
        val filename = when (item.kind) {
            MediaKind.MOVIE -> "movie.nfo"
            MediaKind.TV_SHOW -> "tvshow.nfo"
        }
        return SystemFileSystem.exists(Path("$dir/$filename"))
    }

    fun readRaw(item: MediaItem): String? {
        val dir = when (item.kind) {
            MediaKind.MOVIE -> item.path.substringBeforeLast('/')
            MediaKind.TV_SHOW -> item.path
        }
        val filename = when (item.kind) {
            MediaKind.MOVIE -> "movie.nfo"
            MediaKind.TV_SHOW -> "tvshow.nfo"
        }
        val path = Path("$dir/$filename")
        if (!SystemFileSystem.exists(path)) return null
        return runCatching {
            SystemFileSystem.source(path).buffered().use { it.readString() }
        }.getOrNull()
    }

    suspend fun writeEpisode(episode: Episode): Result<String> = runCatching {
        val dir = episode.path.substringBeforeLast('/')
        val baseName = episode.filename.substringBeforeLast('.')
        val nfoPath = "$dir/$baseName.nfo"
        writeAtomically(nfoPath, buildEpisodeXml(episode))
        Logger.info("Wrote episode NFO: $nfoPath", "nfo")
        nfoPath
    }

    fun episodeNfoExists(episode: Episode): Boolean {
        val dir = episode.path.substringBeforeLast('/')
        val baseName = episode.filename.substringBeforeLast('.')
        return SystemFileSystem.exists(Path("$dir/$baseName.nfo"))
    }

    fun readRawEpisode(episode: Episode): String? {
        val dir = episode.path.substringBeforeLast('/')
        val baseName = episode.filename.substringBeforeLast('.')
        val path = Path("$dir/$baseName.nfo")
        if (!SystemFileSystem.exists(path)) return null
        return runCatching {
            SystemFileSystem.source(path).buffered().use { it.readString() }
        }.getOrNull()
    }

    private fun buildEpisodeXml(episode: Episode): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8" standalone="yes"?>""")
        appendLine("<episodedetails>")
        if (!episode.title.isNullOrBlank()) {
            appendLine("  <title>${episode.title.esc()}</title>")
        }
        if (episode.seasonNumber != null) appendLine("  <season>${episode.seasonNumber}</season>")
        if (episode.episodeNumber != null) appendLine("  <episode>${episode.episodeNumber}</episode>")
        if (!episode.overview.isNullOrBlank()) {
            appendLine("  <plot>${episode.overview.esc()}</plot>")
        }
        if (episode.tmdbEpisodeId != null) {
            appendLine("""  <uniqueid type="tmdb">${episode.tmdbEpisodeId}</uniqueid>""")
        }
        appendLine("</episodedetails>")
    }

    private fun buildMovieXml(item: MediaItem, serverUrl: String? = null): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8" standalone="yes"?>""")
        appendLine("<movie>")
        appendLine("  <title>${item.title.esc()}</title>")
        if (!item.originalTitle.isNullOrBlank()) {
            appendLine("  <originaltitle>${item.originalTitle.esc()}</originaltitle>")
        }
        if (item.year != null) appendLine("  <year>${item.year}</year>")
        if (!item.overview.isNullOrBlank()) {
            appendLine("  <plot>${item.overview.esc()}</plot>")
        }
        if (item.tmdbId != null) {
            appendLine("  <tmdbid>${item.tmdbId}</tmdbid>")
            appendLine("""  <uniqueid type="tmdb" default="true">${item.tmdbId}</uniqueid>""")
        }
        if (!item.originalLanguage.isNullOrBlank()) {
            appendLine("  <originallanguage>${item.originalLanguage.esc()}</originallanguage>")
        }
        for (genre in item.genres) {
            appendLine("  <genre>${genre.esc()}</genre>")
        }
        for (tag in item.tags) {
            appendLine("  <tag>${tag.esc()}</tag>")
        }
        if (!item.director.isNullOrBlank()) {
            appendLine("  <director>${item.director.esc()}</director>")
        }
        for (p in item.crew.filter { it.department?.lowercase() == "directing" && it.job?.lowercase() == "director" }) {
            appendLine("  <director>${p.name.esc()}</director>")
        }
        for (p in item.crew.filter { it.department?.lowercase() == "writing" }) {
            appendLine("  <writer>${p.name.esc()}</writer>")
        }
        if (!item.studio.isNullOrBlank()) {
            appendLine("  <studio>${item.studio.esc()}</studio>")
        }
        for (p in item.cast.sortedBy { it.order }) {
            appendLine("  <actor>")
            appendLine("    <name>${p.name.esc()}</name>")
            val roleText = p.role?.takeIf { it.isNotBlank() } ?: p.character?.takeIf { it.isNotBlank() }
            if (!roleText.isNullOrBlank()) appendLine("    <role>${roleText.esc()}</role>")
            appendLine("    <order>${p.order}</order>")
            appendLine("    <type>${p.type.esc()}</type>")
            if (serverUrl != null && p.tmdbId != 0) appendLine("    <thumb>${serverUrl}/api/people/${p.tmdbId}/image</thumb>")
            appendLine("  </actor>")
        }
        appendLine("</movie>")
    }

    private fun buildTvShowXml(item: MediaItem, serverUrl: String? = null): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8" standalone="yes"?>""")
        appendLine("<tvshow>")
        appendLine("  <title>${item.title.esc()}</title>")
        if (!item.originalTitle.isNullOrBlank()) {
            appendLine("  <originaltitle>${item.originalTitle.esc()}</originaltitle>")
        }
        if (item.year != null) appendLine("  <year>${item.year}</year>")
        if (!item.overview.isNullOrBlank()) {
            appendLine("  <plot>${item.overview.esc()}</plot>")
        }
        if (item.tmdbId != null) {
            appendLine("  <tmdbid>${item.tmdbId}</tmdbid>")
            appendLine("""  <uniqueid type="tmdb" default="true">${item.tmdbId}</uniqueid>""")
        }
        if (!item.originalLanguage.isNullOrBlank()) {
            appendLine("  <originallanguage>${item.originalLanguage.esc()}</originallanguage>")
        }
        for (genre in item.genres) {
            appendLine("  <genre>${genre.esc()}</genre>")
        }
        for (tag in item.tags) {
            appendLine("  <tag>${tag.esc()}</tag>")
        }
        if (!item.studio.isNullOrBlank()) {
            appendLine("  <studio>${item.studio.esc()}</studio>")
        }
        if (!item.network.isNullOrBlank()) {
            appendLine("  <tvstudio>${item.network.esc()}</tvstudio>")
        }
        for (p in item.crew.filter { it.department?.lowercase() == "directing" && it.job?.lowercase() == "director" }) {
            appendLine("  <director>${p.name.esc()}</director>")
        }
        for (p in item.crew.filter { it.department?.lowercase() == "writing" }) {
            appendLine("  <writer>${p.name.esc()}</writer>")
        }
        for (p in item.cast.sortedBy { it.order }) {
            appendLine("  <actor>")
            appendLine("    <name>${p.name.esc()}</name>")
            val roleText = p.role?.takeIf { it.isNotBlank() } ?: p.character?.takeIf { it.isNotBlank() }
            if (!roleText.isNullOrBlank()) appendLine("    <role>${roleText.esc()}</role>")
            appendLine("    <order>${p.order}</order>")
            appendLine("    <type>${p.type.esc()}</type>")
            if (serverUrl != null && p.tmdbId != 0) appendLine("    <thumb>${serverUrl}/api/people/${p.tmdbId}/image</thumb>")
            appendLine("  </actor>")
        }
        appendLine("</tvshow>")
    }

    private fun String.esc() =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/**
 * Write [content] to [destPath] as safely as possible.
 *
 * Strategy:
 * 1. Write to a sibling .tmp file in the same directory.
 * 2. Atomically rename .tmp → dest (works when both are on the same filesystem).
 * 3. If rename fails (e.g. EXDEV cross-device error in Docker), fall back to a direct
 *    overwrite of the destination file.  This is less atomic but still correct for
 *    our use case (single-writer, single-reader, small files).
 *
 * Throws if neither strategy succeeds.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private suspend fun writeAtomically(destPath: String, content: String) {
    val tmp = "$destPath.tmp"
    // Write content to the .tmp file
    val sink = SystemFileSystem.sink(Path(tmp)).buffered()
    sink.writeString(content)
    sink.flush()
    sink.close()

    // Try atomic rename first
    val rc = platform.posix.rename(tmp, destPath)
    if (rc == 0) return

    // Rename failed — clean up .tmp and fall back to direct write
    val renameErrno = platform.posix.errno
    runCatching { SystemFileSystem.delete(Path(tmp)) }
    Logger.warn("writeAtomically: rename failed (errno=$renameErrno), falling back to direct write for '$destPath'")

    // Direct overwrite
    val sink2 = SystemFileSystem.sink(Path(destPath)).buffered()
    sink2.writeString(content)
    sink2.flush()
    sink2.close()
}

private fun kotlinx.io.Source.readString(): String {
    val sb = StringBuilder()
    val buf = ByteArray(8192)
    while (true) {
        val n = readAtMostTo(buf, 0, buf.size)
        if (n == -1) break
        sb.append(buf.decodeToString(0, n))
    }
    return sb.toString()
}
