package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinItem
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Person
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LanguageResolver
import dev.jellystructure.tmdb.TmdbClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val TITLE_YEAR_RE = Regex("""^(.+?)\s+\((\d{4})\)\s*$""")
// Season allows up to 4 digits so year-as-season numbering (e.g. S2025E01) parses (Phase 53-C).
private val SEASON_EP_RE = Regex("""[Ss](\d{1,4})[Ee](\d{1,3})""")
private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "m2ts")

class Scanner(
    private val configStore: ConfigStore,
    private val tmdb: TmdbClient,
    private val jellyfinClient: JellyfinClient,
    private val jsTagStore: JsTagStore,
) {
    /**
     * TMDB re-pull tag rule (Phase 19 §15): TMDB keywords become the non-JS tags, and any
     * Jellystructure-defined tags on the item always survive. Jellyfin-sourced tags that are
     * neither are dropped — TMDB is authoritative for non-JS tags on a TMDB re-pull.
     */
    private fun mergeRepullTags(tmdbTags: List<String>, existing: MediaItem): List<String> {
        val jsNames = jsTagStore.nameSet()
        return (tmdbTags + existing.tags.filter { it in jsNames }).distinct()
    }
    /** Processes a single Jellyfin item end-to-end. Used by the worker pool and the sequential scan. */
    suspend fun scanItem(jItem: JellyfinItem): MediaItem? {
        val config = configStore.current
        val libraries = config.libraries.filter { !it.skip && it.localPath.isNotBlank() }
        val globalFallback = config.languageRules.fallbackLanguage

        val jellyfinPath = jItem.path ?: return null
        val lib = libraries.firstOrNull { lib ->
            val prefix = lib.jellyfinPath.ifBlank { lib.localPath }
            prefix.isNotBlank() && jellyfinPath.startsWith(prefix)
        }
        if (lib == null) {
            val prefixes = libraries.map { it.jellyfinPath.ifBlank { it.localPath } }
            Logger.warn("No matching library for '$jellyfinPath' — configured prefixes: $prefixes", "scan")
            return null
        }
        val localPath = if (lib.jellyfinPath.isNotBlank()) {
            jellyfinPath.replaceFirst(lib.jellyfinPath, lib.localPath)
        } else {
            jellyfinPath
        }
        val effectiveFallback = lib.fallbackLanguage?.ifBlank { null } ?: globalFallback
        return when (jItem.type) {
            "Movie" -> scanMovie(jItem, localPath, effectiveFallback)
            "Series" -> scanSeries(jItem, localPath, effectiveFallback)
            else -> null
        }
    }

    /** Fetches all Jellyfin items. Returns null if URL/token not configured. */
    suspend fun fetchItemsForLibrary(libraryJellyfinId: String): List<JellyfinItem>? {
        val config = configStore.current
        val baseUrl = config.apiKeys.jellyfinUrl
        val token = config.apiKeys.jellyfinToken
        if (baseUrl.isBlank() || token.isBlank()) {
            Logger.warn("Jellyfin URL or token not configured — skipping scan")
            return null
        }
        return jellyfinClient.getItemsByParent(baseUrl, token, libraryJellyfinId)
    }

    suspend fun fetchItems(): List<JellyfinItem>? {
        val config = configStore.current
        val baseUrl = config.apiKeys.jellyfinUrl
        val token = config.apiKeys.jellyfinToken
        if (baseUrl.isBlank() || token.isBlank()) {
            Logger.warn("Jellyfin URL or token not configured — skipping scan")
            return null
        }
        return jellyfinClient.getItems(baseUrl, token)
    }

    /**
     * Re-fetches a single item from Jellyfin by its Jellyfin ID, re-runs the full scan pipeline
     * (ffprobe + TMDB), and returns the refreshed MediaItem. JS tags from the existing item are
     * preserved. Returns null if the item cannot be found in Jellyfin or config is missing.
     */
    suspend fun rescanFromJellyfin(existing: MediaItem): MediaItem? {
        val config = configStore.current
        val baseUrl = config.apiKeys.jellyfinUrl
        val token = config.apiKeys.jellyfinToken
        if (baseUrl.isBlank() || token.isBlank()) {
            Logger.warn("Jellyfin URL or token not configured — cannot re-pull")
            return null
        }
        val jid = existing.jellyfinId
        if (jid.isNullOrBlank()) {
            Logger.warn("Item '${existing.id}' has no Jellyfin ID — cannot re-pull")
            return null
        }
        val jItem = jellyfinClient.getItem(baseUrl, token, jid)
        if (jItem == null) {
            Logger.warn("Jellyfin returned null for item id=$jid")
            return null
        }
        val fresh = scanItem(jItem) ?: return null
        // Sync-from-Jellyfin merges additively: union Jellyfin's current tags with everything the
        // item already had (TMDB-sourced + Jellystructure-defined tags all survive).
        return fresh.copy(tags = (fresh.tags + existing.tags).distinct())
    }

    /** Sequential single-worker scan — used by FolderWatcher auto-scans. */
    suspend fun scan(
        tracker: ScanTracker? = null,
        skipIds: Set<String> = emptySet(),
        onItemReady: suspend (MediaItem) -> Unit,
    ): Int {
        val jellyfinItems = fetchItems() ?: return 0
        Logger.info("Jellyfin returned ${jellyfinItems.size} items (${skipIds.size} will be skipped for resume)")
        var count = 0
        for (jItem in jellyfinItems) {
            if (jItem.id in skipIds) {
                Logger.info("Resume: skipping already-processed '${jItem.name}'")
                continue
            }
            if (tracker?.cancelRequested == true) {
                Logger.info("Scan cancelled after $count items")
                break
            }
            val mediaItem = scanItem(jItem)
            if (mediaItem != null) {
                onItemReady(mediaItem)
                count++
                delay(100)
            }
        }
        Logger.info("Scan complete — $count items processed")
        return count
    }

    private suspend fun scanMovie(jItem: JellyfinItem, localPath: String, fallback: String): MediaItem? {
        if (!SystemFileSystem.exists(Path(localPath))) {
            Logger.warn("Movie file not found on disk: $localPath")
            return null
        }

        val (title, parsedYear) = parseTitleYear(jItem.name)
        // Search/id year: name-parsed, else Jellyfin's ProductionYear. Stable + match-independent
        // (TMDB's year isn't known until after the lookup), so it's safe for the search + the slug (Phase 53-A).
        val searchYear = parsedYear ?: jItem.year
        Logger.info("Scanning movie: $title (${searchYear ?: "?"})", "scan")

        val tracks = FfprobeRunner.probe(localPath)
        val audioLangs = tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
        val langPriority = LanguageResolver.priorityList(audioLangs, fallback)

        val tmdbId = jItem.providerIds?.tmdb?.toIntOrNull()
            ?: tmdb.searchMovie(title, searchYear)?.id
        val details = tmdbId?.let { tmdb.getMovieDetailsLocalized(it, langPriority) }
        val resolvedLang = details?.let {
            langPriority.firstOrNull { lang -> it.overview.isNotBlank() && lang != fallback }
                ?: langPriority.lastOrNull()
        }

        val issueCount = tracks.count {
            (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
        }

        val primaryCompany = details?.productionCompanies?.firstOrNull()
        val tmdbFinalId = details?.id ?: tmdbId
        // Stored/display year prefers TMDB's release year, then the search year.
        val storedYear = details?.releaseDate?.take(4)?.toIntOrNull() ?: searchYear
        val titlesByLang = buildTitlesByLang(tmdbFinalId, isMovie = true, details?.title, details?.originalLanguage, details?.originalTitle)
        val (cast, crew) = tmdbFinalId?.let { fetchCredits(it, isMovie = true) } ?: Pair(emptyList(), emptyList())
        val extIds = tmdbFinalId?.let { tmdb.getExternalIds(it, isMovie = true) }
        return MediaItem(
            id = itemId(title, searchYear, jItem.id),
            title = details?.title ?: title,
            originalTitle = details?.originalTitle?.takeIf { it.isNotBlank() },
            year = storedYear,
            kind = MediaKind.MOVIE,
            path = localPath,
            jellyfinId = jItem.id,
            tmdbId = tmdbFinalId,
            originalLanguage = details?.originalLanguage?.takeIf { it.isNotBlank() },
            resolvedLanguage = resolvedLang,
            posterPath = details?.posterPath,
            backdropPath = details?.backdropPath,
            overview = details?.overview?.takeIf { it.isNotBlank() },
            genres = details?.genres?.map { it.name } ?: emptyList(),
            studio = primaryCompany?.name,
            studioTmdbId = primaryCompany?.id,
            studioLogoPath = primaryCompany?.logoPath,
            tracks = tracks,
            issueCount = issueCount,
            languageMix = false,
            scannedAt = epochSeconds(),
            jellyfinLockData = jItem.lockData,
            jellyfinLockedFields = jItem.lockedFields,
            tags = jItem.tags,
            titlesByLang = titlesByLang,
            cast = cast,
            crew = crew,
            imdbId = extIds?.imdbId?.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun scanSeries(jItem: JellyfinItem, localPath: String, fallback: String): MediaItem? {
        if (!SystemFileSystem.exists(Path(localPath))) {
            Logger.warn("Series directory not found on disk: $localPath")
            return null
        }

        val (title, parsedYear) = parseTitleYear(jItem.name)
        // Search/id year: name-parsed, else Jellyfin's ProductionYear (Phase 53-A).
        val searchYear = parsedYear ?: jItem.year
        Logger.info("Scanning series: $title (${searchYear ?: "?"})", "scan")

        val episodeFiles = findEpisodeFiles(localPath)
        if (episodeFiles.isEmpty()) {
            Logger.warn("No episode files found in: $localPath")
            return null
        }

        // Probe every episode by default; only sample when an operator sets a positive
        // scan_episode_cap (Phase 49). A targeted per-item sync always probes all.
        val cap = configStore.current.behavior.scanEpisodeCap
        val filesToProbe = if (cap in 1 until episodeFiles.size) {
            Logger.info("Series has ${episodeFiles.size} episodes — scan_episode_cap=$cap, probing a spread of $cap")
            selectSamples(episodeFiles, cap)
        } else {
            episodeFiles
        }

        // Resolve TMDB series ID once upfront so it can be reused for both
        // per-episode detail fetching and the series-level metadata fetch below.
        val seriesTmdbId = jItem.providerIds?.tmdb?.toIntOrNull()
            ?: tmdb.searchTv(title, searchYear)?.id

        val episodes = mutableListOf<Episode>()
        for (file in filesToProbe) {
            val tracks = FfprobeRunner.probe(file)
            if (tracks.isEmpty()) Logger.warn("ffprobe returned no tracks for episode: $file", "scan")  // Phase 53-E
            val epIssueCount = tracks.count {
                (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
            }
            val audioLangs = tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
            val epLangPriority = LanguageResolver.priorityList(audioLangs, fallback)
            val epResolvedLang = epLangPriority.firstOrNull()
            val (seasonNum, epNum) = parseSeasonEpisode(file)

            // Fetch per-episode TMDB details in the episode's own resolved language
            val epDetails = if (seriesTmdbId != null && seasonNum != null && epNum != null) {
                epLangPriority.firstNotNullOfOrNull { lang ->
                    tmdb.getEpisodeDetails(seriesTmdbId, seasonNum, epNum, lang)
                        ?.takeIf { it.name.isNotBlank() || it.overview.isNotBlank() }
                } ?: tmdb.getEpisodeDetails(seriesTmdbId, seasonNum, epNum)
            } else null

            // Phase 76: fetch guest stars + episode crew from TMDB
            val (epGuests, epCrew) = if (seriesTmdbId != null && seasonNum != null && epNum != null) {
                fetchEpisodeCredits(seriesTmdbId, seasonNum, epNum)
            } else Pair(emptyList(), emptyList())

            episodes += Episode(
                filename = file.substringAfterLast('/'),
                path = file,
                seasonNumber = seasonNum,
                episodeNumber = epNum,
                tracks = tracks,
                issueCount = epIssueCount,
                resolvedLanguage = epResolvedLang,
                title = epDetails?.name?.takeIf { it.isNotBlank() },
                overview = epDetails?.overview?.takeIf { it.isNotBlank() },
                stillPath = epDetails?.stillPath,
                tmdbEpisodeId = epDetails?.id,
                guestStars = epGuests,
                crew = epCrew,
            )
        }

        val sortedEpisodes = episodes.sortedWith(
            compareBy({ it.seasonNumber ?: 999 }, { it.episodeNumber ?: 999 })
        )

        // Language mix: audio language sets differ across episodes
        val audioSets = sortedEpisodes.map { ep ->
            ep.tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }.toSet()
        }
        val languageMix = audioSets.size > 1 && !audioSets.all { it == audioSets.first() }

        val firstTracks = sortedEpisodes.firstOrNull()?.tracks ?: emptyList()
        val totalIssueCount = sortedEpisodes.sumOf { it.issueCount }

        if (languageMix) {
            Logger.info("Series '$title' has mixed audio languages across episodes — using majority language for NFO")
            // Vote on each episode's primary (first) audio track to find the majority language.
            val langVotes = mutableMapOf<String, Int>()
            for (ep in sortedEpisodes) {
                val primaryLang = ep.tracks
                    .firstOrNull { it.kind == TrackKind.AUDIO }?.language
                    ?.let { LanguageResolver.normalize(it) }
                if (primaryLang != null) langVotes[primaryLang] = (langVotes[primaryLang] ?: 0) + 1
            }
            val majorityLang = langVotes.maxByOrNull { it.value }?.key
            val mixPriority = LanguageResolver.priorityList(
                majorityLang?.let { listOf(it) } ?: emptyList(), fallback
            )
            val mixDetails = seriesTmdbId?.let { tmdb.getTvDetailsLocalized(it, mixPriority) }
            val mixNetwork = mixDetails?.networks?.firstOrNull()
            val mixTitlesByLang = buildTitlesByLang(seriesTmdbId, isMovie = false, mixDetails?.name, mixDetails?.originalLanguage, mixDetails?.originalName)
            val mixStoredYear = mixDetails?.firstAirDate?.take(4)?.toIntOrNull() ?: searchYear
            val (mixCast, mixCrew) = seriesTmdbId?.let { fetchCredits(it, isMovie = false) } ?: Pair(emptyList(), emptyList())
            val mixExtIds = seriesTmdbId?.let { tmdb.getExternalIds(it, isMovie = false) }
            return MediaItem(
                id = itemId(title, searchYear, jItem.id),
                title = mixDetails?.name ?: title,
                originalTitle = mixDetails?.originalName?.takeIf { it.isNotBlank() },
                year = mixStoredYear,
                kind = MediaKind.TV_SHOW,
                path = localPath,
                jellyfinId = jItem.id,
                tmdbId = seriesTmdbId,
                originalLanguage = mixDetails?.originalLanguage?.takeIf { it.isNotBlank() },
                resolvedLanguage = majorityLang,
                posterPath = mixDetails?.posterPath,
                backdropPath = mixDetails?.backdropPath,
                overview = mixDetails?.overview?.takeIf { it.isNotBlank() },
                genres = mixDetails?.genres?.map { it.name } ?: emptyList(),
                network = mixNetwork?.name,
                networkTmdbId = mixNetwork?.id,
                networkLogoPath = mixNetwork?.logoPath,
                tracks = firstTracks,
                episodes = sortedEpisodes,
                issueCount = totalIssueCount,
                languageMix = true,
                scannedAt = epochSeconds(),
                jellyfinLockData = jItem.lockData,
                jellyfinLockedFields = jItem.lockedFields,
                tags = jItem.tags,
                titlesByLang = mixTitlesByLang,
                cast = mixCast,
                crew = mixCrew,
                imdbId = mixExtIds?.imdbId?.takeIf { it.isNotBlank() },
                tvdbId = mixExtIds?.tvdbId,
            )
        }

        val audioLangs = firstTracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
        val langPriority = LanguageResolver.priorityList(audioLangs, fallback)

        val details = seriesTmdbId?.let { tmdb.getTvDetailsLocalized(it, langPriority) }
        val resolvedLang = details?.let {
            langPriority.firstOrNull { lang -> it.overview.isNotBlank() && lang != fallback }
                ?: langPriority.lastOrNull()
        }

        val tvTmdbFinalId = details?.id ?: seriesTmdbId
        val tvTitlesByLang = buildTitlesByLang(tvTmdbFinalId, isMovie = false, details?.name, details?.originalLanguage, details?.originalName)
        val tvStoredYear = details?.firstAirDate?.take(4)?.toIntOrNull() ?: searchYear
        val (tvCast, tvCrew) = tvTmdbFinalId?.let { fetchCredits(it, isMovie = false) } ?: Pair(emptyList(), emptyList())
        val tvExtIds = tvTmdbFinalId?.let { tmdb.getExternalIds(it, isMovie = false) }
        return MediaItem(
            id = itemId(title, searchYear, jItem.id),
            title = details?.name ?: title,
            originalTitle = details?.originalName?.takeIf { it.isNotBlank() },
            year = tvStoredYear,
            kind = MediaKind.TV_SHOW,
            path = localPath,
            jellyfinId = jItem.id,
            tmdbId = tvTmdbFinalId,
            originalLanguage = details?.originalLanguage?.takeIf { it.isNotBlank() },
            resolvedLanguage = resolvedLang,
            posterPath = details?.posterPath,
            backdropPath = details?.backdropPath,
            overview = details?.overview?.takeIf { it.isNotBlank() },
            genres = details?.genres?.map { it.name } ?: emptyList(),
            network = details?.networks?.firstOrNull()?.name,
            networkTmdbId = details?.networks?.firstOrNull()?.id,
            networkLogoPath = details?.networks?.firstOrNull()?.logoPath,
            tracks = firstTracks,
            episodes = sortedEpisodes,
            issueCount = totalIssueCount,
            languageMix = false,
            scannedAt = epochSeconds(),
            jellyfinLockData = jItem.lockData,
            jellyfinLockedFields = jItem.lockedFields,
            tags = jItem.tags,
            titlesByLang = tvTitlesByLang,
            cast = tvCast,
            crew = tvCrew,
            imdbId = tvExtIds?.imdbId?.takeIf { it.isNotBlank() },
            tvdbId = tvExtIds?.tvdbId,
        )
    }

    private fun parseSeasonEpisode(path: String): Pair<Int?, Int?> {
        val filename = path.substringAfterLast('/')
        val match = SEASON_EP_RE.find(filename) ?: return Pair(null, null)
        return Pair(match.groupValues[1].toIntOrNull(), match.groupValues[2].toIntOrNull())
    }

    /** Full re-probe + TMDB for a movie. Replaces tracks and re-resolves language. */
    suspend fun syncMovie(item: MediaItem): MediaItem? {
        val config = configStore.current
        val lib = config.libraries.firstOrNull { lib ->
            val prefix = lib.localPath.ifBlank { lib.jellyfinPath }
            prefix.isNotBlank() && item.path.startsWith(prefix)
        }
        val fallback = lib?.fallbackLanguage?.ifBlank { null } ?: config.languageRules.fallbackLanguage
        if (!SystemFileSystem.exists(Path(item.path))) {
            Logger.warn("Sync: movie file not found: ${item.path}")
            return null
        }
        val tracks = FfprobeRunner.probe(item.path)
        val audioLangs = tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
        val langPriority = LanguageResolver.priorityList(audioLangs, fallback)
        val tmdbId = item.tmdbId ?: tmdb.searchMovie(item.title, item.year)?.id
        val details = tmdbId?.let { tmdb.getMovieDetailsLocalized(it, langPriority) } ?: return null
        val resolvedLang = langPriority.firstOrNull { lang -> details.overview.isNotBlank() && lang != fallback }
            ?: langPriority.lastOrNull()
        val issueCount = tracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
        val primaryCompany = details.productionCompanies.firstOrNull()
        val tmdbTags = tmdb.getMovieKeywords(details.id)
        val syncExtIds = tmdb.getExternalIds(details.id, isMovie = true)
        return item.copy(
            title = details.title,
            originalTitle = details.originalTitle.takeIf { it.isNotBlank() },
            tmdbId = details.id,
            year = details.releaseDate.take(4).toIntOrNull() ?: item.year,
            originalLanguage = details.originalLanguage.takeIf { it.isNotBlank() },
            resolvedLanguage = resolvedLang,
            posterPath = details.posterPath,
            backdropPath = details.backdropPath,
            overview = details.overview.takeIf { it.isNotBlank() },
            genres = details.genres.map { it.name },
            studio = primaryCompany?.name,
            studioTmdbId = primaryCompany?.id,
            studioLogoPath = primaryCompany?.logoPath,
            tracks = tracks,
            issueCount = issueCount,
            tags = mergeRepullTags(tmdbTags, item),
            scannedAt = epochSeconds(),
            imdbId = syncExtIds?.imdbId?.takeIf { it.isNotBlank() } ?: item.imdbId,
        )
    }

    /** Re-probes every episode file on disk and re-fetches TMDB for a TV series. */
    suspend fun syncSeriesEpisodes(item: MediaItem): MediaItem? {
        val config = configStore.current
        val lib = config.libraries.firstOrNull { lib ->
            val prefix = lib.localPath.ifBlank { lib.jellyfinPath }
            prefix.isNotBlank() && item.path.startsWith(prefix)
        }
        val fallback = lib?.fallbackLanguage?.ifBlank { null } ?: config.languageRules.fallbackLanguage
        if (!SystemFileSystem.exists(Path(item.path))) {
            Logger.warn("Sync: series directory not found: ${item.path}")
            return null
        }
        val episodeFiles = findEpisodeFiles(item.path)
        if (episodeFiles.isEmpty()) {
            Logger.warn("Sync: no episode files found in: ${item.path}")
            return null
        }
        val seriesTmdbId = item.tmdbId
        val episodes = mutableListOf<Episode>()
        for (file in episodeFiles) {
            val tracks = FfprobeRunner.probe(file)
            val epIssueCount = tracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
            val audioLangs = tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
            val epLangPriority = LanguageResolver.priorityList(audioLangs, fallback)
            val (seasonNum, epNum) = parseSeasonEpisode(file)
            val existingEp = item.episodes.firstOrNull { it.filename == file.substringAfterLast('/') }
            val epDetails = if (seriesTmdbId != null && seasonNum != null && epNum != null) {
                epLangPriority.firstNotNullOfOrNull { lang ->
                    tmdb.getEpisodeDetails(seriesTmdbId, seasonNum, epNum, lang)
                        ?.takeIf { it.name.isNotBlank() || it.overview.isNotBlank() }
                } ?: tmdb.getEpisodeDetails(seriesTmdbId, seasonNum, epNum)
            } else null
            // Phase 76: preserve existing guest stars/crew; re-fetch from TMDB if available
            val (epGuests, epCrew) = if (seriesTmdbId != null && seasonNum != null && epNum != null) {
                fetchEpisodeCredits(seriesTmdbId, seasonNum, epNum)
            } else Pair(existingEp?.guestStars ?: emptyList(), existingEp?.crew ?: emptyList())
            episodes += Episode(
                filename = file.substringAfterLast('/'),
                path = file,
                seasonNumber = seasonNum,
                episodeNumber = epNum,
                tracks = tracks,
                issueCount = epIssueCount,
                resolvedLanguage = epLangPriority.firstOrNull(),
                title = epDetails?.name?.takeIf { it.isNotBlank() } ?: existingEp?.title,
                overview = epDetails?.overview?.takeIf { it.isNotBlank() } ?: existingEp?.overview,
                stillPath = epDetails?.stillPath ?: existingEp?.stillPath,
                tmdbEpisodeId = epDetails?.id ?: existingEp?.tmdbEpisodeId,
                guestStars = epGuests,
                crew = epCrew,
            )
        }
        val sortedEpisodes = episodes.sortedWith(compareBy({ it.seasonNumber ?: 999 }, { it.episodeNumber ?: 999 }))
        val audioSets = sortedEpisodes.map { ep -> ep.tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }.toSet() }
        val languageMix = audioSets.size > 1 && !audioSets.all { it == audioSets.first() }
        val firstTracks = sortedEpisodes.firstOrNull()?.tracks ?: emptyList()
        val totalIssueCount = sortedEpisodes.sumOf { it.issueCount }
        val resolvedLang: String?
        val updatedDetails = if (languageMix) {
            val langVotes = mutableMapOf<String, Int>()
            for (ep in sortedEpisodes) {
                val primaryLang = ep.tracks.firstOrNull { it.kind == TrackKind.AUDIO }?.language
                    ?.let { LanguageResolver.normalize(it) }
                if (primaryLang != null) langVotes[primaryLang] = (langVotes[primaryLang] ?: 0) + 1
            }
            val majorityLang = langVotes.maxByOrNull { it.value }?.key
            resolvedLang = majorityLang
            val mixPriority = LanguageResolver.priorityList(majorityLang?.let { listOf(it) } ?: emptyList(), fallback)
            seriesTmdbId?.let { tmdb.getTvDetailsLocalized(it, mixPriority) }
        } else {
            val audioLangs = firstTracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
            val langPriority = LanguageResolver.priorityList(audioLangs, fallback)
            val details = seriesTmdbId?.let { tmdb.getTvDetailsLocalized(it, langPriority) }
            resolvedLang = details?.let {
                langPriority.firstOrNull { lang -> it.overview.isNotBlank() && lang != fallback } ?: langPriority.lastOrNull()
            }
            details
        }
        val syncNetwork = updatedDetails?.networks?.firstOrNull()
        val syncSeriesFinalId = updatedDetails?.id ?: seriesTmdbId
        val tmdbTags = syncSeriesFinalId?.let { tmdb.getTvKeywords(it) } ?: emptyList()
        val syncSeriesExtIds = syncSeriesFinalId?.let { tmdb.getExternalIds(it, isMovie = false) }
        return item.copy(
            title = updatedDetails?.name ?: item.title,
            originalTitle = updatedDetails?.originalName?.takeIf { it.isNotBlank() } ?: item.originalTitle,
            tmdbId = syncSeriesFinalId,
            year = updatedDetails?.firstAirDate?.take(4)?.toIntOrNull() ?: item.year,
            originalLanguage = updatedDetails?.originalLanguage?.takeIf { it.isNotBlank() } ?: item.originalLanguage,
            resolvedLanguage = resolvedLang,
            posterPath = updatedDetails?.posterPath ?: item.posterPath,
            backdropPath = updatedDetails?.backdropPath ?: item.backdropPath,
            overview = updatedDetails?.overview?.takeIf { it.isNotBlank() } ?: item.overview,
            genres = updatedDetails?.genres?.map { it.name } ?: item.genres,
            network = syncNetwork?.name ?: item.network,
            networkTmdbId = syncNetwork?.id ?: item.networkTmdbId,
            networkLogoPath = syncNetwork?.logoPath ?: item.networkLogoPath,
            tracks = firstTracks,
            episodes = sortedEpisodes,
            issueCount = totalIssueCount,
            languageMix = languageMix,
            tags = mergeRepullTags(tmdbTags, item),
            scannedAt = epochSeconds(),
            imdbId = syncSeriesExtIds?.imdbId?.takeIf { it.isNotBlank() } ?: item.imdbId,
            tvdbId = syncSeriesExtIds?.tvdbId ?: item.tvdbId,
        )
    }

    /** Re-syncs episodes of a specific season. probeFiles=true re-runs ffprobe on each file. */
    suspend fun syncSeason(item: MediaItem, seasonNumber: Int, probeFiles: Boolean): Pair<MediaItem, Int> {
        val config = configStore.current
        val lib = config.libraries.firstOrNull { lib ->
            val prefix = lib.localPath.ifBlank { lib.jellyfinPath }
            prefix.isNotBlank() && item.path.startsWith(prefix)
        }
        val fallback = lib?.fallbackLanguage?.ifBlank { null } ?: config.languageRules.fallbackLanguage
        val seriesTmdbId = item.tmdbId
        val updatedEpisodes = item.episodes.toMutableList()
        var synced = 0
        for ((idx, ep) in updatedEpisodes.withIndex()) {
            if (ep.seasonNumber != seasonNumber) continue
            val tracks = if (probeFiles) FfprobeRunner.probe(ep.path) else ep.tracks
            val epIssueCount = if (probeFiles)
                tracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
            else ep.issueCount
            val audioLangs = tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
            val epLangPriority = LanguageResolver.priorityList(audioLangs, fallback)
            val epNum = ep.episodeNumber
            val epDetails = if (seriesTmdbId != null && epNum != null) {
                epLangPriority.firstNotNullOfOrNull { lang ->
                    tmdb.getEpisodeDetails(seriesTmdbId, seasonNumber, epNum, lang)
                        ?.takeIf { it.name.isNotBlank() || it.overview.isNotBlank() }
                } ?: tmdb.getEpisodeDetails(seriesTmdbId, seasonNumber, epNum)
            } else null
            updatedEpisodes[idx] = ep.copy(
                tracks = tracks,
                issueCount = epIssueCount,
                resolvedLanguage = epLangPriority.firstOrNull(),
                title = epDetails?.name?.takeIf { it.isNotBlank() } ?: ep.title,
                overview = epDetails?.overview?.takeIf { it.isNotBlank() } ?: ep.overview,
                stillPath = epDetails?.stillPath ?: ep.stillPath,
                tmdbEpisodeId = epDetails?.id ?: ep.tmdbEpisodeId,
            )
            synced++
        }
        val totalIssueCount = updatedEpisodes.sumOf { it.issueCount }
        return Pair(item.copy(episodes = updatedEpisodes, issueCount = totalIssueCount, scannedAt = epochSeconds()), synced)
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
        val fallback = lib?.fallbackLanguage?.ifBlank { null } ?: globalFallback
        // For TV shows, item.tracks mirrors the first episode's tracks but may be stale after triage edits.
        // Read from episodes.first() when available so repull sees the current (post-triage) track state.
        val sourceTracks = if (item.kind == MediaKind.TV_SHOW)
            item.episodes.firstOrNull()?.tracks ?: item.tracks
        else item.tracks
        val audioLangs = sourceTracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
        val basePriority = LanguageResolver.priorityList(audioLangs, fallback)
        // If the user has explicitly set a language override (e.g. via the language-mix control),
        // honour it as the first TMDB query language so repull fetches metadata in that language.
        val overrideLang = item.resolvedLanguage?.ifBlank { null }?.let { LanguageResolver.normalize(it) }
        val langPriority = if (overrideLang != null && basePriority.firstOrNull() != overrideLang)
            listOf(overrideLang) + basePriority.filter { it != overrideLang }
        else basePriority

        return when (item.kind) {
            MediaKind.MOVIE -> {
                val tmdbId = item.tmdbId ?: tmdb.searchMovie(item.title, item.year)?.id
                val details = tmdbId?.let { tmdb.getMovieDetailsLocalized(it, langPriority) }
                    ?: return null
                val resolvedLang = langPriority.firstOrNull { lang ->
                    details.overview.isNotBlank() && lang != fallback
                } ?: langPriority.lastOrNull()
                val rescanCompany = details.productionCompanies.firstOrNull()
                val rescanTmdbTags = tmdb.getMovieKeywords(details.id)
                val rescanMovieExtIds = tmdb.getExternalIds(details.id, isMovie = true)
                val (rescanCast, rescanCrew) = fetchCredits(details.id, isMovie = true)
                item.copy(
                    title = details.title,
                    originalTitle = details.originalTitle.takeIf { it.isNotBlank() },
                    tmdbId = details.id,
                    year = details.releaseDate.take(4).toIntOrNull() ?: item.year,
                    originalLanguage = details.originalLanguage.takeIf { it.isNotBlank() },
                    resolvedLanguage = resolvedLang,
                    posterPath = details.posterPath,
                    backdropPath = details.backdropPath,
                    overview = details.overview.takeIf { it.isNotBlank() },
                    genres = details.genres.map { it.name },
                    studio = rescanCompany?.name,
                    studioTmdbId = rescanCompany?.id,
                    studioLogoPath = rescanCompany?.logoPath,
                    tags = mergeRepullTags(rescanTmdbTags, item),
                    imdbId = rescanMovieExtIds?.imdbId?.takeIf { it.isNotBlank() } ?: item.imdbId,
                    cast = rescanCast,
                    crew = rescanCrew,
                )
            }
            MediaKind.TV_SHOW -> {
                val tmdbId = item.tmdbId ?: tmdb.searchTv(item.title, item.year)?.id
                val details = tmdbId?.let { tmdb.getTvDetailsLocalized(it, langPriority) }
                    ?: return null
                val resolvedLang = langPriority.firstOrNull { lang ->
                    details.overview.isNotBlank() && lang != fallback
                } ?: langPriority.lastOrNull()
                // Re-fetch per-episode TMDB details using the series langPriority, which already
                // puts the user's selected series language first, then falls through the chain.
                val updatedEpisodes = item.episodes.map { ep ->
                    val s = ep.seasonNumber
                    val e = ep.episodeNumber
                    if (s != null && e != null) {
                        val epDetails = langPriority.firstNotNullOfOrNull { lang ->
                            tmdb.getEpisodeDetails(details.id, s, e, lang)
                                ?.takeIf { it.name.isNotBlank() || it.overview.isNotBlank() }
                        } ?: tmdb.getEpisodeDetails(details.id, s, e)
                        if (epDetails != null) ep.copy(
                            title = epDetails.name.takeIf { it.isNotBlank() },
                            overview = epDetails.overview.takeIf { it.isNotBlank() },
                            stillPath = epDetails.stillPath,
                            tmdbEpisodeId = epDetails.id,
                            resolvedLanguage = resolvedLang,
                        ) else ep
                    } else ep
                }
                val rescanNetwork = details.networks.firstOrNull()
                val rescanTmdbTags = tmdb.getTvKeywords(details.id)
                val rescanTvExtIds = tmdb.getExternalIds(details.id, isMovie = false)
                val (rescanTvCast, rescanTvCrew) = fetchCredits(details.id, isMovie = false)
                item.copy(
                    title = details.name,
                    originalTitle = details.originalName.takeIf { it.isNotBlank() },
                    tmdbId = details.id,
                    year = details.firstAirDate.take(4).toIntOrNull() ?: item.year,
                    originalLanguage = details.originalLanguage.takeIf { it.isNotBlank() },
                    resolvedLanguage = resolvedLang,
                    posterPath = details.posterPath,
                    backdropPath = details.backdropPath,
                    overview = details.overview.takeIf { it.isNotBlank() },
                    genres = details.genres.map { it.name },
                    network = rescanNetwork?.name ?: item.network,
                    networkTmdbId = rescanNetwork?.id ?: item.networkTmdbId,
                    networkLogoPath = rescanNetwork?.logoPath ?: item.networkLogoPath,
                    tags = mergeRepullTags(rescanTmdbTags, item),
                    episodes = updatedEpisodes,
                    imdbId = rescanTvExtIds?.imdbId?.takeIf { it.isNotBlank() } ?: item.imdbId,
                    tvdbId = rescanTvExtIds?.tvdbId ?: item.tvdbId,
                    cast = rescanTvCast,
                    crew = rescanTvCrew,
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
                val entryName = entryStr.substringAfterLast('/')
                if (entryName.startsWith(".")) continue  // skip hidden files and directories
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

    private fun isVideoFile(path: String): Boolean {
        val filename = path.substringAfterLast('/')
        if (filename.startsWith(".")) return false  // skip hidden files (._foo, .DS_Store, etc.)
        return filename.substringAfterLast('.').lowercase() in VIDEO_EXTENSIONS
    }

    private fun slugify(title: String, year: Int?): String {
        val base = if (year != null) "$title $year" else title
        return base.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    /**
     * Stable, non-empty item id. Uses the human slug when the title yields one, else a Jellyfin-id
     * fallback — non-Latin titles (CJK, Devanagari, …) slug to "" and would otherwise all collapse to
     * the same empty id and silently overwrite each other (Phase 53-B). Deterministic per item (depends
     * only on title/year/jellyfinId, never scan order). Detail URLs use jellyfinId, and
     * `MediaStore.resolve()` accepts it, so the fallback id is invisible to navigation.
     */
    private fun itemId(title: String, year: Int?, jellyfinId: String): String =
        slugify(title, year).ifBlank { "jf-$jellyfinId" }

    /**
     * Why `scanItem` would skip this Jellyfin item, for the post-scan skip report (Phase 53-D). Cheap:
     * mirrors the early returns of scanItem/scanMovie/scanSeries without probing or hitting TMDB.
     */
    fun classifySkip(jItem: JellyfinItem): String {
        if (jItem.type != "Movie" && jItem.type != "Series") return "unsupported-type"
        val config = configStore.current
        val libraries = config.libraries.filter { !it.skip && it.localPath.isNotBlank() }
        val jellyfinPath = jItem.path ?: return "no-path"
        val lib = libraries.firstOrNull { lib ->
            val prefix = lib.jellyfinPath.ifBlank { lib.localPath }
            prefix.isNotBlank() && jellyfinPath.startsWith(prefix)
        } ?: return "no-matching-library"
        val localPath = if (lib.jellyfinPath.isNotBlank())
            jellyfinPath.replaceFirst(lib.jellyfinPath, lib.localPath) else jellyfinPath
        if (!SystemFileSystem.exists(Path(localPath)))
            return if (jItem.type == "Movie") "file-not-found" else "dir-not-found"
        if (jItem.type == "Series" && findEpisodeFiles(localPath).isEmpty()) return "no-episode-files"
        return "other"
    }

    /** Phase 75 — fetch full cast + crew from TMDB and map to Person model. */
    suspend fun fetchCredits(tmdbId: Int, isMovie: Boolean): Pair<List<Person>, List<Person>> {
        if (!isMovie) {
            // Phase 76: use aggregate_credits for TV series to get total_episode_count per actor
            val agg = tmdb.getTvAggregateCredits(tmdbId)
            val cast = agg.cast.sortedBy { it.order }.map { m ->
                Person(
                    tmdbId = m.id,
                    name = m.name,
                    profilePath = m.profilePath,
                    character = m.roles.firstOrNull()?.character?.takeIf { it.isNotBlank() },
                    order = m.order,
                    type = "Actor",
                    episodeCount = m.totalEpisodeCount,
                )
            }
            val crew = agg.crew
                .flatMap { m -> m.jobs.map { j -> Triple(m, j.job, j.episodeCount) } }
                .distinctBy { (m, job, _) -> Pair(m.id, job) }
                .sortedWith(compareBy({ it.first.department }, { it.first.name }))
                .map { (m, job, _) ->
                    Person(
                        tmdbId = m.id,
                        name = m.name,
                        profilePath = m.profilePath,
                        job = job.takeIf { it.isNotBlank() },
                        department = m.department.takeIf { it.isNotBlank() },
                        order = 0,
                        type = "Director".takeIf { m.department.lowercase() == "directing" } ?: "Writer".takeIf { m.department.lowercase() == "writing" } ?: m.department,
                    )
                }
            return Pair(cast, crew)
        }
        val response = tmdb.getMovieFullCredits(tmdbId)
        val cast = response.cast.sortedBy { it.order }.map { m ->
            Person(
                tmdbId = m.id,
                name = m.name,
                profilePath = m.profilePath,
                character = m.character.takeIf { it.isNotBlank() },
                order = m.order,
                type = "Actor",
            )
        }
        val crew = response.crew
            .distinctBy { Pair(it.id, it.job) }
            .sortedWith(compareBy({ it.department }, { it.name }))
            .map { m ->
                Person(
                    tmdbId = m.id,
                    name = m.name,
                    profilePath = m.profilePath,
                    job = m.job.takeIf { it.isNotBlank() },
                    department = m.department.takeIf { it.isNotBlank() },
                    order = 0,
                    type = "Director".takeIf { m.department.lowercase() == "directing" } ?: "Writer".takeIf { m.department.lowercase() == "writing" } ?: m.department,
                )
            }
        return Pair(cast, crew)
    }

    /** Phase 76: fetch guest stars + crew for a single episode from TMDB. */
    suspend fun fetchEpisodeCredits(seriesId: Int, season: Int, episode: Int): Pair<List<Person>, List<Person>> {
        val creds = tmdb.getEpisodeCredits(seriesId, season, episode)
        val guests = creds.guestStars.sortedBy { it.order }.map { m ->
            Person(
                tmdbId = m.id,
                name = m.name,
                profilePath = m.profilePath,
                character = m.character.takeIf { it.isNotBlank() },
                order = m.order,
                type = "Actor",
            )
        }
        val crew = creds.crew
            .distinctBy { Pair(it.id, it.job) }
            .sortedWith(compareBy({ it.department }, { it.name }))
            .map { m ->
                Person(
                    tmdbId = m.id,
                    name = m.name,
                    profilePath = m.profilePath,
                    job = m.job.takeIf { it.isNotBlank() },
                    department = m.department.takeIf { it.isNotBlank() },
                    order = 0,
                    type = "Director".takeIf { m.department.lowercase() == "directing" } ?: "Writer".takeIf { m.department.lowercase() == "writing" } ?: m.department,
                )
            }
        return Pair(guests, crew)
    }

    suspend fun translationLanguages(tmdbId: Int, isMovie: Boolean): List<String> =
        tmdb.getTranslationLanguages(tmdbId, isMovie)

    suspend fun searchMovieTmdb(query: String, year: Int?) =
        tmdb.searchMovieAll(query, year)

    suspend fun searchTvTmdb(query: String, year: Int?) =
        tmdb.searchTvAll(query, year)

    /**
     * Fetches all localized titles from TMDB and folds in the resolved-language title and
     * originalLanguage→originalTitle. Returns an empty map if tmdbId is null.
     */
    private suspend fun buildTitlesByLang(
        tmdbId: Int?,
        isMovie: Boolean,
        resolvedTitle: String?,
        resolvedLang: String?,
        originalTitle: String?,
    ): Map<String, String> {
        if (tmdbId == null) return emptyMap()
        val base = tmdb.getTranslatedTitles(tmdbId, isMovie).toMutableMap()
        if (!resolvedTitle.isNullOrBlank() && !resolvedLang.isNullOrBlank()) {
            base[LanguageResolver.normalize(resolvedLang)] = resolvedTitle
        }
        if (!originalTitle.isNullOrBlank() && !resolvedLang.isNullOrBlank()) {
            val normLang = LanguageResolver.normalize(resolvedLang)
            if (!base.containsKey(normLang)) base[normLang] = originalTitle
        }
        return base
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun epochSeconds(): Long = platform.posix.time(null)
}
