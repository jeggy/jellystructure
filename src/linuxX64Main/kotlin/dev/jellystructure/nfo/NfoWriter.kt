package dev.jellystructure.nfo

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

object NfoWriter {
    fun buildXml(item: MediaItem): String = when (item.kind) {
        MediaKind.MOVIE -> buildMovieXml(item)
        MediaKind.TV_SHOW -> buildMovieXml(item) // TV nfo extended in P4
    }

    fun write(item: MediaItem): Result<String> = runCatching {
        val dir = item.path.substringBeforeLast('/')
        val filename = when (item.kind) {
            MediaKind.MOVIE -> "movie.nfo"
            MediaKind.TV_SHOW -> "tvshow.nfo"
        }
        val nfoPath = "$dir/$filename"
        val tmp = "$nfoPath.tmp"

        val sink = SystemFileSystem.sink(Path(tmp)).buffered()
        sink.writeString(buildXml(item))
        sink.flush()
        sink.close()

        platform.posix.rename(tmp, nfoPath)
        println("[INFO] Wrote NFO: $nfoPath")
        nfoPath
    }

    fun exists(item: MediaItem): Boolean {
        val dir = item.path.substringBeforeLast('/')
        val filename = when (item.kind) {
            MediaKind.MOVIE -> "movie.nfo"
            MediaKind.TV_SHOW -> "tvshow.nfo"
        }
        return SystemFileSystem.exists(Path("$dir/$filename"))
    }

    fun readRaw(item: MediaItem): String? {
        val dir = item.path.substringBeforeLast('/')
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

    private fun buildMovieXml(item: MediaItem): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8" standalone="yes"?>""")
        appendLine("<movie>")
        appendLine("  <lockdata>true</lockdata>")
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
        appendLine("</movie>")
    }

    private fun String.esc() =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
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
