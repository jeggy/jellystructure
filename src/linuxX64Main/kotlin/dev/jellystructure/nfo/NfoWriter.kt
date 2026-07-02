package dev.jellystructure.nfo

import dev.jellystructure.log.Logger
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Person
import dev.jellystructure.resolver.CertificationResolver
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

object NfoWriter {
    // Phase 106: [ageRatingCascade] resolves item.certifications → a single <mpaa> code; empty
    // cascade or no certifications on the item ⇒ no <mpaa> tag written (feature off / no data).
    fun buildXml(item: MediaItem, serverUrl: String? = null, ageRatingCascade: List<String> = emptyList()): String = when (item.kind) {
        MediaKind.MOVIE -> buildMovieXml(item, serverUrl, ageRatingCascade)
        MediaKind.TV_SHOW -> buildTvShowXml(item, serverUrl, ageRatingCascade)
    }

    // Phase 115: a stable content hash for the item-level NFO — used to tell "we haven't written this
    // change yet" (DB ≠ nfoHash) from "the file on disk isn't ours" (readRaw() ≠ nfoHash) without a
    // byte-for-byte compare at every call site. Not cryptographic — collision risk is irrelevant here,
    // this only ever answers "did the generated XML change since the last write".
    fun contentHash(item: MediaItem, serverUrl: String? = null, ageRatingCascade: List<String> = emptyList()): String =
        buildXml(item, serverUrl, ageRatingCascade).hashCode().toString()

    /** The hash of what's CURRENTLY on disk, or null if there's no NFO yet. Comparing this against
     *  [MediaItem.nfoHash] tells whether the on-disk file is still the one jellystructure last wrote. */
    fun onDiskHash(item: MediaItem): String? = readRaw(item)?.hashCode()?.toString()

    data class WriteResult(val path: String, val hash: String, val writtenAt: Long)

    /** Like [write] but also returns the content hash + write time, for callers that persist
     *  [MediaItem.nfoWrittenAt]/[MediaItem.nfoHash] (Phase 115 sync-state tracking). */
    suspend fun writeTracked(item: MediaItem, serverUrl: String? = null, ageRatingCascade: List<String> = emptyList()): Result<WriteResult> {
        val hash = contentHash(item, serverUrl, ageRatingCascade)
        return write(item, serverUrl, ageRatingCascade).map { path -> WriteResult(path, hash, platform.posix.time(null)) }
    }

    suspend fun write(item: MediaItem, serverUrl: String? = null, ageRatingCascade: List<String> = emptyList()): Result<String> {
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
            val xml = buildXml(item, serverUrl, ageRatingCascade)
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

    suspend fun writeEpisode(episode: Episode, inheritedCast: List<Person> = emptyList()): Result<String> = runCatching {
        val dir = episode.path.substringBeforeLast('/')
        val baseName = episode.filename.substringBeforeLast('.')
        val nfoPath = "$dir/$baseName.nfo"
        writeAtomically(nfoPath, buildEpisodeXml(episode, inheritedCast))
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

    private fun buildEpisodeXml(episode: Episode, inheritedCast: List<Person> = emptyList()): String = buildString {
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
        // Phase 76: episode crew — director/writer tags
        for (p in episode.crew) {
            when (p.type) {
                "Director" -> appendLine("  <director>${p.name.esc()}</director>")
                "Writer" -> appendLine("  <credits>${p.name.esc()}</credits>")
            }
        }
        // Phase 80: inherit main cast by real season presence. An explicit per-episode override wins;
        // otherwise a season member (TMDB seasonEpisodeCounts) is written to every episode of that season.
        // Items with no presence data yet (pre-Phase-80, not re-pulled) inherit all (back-compat).
        val sn = episode.seasonNumber?.toString()
        val en = episode.episodeNumber
        val presentMainCast = inheritedCast.filter { p ->
            if (sn == null || en == null) return@filter true
            val override = p.episodePresence[sn]
            when {
                override != null -> override.contains(en)
                p.seasonEpisodeCounts.containsKey(sn) -> true
                p.seasonEpisodeCounts.isEmpty() && p.episodePresence.isEmpty() -> true
                else -> false
            }
        }
        val allActors = presentMainCast + episode.guestStars
        for ((order, p) in allActors.withIndex()) {
            appendLine("  <actor>")
            appendLine("    <name>${p.name.esc()}</name>")
            val charOrRole = (p.character ?: p.role)?.takeIf { it.isNotBlank() }
            if (charOrRole != null) appendLine("    <role>${charOrRole.esc()}</role>")
            appendLine("    <order>$order</order>")
            if (!p.profilePath.isNullOrBlank()) {
                appendLine("    <thumb>/api/people/${p.tmdbId}/image</thumb>")
            }
            appendLine("  </actor>")
        }
        appendLine("</episodedetails>")
    }

    private fun buildMovieXml(item: MediaItem, serverUrl: String? = null, ageRatingCascade: List<String> = emptyList()): String = buildString {
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
        CertificationResolver.resolve(ageRatingCascade, item.certifications)?.let {
            appendLine("  <mpaa>${it.code.esc()}</mpaa>")
        }
        if (item.tmdbId != null) {
            appendLine("  <tmdbid>${item.tmdbId}</tmdbid>")
            appendLine("""  <uniqueid type="tmdb" default="true">${item.tmdbId}</uniqueid>""")
        }
        if (!item.imdbId.isNullOrBlank()) {
            appendLine("  <imdbid>${item.imdbId.esc()}</imdbid>")
            appendLine("""  <uniqueid type="imdb">${item.imdbId.esc()}</uniqueid>""")
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

    private fun buildTvShowXml(item: MediaItem, serverUrl: String? = null, ageRatingCascade: List<String> = emptyList()): String = buildString {
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
        CertificationResolver.resolve(ageRatingCascade, item.certifications)?.let {
            appendLine("  <mpaa>${it.code.esc()}</mpaa>")
        }
        if (item.tmdbId != null) {
            appendLine("  <tmdbid>${item.tmdbId}</tmdbid>")
            appendLine("""  <uniqueid type="tmdb" default="true">${item.tmdbId}</uniqueid>""")
        }
        if (!item.imdbId.isNullOrBlank()) {
            appendLine("  <imdbid>${item.imdbId.esc()}</imdbid>")
            appendLine("""  <uniqueid type="imdb">${item.imdbId.esc()}</uniqueid>""")
        }
        if (item.tvdbId != null) {
            appendLine("""  <uniqueid type="tvdb">${item.tvdbId}</uniqueid>""")
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
