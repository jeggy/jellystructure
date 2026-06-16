package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinItem
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
    private val jellyfinClient: JellyfinClient,
) {
    suspend fun scan(tracker: ScanTracker? = null, onItemReady: suspend (MediaItem) -> Unit): Int {
        val config = configStore.current
        val baseUrl = config.apiKeys.jellyfinUrl
        val token = config.apiKeys.jellyfinToken

        if (baseUrl.isBlank() || token.isBlank()) {
            println("[WARN] Jellyfin URL or token not configured — skipping scan")
            return 0
        }

        val jellyfinItems = jellyfinClient.getItems(baseUrl, token)
        println("[INFO] Jellyfin returned ${jellyfinItems.size} items")

        val libraries = config.libraries.filter { !it.skip && it.localPath.isNotBlank() }
        val globalFallback = config.languageRules.fallbackLanguage

        var count = 0
        for (jItem in jellyfinItems) {
            val jellyfinPath = jItem.path ?: continue

            // Match using jellyfinPath prefix if configured, otherwise fall back to localPath
            val lib = libraries.firstOrNull { lib ->
                val prefix = lib.jellyfinPath.ifBlank { lib.localPath }
                prefix.isNotBlank() && jellyfinPath.startsWith(prefix)
            }
            if (lib == null) {
                println("[WARN] No matching library for '$jellyfinPath' — check library mapping config")
                continue
            }

            // Translate Jellyfin container path → local filesystem path
            val localPath = if (lib.jellyfinPath.isNotBlank()) {
                jellyfinPath.replaceFirst(lib.jellyfinPath, lib.localPath)
            } else {
                jellyfinPath
            }

            val effectiveFallback = lib.fallbackLanguage ?: globalFallback

            val mediaItem = when (jItem.type) {
                "Movie" -> scanMovie(jItem, localPath, effectiveFallback)
                "Series" -> scanSeries(jItem, localPath, effectiveFallback)
                else -> null
            }

            if (tracker?.cancelRequested == true) {
                println("[INFO] Scan cancelled after $count items")
                break
            }

            if (mediaItem != null) {
                onItemReady(mediaItem)
                count++
                delay(100)
            }
        }
        println("[INFO] Scan complete — $count items processed")
        return count
    }

    private suspend fun scanMovie(jItem: JellyfinItem, localPath: String, fallback: String): MediaItem? {
        if (!SystemFileSystem.exists(Path(localPath))) {
            println("[WARN] Movie file not found on disk: $localPath")
            return null
        }

        val (title, year) = parseTitleYear(jItem.name)
        println("[INFO] Scanning movie: $title (${year ?: "?"})")

        val tracks = FfprobeRunner.probe(localPath)
        val audioLangs = tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
        val langPriority = LanguageResolver.priorityList(audioLangs, fallback)

        val tmdbId = jItem.providerIds?.tmdb?.toIntOrNull()
            ?: tmdb.searchMovie(title, year)?.id
        val details = tmdbId?.let { tmdb.getMovieDetailsLocalized(it, langPriority) }
        val resolvedLang = details?.let {
            langPriority.firstOrNull { lang -> it.overview.isNotBlank() && lang != fallback }
                ?: langPriority.lastOrNull()
        }

        val issueCount = tracks.count {
            (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
        }

        return MediaItem(
            id = slugify(title, year),
            title = details?.title ?: title,
            originalTitle = details?.originalTitle?.takeIf { it.isNotBlank() },
            year = year,
            kind = MediaKind.MOVIE,
            path = localPath,
            jellyfinId = jItem.id,
            tmdbId = details?.id,
            originalLanguage = details?.originalLanguage?.takeIf { it.isNotBlank() },
            resolvedLanguage = resolvedLang,
            posterPath = details?.posterPath,
            backdropPath = details?.backdropPath,
            overview = details?.overview?.takeIf { it.isNotBlank() },
            genres = details?.genres?.map { it.name } ?: emptyList(),
            tracks = tracks,
            issueCount = issueCount,
            languageMix = false,
            scannedAt = epochSeconds(),
        )
    }

    private suspend fun scanSeries(jItem: JellyfinItem, localPath: String, fallback: String): MediaItem? {
        if (!SystemFileSystem.exists(Path(localPath))) {
            println("[WARN] Series directory not found on disk: $localPath")
            return null
        }

        val (title, year) = parseTitleYear(jItem.name)
        println("[INFO] Scanning series: $title (${year ?: "?"})")

        val episodeFiles = findEpisodeFiles(localPath)
        if (episodeFiles.isEmpty()) {
            println("[WARN] No episode files found in: $localPath")
            return null
        }

        // Sample up to 5 files spread evenly across the collection
        val samples = selectSamples(episodeFiles, maxSamples = 5)
        val probedSamples = samples.map { FfprobeRunner.probe(it) }

        // Compare audio language sets across samples
        val audioSets = probedSamples.map { tracks ->
            tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }.toSet()
        }
        val uniform = audioSets.all { it == audioSets.first() }
        val languageMix = !uniform

        val firstTracks = probedSamples.firstOrNull() ?: emptyList()
        val issueCount = firstTracks.count {
            (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
        }

        if (languageMix) {
            println("[INFO] Series '$title' has mixed audio languages across episodes — marking as language mix")
            return MediaItem(
                id = slugify(title, year),
                title = title,
                year = year,
                kind = MediaKind.TV_SHOW,
                path = localPath,
                jellyfinId = jItem.id,
                tmdbId = jItem.providerIds?.tmdb?.toIntOrNull(),
                originalLanguage = null,
                resolvedLanguage = null,
                posterPath = null,
                backdropPath = null,
                overview = null,
                genres = emptyList(),
                tracks = firstTracks,
                issueCount = issueCount,
                languageMix = true,
                scannedAt = epochSeconds(),
            )
        }

        val audioLangs = firstTracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
        val langPriority = LanguageResolver.priorityList(audioLangs, fallback)

        val tmdbId = jItem.providerIds?.tmdb?.toIntOrNull()
            ?: tmdb.searchTv(title, year)?.id
        val details = tmdbId?.let { tmdb.getTvDetailsLocalized(it, langPriority) }
        val resolvedLang = details?.let {
            langPriority.firstOrNull { lang -> it.overview.isNotBlank() && lang != fallback }
                ?: langPriority.lastOrNull()
        }

        return MediaItem(
            id = slugify(title, year),
            title = details?.name ?: title,
            originalTitle = details?.originalName?.takeIf { it.isNotBlank() },
            year = year,
            kind = MediaKind.TV_SHOW,
            path = localPath,
            jellyfinId = jItem.id,
            tmdbId = details?.id,
            originalLanguage = details?.originalLanguage?.takeIf { it.isNotBlank() },
            resolvedLanguage = resolvedLang,
            posterPath = details?.posterPath,
            backdropPath = details?.backdropPath,
            overview = details?.overview?.takeIf { it.isNotBlank() },
            genres = details?.genres?.map { it.name } ?: emptyList(),
            tracks = firstTracks,
            issueCount = issueCount,
            languageMix = false,
            scannedAt = epochSeconds(),
        )
    }

    // Re-fetches TMDB metadata for an already-scanned item without re-probing
    // the file. Keeps existing tracks, path, and Jellyfin IDs.
    suspend fun rescanMetadata(item: MediaItem): MediaItem? {
        val config = configStore.current
        val globalFallback = config.languageRules.fallbackLanguage
        val lib = config.libraries.firstOrNull { lib ->
            val prefix = lib.localPath.ifBlank { lib.jellyfinPath }
            prefix.isNotBlank() && item.path.startsWith(prefix)
        }
        val fallback = lib?.fallbackLanguage ?: globalFallback
        val audioLangs = item.tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
        val langPriority = LanguageResolver.priorityList(audioLangs, fallback)

        return when (item.kind) {
            MediaKind.MOVIE -> {
                val tmdbId = item.tmdbId ?: tmdb.searchMovie(item.title, item.year)?.id
                val details = tmdbId?.let { tmdb.getMovieDetailsLocalized(it, langPriority) }
                    ?: return null
                val resolvedLang = langPriority.firstOrNull { lang ->
                    details.overview.isNotBlank() && lang != fallback
                } ?: langPriority.lastOrNull()
                item.copy(
                    title = details.title,
                    originalTitle = details.originalTitle.takeIf { it.isNotBlank() },
                    tmdbId = details.id,
                    originalLanguage = details.originalLanguage.takeIf { it.isNotBlank() },
                    resolvedLanguage = resolvedLang,
                    posterPath = details.posterPath,
                    backdropPath = details.backdropPath,
                    overview = details.overview.takeIf { it.isNotBlank() },
                    genres = details.genres.map { it.name },
                )
            }
            MediaKind.TV_SHOW -> {
                val tmdbId = item.tmdbId ?: tmdb.searchTv(item.title, item.year)?.id
                val details = tmdbId?.let { tmdb.getTvDetailsLocalized(it, langPriority) }
                    ?: return null
                val resolvedLang = langPriority.firstOrNull { lang ->
                    details.overview.isNotBlank() && lang != fallback
                } ?: langPriority.lastOrNull()
                item.copy(
                    title = details.name,
                    originalTitle = details.originalName.takeIf { it.isNotBlank() },
                    tmdbId = details.id,
                    originalLanguage = details.originalLanguage.takeIf { it.isNotBlank() },
                    resolvedLanguage = resolvedLang,
                    posterPath = details.posterPath,
                    backdropPath = details.backdropPath,
                    overview = details.overview.takeIf { it.isNotBlank() },
                    genres = details.genres.map { it.name },
                )
            }
        }
    }

    private fun findEpisodeFiles(dir: String): List<String> {
        val result = mutableListOf<String>()
        fun recurse(d: String) {
            val path = Path(d)
            if (!SystemFileSystem.exists(path)) return
            val meta = SystemFileSystem.metadataOrNull(path) ?: return
            if (!meta.isDirectory) return
            for (entry in SystemFileSystem.list(path).sortedBy { it.toString() }) {
                val entryStr = entry.toString()
                val entryMeta = SystemFileSystem.metadataOrNull(entry) ?: continue
                when {
                    entryMeta.isDirectory -> recurse(entryStr)
                    entryMeta.isRegularFile && isVideoFile(entryStr) -> result += entryStr
                }
            }
        }
        recurse(dir)
        return result
    }

    private fun selectSamples(files: List<String>, maxSamples: Int): List<String> {
        if (files.size <= maxSamples) return files
        val step = (files.size - 1).toDouble() / (maxSamples - 1)
        return (0 until maxSamples).map { i -> files[(i * step).toInt()] }
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
