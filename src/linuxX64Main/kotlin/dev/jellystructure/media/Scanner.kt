package dev.jellystructure.media

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LanguageResolver
import dev.jellystructure.tmdb.TmdbClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val TITLE_YEAR_RE = Regex("""^(.+?)\s+\((\d{4})\)\s*$""")
private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "m2ts")

class Scanner(
    private val configStore: ConfigStore,
    private val tmdb: TmdbClient,
) {
    suspend fun scan(): List<MediaItem> {
        val results = mutableListOf<MediaItem>()
        for (lib in configStore.current.libraries) {
            if (lib.skip || lib.localPath.isBlank()) continue
            println("[INFO] Scanning library '${lib.name}' (${lib.collectionType}) at ${lib.localPath}")
            val kind = when (lib.collectionType.lowercase()) {
                "tvshows" -> MediaKind.TV_SHOW
                else -> MediaKind.MOVIE
            }
            results += scanDir(lib.localPath, kind)
        }
        return results
    }

    private suspend fun scanDir(dir: String, kind: MediaKind): List<MediaItem> {
        val root = Path(dir)
        if (!SystemFileSystem.exists(root)) {
            println("[WARN] Library dir not found: $dir")
            return emptyList()
        }
        val items = mutableListOf<MediaItem>()
        for (entry in SystemFileSystem.list(root).sortedBy { it.toString() }) {
            val entryStr = entry.toString()
            val entryName = entryStr.trimEnd('/').substringAfterLast('/')
            val meta = SystemFileSystem.metadataOrNull(entry) ?: continue
            when {
                meta.isDirectory -> {
                    val videoFile = SystemFileSystem.list(entry)
                        .firstOrNull { child ->
                            val childMeta = SystemFileSystem.metadataOrNull(child)
                            childMeta?.isRegularFile == true && isVideoFile(child.toString())
                        }
                    if (videoFile != null) {
                        scanOneFile(videoFile.toString(), entryName, kind)?.let { items += it }
                        delay(150)
                    }
                }
                meta.isRegularFile && isVideoFile(entryStr) -> {
                    val nameWithoutExt = entryName.substringBeforeLast('.')
                    scanOneFile(entryStr, nameWithoutExt, kind)?.let { items += it }
                    delay(150)
                }
            }
        }
        println("[INFO] Found ${items.size} items in $dir")
        return items
    }

    private suspend fun scanOneFile(filePath: String, displayName: String, kind: MediaKind): MediaItem? {
        val (title, year) = parseTitleYear(displayName)
        println("[INFO] Scanning: $title (${year ?: "?"})")

        val tracks = FfprobeRunner.probe(filePath)

        val audioLangs = tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
        val fallback = configStore.current.languageRules.fallbackLanguage
        val langPriority = LanguageResolver.priorityList(audioLangs, fallback)

        val tmdbResult = tmdb.searchMovie(title, year)
        val details = tmdbResult?.let { tmdb.getMovieDetailsLocalized(it.id, langPriority) }
        val resolvedLang = details?.let {
            langPriority.firstOrNull { lang -> it.overview.isNotBlank() } ?: langPriority.lastOrNull()
        }

        val issueCount = tracks.count {
            (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
        }

        return MediaItem(
            id = slugify(title, year),
            title = details?.title ?: title,
            originalTitle = details?.originalTitle?.takeIf { it.isNotBlank() },
            year = year,
            kind = kind,
            path = filePath,
            tmdbId = details?.id,
            originalLanguage = details?.originalLanguage,
            resolvedLanguage = resolvedLang,
            posterPath = details?.posterPath,
            backdropPath = details?.backdropPath,
            overview = details?.overview?.takeIf { it.isNotBlank() },
            genres = details?.genres?.map { it.name } ?: emptyList(),
            tracks = tracks,
            issueCount = issueCount,
            scannedAt = epochSeconds(),
        )
    }

    private fun parseTitleYear(name: String): Pair<String, Int?> {
        val match = TITLE_YEAR_RE.find(name.trim())
            ?: return Pair(name.trim(), null)
        return Pair(match.groupValues[1].trim(), match.groupValues[2].toIntOrNull())
    }

    private fun isVideoFile(path: String): Boolean =
        path.substringAfterLast('.').lowercase() in VIDEO_EXTENSIONS

    private fun slugify(title: String, year: Int?): String {
        val base = if (year != null) "$title $year" else title
        return base.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun epochSeconds(): Long = platform.posix.time(null)
}
