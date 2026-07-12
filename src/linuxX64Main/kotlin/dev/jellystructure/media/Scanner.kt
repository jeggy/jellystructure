package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinEpisodeItem
import dev.jellystructure.auth.JellyfinItem
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.MediaTrailer
import dev.jellystructure.model.Person
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LanguageResolver
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.util.isoToEpochSeconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val TITLE_YEAR_RE = Regex("""^(.+?)\s+\((\d{4})\)\s*$""")
// Season allows up to 4 digits so year-as-season numbering (e.g. S2025E01) parses (Phase 53-C).
private val SEASON_EP_RE = Regex("""[Ss](\d{1,4})[Ee](\d{1,3})""")
// Fallback for the Kodi/XBMC "2x01" numbering some downloads carry instead of S02E01. Anchored on
// both sides (\b) with a 2-digit season cap and a ≥2-digit episode so it can't latch onto a
// resolution token like 1280x720 (there is no word boundary inside "1280x720", and "80x720" is
// excluded because the season is preceded by a digit). Only consulted when SEASON_EP_RE misses.
private val ALT_SEASON_EP_RE = Regex("""\b(\d{1,2})x(\d{2,3})\b""")
// Phase 149 — multi-episode filename tails, anchored at the START of whatever follows the first
// SxxEyy/AxB match (so they only ever consume immediately-adjacent tokens, never something further
// into the filename like a resolution or release-group tag). No trailing \b on the per-token head
// regexes below: digits/letters are all \w, so "E02" immediately followed by "E03" (or "x02" by "x03")
// has no word-boundary between them — the walk only needs the START anchor per step.
private val EP_RANGE_TAIL_RE = Regex("""^\s*-\s*[Ee](\d{1,3})""")
private val EP_TOKEN_HEAD_RE = Regex("""^[Ee](\d{1,3})""")
private val ALT_EP_TOKEN_HEAD_RE = Regex("""^x(\d{2,3})""")
// Bug fix: a chained "1x01x02x03" is one solid run of word characters (digits + the letter x are both
// \w), so ALT_SEASON_EP_RE's \b-anchored single-pair form can never match a substring in the MIDDLE of
// that chain (there's no boundary between "01" and the next "x") — it would silently fail to match this
// filename at all, not just capture one episode. This variant requires ≥1 repeated "xNN" tail and
// anchors \b only at the true start/end of the whole chain.
private val ALT_MULTI_EP_HEAD_RE = Regex("""\b(\d{1,2})x(\d{2,3})((?:x\d{2,3})+)\b""")
private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "m2ts")

/**
 * Phase 149: a multi-episode file (`S01E01E02E03.mkv`, `S01E01-E03.mkv`, `1x01x02x03.mkv`) spans
 * several episode numbers — returns the FULL contained list, not just the first. The overwhelmingly
 * common single-episode filename still returns a one-element list, so this is the only parse function
 * needed; there is no separate single-episode variant to keep in sync. A top-level pure function (no
 * `Scanner` instance state involved) so it's directly unit-testable — see `ScannerFilenameParsingTest`.
 */
internal fun parseSeasonEpisodes(path: String): Pair<Int?, List<Int>> {
    val filename = path.substringAfterLast('/')
    val seasonMatch = SEASON_EP_RE.find(filename)
    if (seasonMatch != null) {
        val season = seasonMatch.groupValues[1].toIntOrNull()
        val firstEp = seasonMatch.groupValues[2].toIntOrNull() ?: return Pair(season, emptyList())
        val rest = filename.substring(seasonMatch.range.last + 1)
        // Range form: SxxEyy-Ezz — the middle numbers aren't in the filename, so expand them.
        val rangeMatch = EP_RANGE_TAIL_RE.find(rest)
        if (rangeMatch != null) {
            val lastEp = rangeMatch.groupValues[1].toIntOrNull()
            if (lastEp != null && lastEp > firstEp) return Pair(season, (firstEp..lastEp).toList())
        }
        // Repeated-token form: SxxEyyEzzEww… — scan consecutive trailing E\d+ tokens.
        val episodes = mutableListOf(firstEp)
        var remaining = rest
        while (true) {
            val tokenMatch = EP_TOKEN_HEAD_RE.find(remaining) ?: break
            episodes += tokenMatch.groupValues[1].toIntOrNull() ?: break
            remaining = remaining.substring(tokenMatch.range.last + 1)
        }
        return Pair(season, episodes)
    }
    // Fallback Kodi/XBMC numbering: 1x01x02x03 (repeated-token form only — no dash-range convention).
    // Try the chained multi-episode pattern first (it requires ≥1 repeat and won't match a lone
    // "2x01"); fall back to the plain single-pair pattern when there's no chain.
    val altChainMatch = ALT_MULTI_EP_HEAD_RE.find(filename)
    if (altChainMatch != null) {
        val season = altChainMatch.groupValues[1].toIntOrNull()
        val firstEp = altChainMatch.groupValues[2].toIntOrNull() ?: return Pair(season, emptyList())
        val episodes = mutableListOf(firstEp)
        var remaining = altChainMatch.groupValues[3]   // the "x02x03…" tail, captured as one span
        while (true) {
            val tokenMatch = ALT_EP_TOKEN_HEAD_RE.find(remaining) ?: break
            episodes += tokenMatch.groupValues[1].toIntOrNull() ?: break
            remaining = remaining.substring(tokenMatch.range.last + 1)
        }
        return Pair(season, episodes)
    }
    val altMatch = ALT_SEASON_EP_RE.find(filename) ?: return Pair(null, emptyList())
    val season = altMatch.groupValues[1].toIntOrNull()
    val firstEp = altMatch.groupValues[2].toIntOrNull() ?: return Pair(season, emptyList())
    return Pair(season, listOf(firstEp))
}

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

    /**
     * Phase 94: genre provenance. TMDB owns genres it sets, but the user's edits are sticky across a
     * re-sync. Derived from the stored baseline [MediaItem.tmdbGenres]: genres the user added (not in the
     * baseline) survive, genres the user removed (in the baseline, gone from `genres`) stay gone, and
     * everything else follows fresh TMDB. Caller must also set `tmdbGenres = newTmdb`.
     */
    private fun mergeUserGenres(prior: MediaItem, newTmdb: List<String>): List<String> {
        val baseline = prior.tmdbGenres
        val userAdded = prior.genres.filterNot { it in baseline }
        val userRemoved = baseline.filterNot { it in prior.genres }
        return (newTmdb.filterNot { it in userRemoved } + userAdded).distinct()
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
        val libraryId = lib.jellyfinId.ifBlank { null }
        return when (jItem.type) {
            "Movie" -> scanMovie(jItem, localPath, effectiveFallback, libraryId)
            "Series" -> scanSeries(jItem, localPath, effectiveFallback, libraryId)
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

    private suspend fun scanMovie(jItem: JellyfinItem, localPath: String, fallback: String, libraryId: String?): MediaItem? {
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
        val localized = tmdbId?.let { tmdb.getMovieDetailsLocalized(it, langPriority) }
        val details = localized?.details
        val resolvedLang = localized?.let { it.language ?: langPriority.lastOrNull() }

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
        val certifications = tmdbFinalId?.let { tmdb.getMovieCertifications(it) } ?: emptyMap()
        val trailer = tmdbFinalId?.let { buildTrailer(tmdb.getMovieVideos(it, details?.originalLanguage.orEmpty())) }
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
            // Phase 128: a zero-audio-track title (e.g. a corrupt/truncated file) shouldn't display a
            // language it never earned — the fallback above exists to keep the TMDB query above working,
            // not to fabricate a display language.
            resolvedLanguage = resolvedLang.takeIf { audioLangs.isNotEmpty() },
            posterPath = details?.posterPath,
            backdropPath = details?.backdropPath,
            overview = details?.overview?.takeIf { it.isNotBlank() },
            genres = details?.genres?.map { it.name } ?: emptyList(),
            tmdbGenres = details?.genres?.map { it.name } ?: emptyList(),  // Phase 94: baseline = TMDB list (fresh scan, no user edits yet)
            studio = primaryCompany?.name,
            studioTmdbId = primaryCompany?.id,
            studioLogoPath = primaryCompany?.logoPath,
            secondaryStudios = details?.productionCompanies?.drop(1)?.map { it.name }?.filter { it.isNotBlank() }?.distinct() ?: emptyList(),
            tracks = tracks,
            issueCount = issueCount,
            languageMix = false,
            scannedAt = epochSeconds(),
            addedAt = jItem.dateCreated?.let { isoToEpochSeconds(it) },
            jellyfinUpdatedAt = jItem.dateLastSaved?.let { isoToEpochSeconds(it) },
            jellyfinLockData = jItem.lockData,
            jellyfinLockedFields = jItem.lockedFields,
            tags = jItem.tags,
            titlesByLang = titlesByLang,
            cast = cast,
            crew = crew,
            imdbId = extIds?.imdbId?.takeIf { it.isNotBlank() },
            runtime = details?.runtime,
            certifications = certifications,
            trailer = trailer,
            libraryId = libraryId,
        )
    }

    private suspend fun scanSeries(jItem: JellyfinItem, localPath: String, fallback: String, libraryId: String?): MediaItem? {
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

        // R82: Fetch per-episode static metadata (id, season name) from Jellyfin at scan time so
        // DetailService no longer needs a live Jellyfin call just to map (season, ep) → Jellyfin id.
        val scanBaseUrl = configStore.current.apiKeys.jellyfinUrl
        val scanAdminToken = configStore.current.apiKeys.jellyfinToken
        val jfEpsMeta = if (scanBaseUrl.isNotBlank() && scanAdminToken.isNotBlank()) {
            jellyfinClient.getSeriesEpisodesMeta(scanBaseUrl.trimEnd('/'), scanAdminToken, jItem.id)
        } else emptyList()
        val jfBySeasonEp = jfEpsMeta.associateBy { (it.parentIndexNumber ?: 0) to (it.indexNumber ?: 0) }
        val seasonNamesMap: Map<Int, String> = jfEpsMeta
            .groupBy { it.parentIndexNumber ?: 0 }
            .mapValues { (_, eps) -> eps.firstOrNull()?.seasonName ?: "" }
            .filterValues { it.isNotBlank() }

        val episodes = mutableListOf<Episode>()
        for (file in filesToProbe) {
            val tracks = FfprobeRunner.probe(file)
            // Phase 128: diagnose() re-probes with stderr kept, so the scan log says WHY (corrupt,
            // unreadable, etc.) instead of just that the track list came back empty. Rare path — only
            // runs for a file that already produced zero tracks — so the extra ffprobe call is fine.
            if (tracks.isEmpty()) {
                val reason = FfprobeRunner.diagnose(file)
                Logger.warn("ffprobe returned no tracks for episode: $file (${reason.status}: ${reason.detail})", "scan")
            }
            val epIssueCount = tracks.count {
                (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
            }
            val audioLangs = tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
            val epLangPriority = LanguageResolver.priorityList(audioLangs, fallback)
            val epResolvedLang = epLangPriority.firstOrNull()
            val (seasonNum, epNums) = parseSeasonEpisodes(file)
            // Phase 149: a multi-episode file (`S01E01E02E03.mkv`) yields >1 contained episode number.
            // An unparseable filename still yields exactly one Episode (episodeNumber = null), matching
            // pre-149 behaviour — partCount is 1 either way.
            val partCount = epNums.size.coerceAtLeast(1)
            val partEpisodeNums: List<Int?> = if (epNums.isEmpty()) listOf(null) else epNums
            // Chapters are only worth reading for the (rare) multi-episode case — skip the extra
            // ffprobe invocation entirely for the overwhelmingly common single-episode file.
            val chapterMarkers = if (partCount > 1) FfprobeRunner.chapters(file) else emptyList()
            val hasMatchingChapters = chapterMarkers.size == partCount

            partEpisodeNums.forEachIndexed { partIdx, epNum ->
                // Fetch per-episode TMDB details in the episode's own resolved language — independent
                // per contained episode, exactly like a normal single-episode file.
                val epDetails = if (seriesTmdbId != null && seasonNum != null && epNum != null) {
                    tmdb.getEpisodeDetailsLocalized(seriesTmdbId, seasonNum, epNum, epLangPriority)
                } else null

                // Phase 76: fetch guest stars + episode crew from TMDB
                val (epGuests, epCrew) = if (seriesTmdbId != null && seasonNum != null && epNum != null) {
                    fetchEpisodeCredits(seriesTmdbId, seasonNum, epNum)
                } else Pair(emptyList(), emptyList())

                // R82: map (season, ep) → Jellyfin id from the pre-fetched meta
                val jfEp = if (seasonNum != null && epNum != null) jfBySeasonEp[seasonNum to epNum] else null

                episodes += Episode(
                    filename = file.substringAfterLast('/'),
                    path = file,
                    seasonNumber = seasonNum,
                    episodeNumber = epNum,
                    tracks = tracks,
                    issueCount = epIssueCount,
                    // Phase 128: honest per-episode display language — see the scanMovie comment above.
                    resolvedLanguage = epResolvedLang.takeIf { audioLangs.isNotEmpty() },
                    title = epDetails?.name?.takeIf { it.isNotBlank() },
                    overview = epDetails?.overview?.takeIf { it.isNotBlank() },
                    stillPath = epDetails?.stillPath,
                    tmdbEpisodeId = epDetails?.id,
                    guestStars = epGuests,
                    crew = epCrew,
                    jellyfinId = jfEp?.id,
                    runtime = epDetails?.runtime,
                    airDate = epDetails?.airDate?.takeIf { it.isNotBlank() },  // R148
                    jellyfinCreatedAt = jfEp?.dateCreated?.let { isoToEpochSeconds(it) },  // Phase 108
                    partIndex = partIdx,
                    partCount = partCount,
                    chapterStartMs = if (hasMatchingChapters) chapterMarkers[partIdx].startMs else null,
                    chapterEndMs = if (hasMatchingChapters) chapterMarkers[partIdx].endMs else null,
                    hasChapters = hasMatchingChapters,
                )
            }
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
            val mixDetails = seriesTmdbId?.let { tmdb.getTvDetailsLocalized(it, mixPriority) }?.details
            val mixNetwork = mixDetails?.networks?.firstOrNull()
            val mixTitlesByLang = buildTitlesByLang(seriesTmdbId, isMovie = false, mixDetails?.name, mixDetails?.originalLanguage, mixDetails?.originalName)
            val mixStoredYear = mixDetails?.firstAirDate?.take(4)?.toIntOrNull() ?: searchYear
            val (mixCast, mixCrew) = seriesTmdbId?.let { fetchCredits(it, isMovie = false, seasons = sortedEpisodes.mapNotNull { ep -> ep.seasonNumber }.distinct()) } ?: Pair(emptyList(), emptyList())
            val mixExtIds = seriesTmdbId?.let { tmdb.getExternalIds(it, isMovie = false) }
            val mixCertifications = seriesTmdbId?.let { tmdb.getTvCertifications(it) } ?: emptyMap()
            val mixTrailer = seriesTmdbId?.let { buildTrailer(tmdb.getTvVideos(it, mixDetails?.originalLanguage.orEmpty())) }
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
                // Phase 128: majorityLang is already null when no episode has any audio; the explicit
                // check here just makes that guarantee robust rather than incidental.
                resolvedLanguage = majorityLang.takeIf { firstTracks.any { t -> t.kind == TrackKind.AUDIO } },
                posterPath = mixDetails?.posterPath,
                backdropPath = mixDetails?.backdropPath,
                overview = mixDetails?.overview?.takeIf { it.isNotBlank() },
                genres = mixDetails?.genres?.map { it.name } ?: emptyList(),
                tmdbGenres = mixDetails?.genres?.map { it.name } ?: emptyList(),  // Phase 94: baseline (fresh scan)
                network = mixNetwork?.name,
                networkTmdbId = mixNetwork?.id,
                networkLogoPath = mixNetwork?.logoPath,
                tracks = firstTracks,
                episodes = sortedEpisodes,
                issueCount = totalIssueCount,
                languageMix = true,
                scannedAt = epochSeconds(),
                addedAt = jItem.dateCreated?.let { isoToEpochSeconds(it) },
                jellyfinUpdatedAt = jItem.dateLastSaved?.let { isoToEpochSeconds(it) },
                jellyfinLockData = jItem.lockData,
                jellyfinLockedFields = jItem.lockedFields,
                tags = jItem.tags,
                titlesByLang = mixTitlesByLang,
                cast = mixCast,
                crew = mixCrew,
                imdbId = mixExtIds?.imdbId?.takeIf { it.isNotBlank() },
                tvdbId = mixExtIds?.tvdbId,
                seasonNames = seasonNamesMap,
                certifications = mixCertifications,
                trailer = mixTrailer,
                libraryId = libraryId,
            )
        }

        val audioLangs = firstTracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
        val langPriority = LanguageResolver.priorityList(audioLangs, fallback)

        val localized = seriesTmdbId?.let { tmdb.getTvDetailsLocalized(it, langPriority) }
        val details = localized?.details
        val resolvedLang = localized?.let { it.language ?: langPriority.lastOrNull() }

        val tvTmdbFinalId = details?.id ?: seriesTmdbId
        val tvTitlesByLang = buildTitlesByLang(tvTmdbFinalId, isMovie = false, details?.name, details?.originalLanguage, details?.originalName)
        val tvStoredYear = details?.firstAirDate?.take(4)?.toIntOrNull() ?: searchYear
        val (tvCast, tvCrew) = tvTmdbFinalId?.let { fetchCredits(it, isMovie = false, seasons = sortedEpisodes.mapNotNull { ep -> ep.seasonNumber }.distinct()) } ?: Pair(emptyList(), emptyList())
        val tvExtIds = tvTmdbFinalId?.let { tmdb.getExternalIds(it, isMovie = false) }
        val tvCertifications = tvTmdbFinalId?.let { tmdb.getTvCertifications(it) } ?: emptyMap()
        val tvTrailer = tvTmdbFinalId?.let { buildTrailer(tmdb.getTvVideos(it, details?.originalLanguage.orEmpty())) }
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
            // Phase 128: honest display language — see the scanMovie comment above.
            resolvedLanguage = resolvedLang.takeIf { audioLangs.isNotEmpty() },
            posterPath = details?.posterPath,
            backdropPath = details?.backdropPath,
            overview = details?.overview?.takeIf { it.isNotBlank() },
            genres = details?.genres?.map { it.name } ?: emptyList(),
            tmdbGenres = details?.genres?.map { it.name } ?: emptyList(),  // Phase 94: baseline = TMDB list (fresh scan, no user edits yet)
            network = details?.networks?.firstOrNull()?.name,
            networkTmdbId = details?.networks?.firstOrNull()?.id,
            networkLogoPath = details?.networks?.firstOrNull()?.logoPath,
            tracks = firstTracks,
            episodes = sortedEpisodes,
            issueCount = totalIssueCount,
            languageMix = false,
            scannedAt = epochSeconds(),
            addedAt = jItem.dateCreated?.let { isoToEpochSeconds(it) },
            jellyfinUpdatedAt = jItem.dateLastSaved?.let { isoToEpochSeconds(it) },
            jellyfinLockData = jItem.lockData,
            jellyfinLockedFields = jItem.lockedFields,
            tags = jItem.tags,
            titlesByLang = tvTitlesByLang,
            cast = tvCast,
            crew = tvCrew,
            imdbId = tvExtIds?.imdbId?.takeIf { it.isNotBlank() },
            tvdbId = tvExtIds?.tvdbId,
            seasonNames = seasonNamesMap,
            certifications = tvCertifications,
            trailer = tvTrailer,
            libraryId = libraryId,
        )
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
        val localized = tmdbId?.let { tmdb.getMovieDetailsLocalized(it, langPriority) } ?: return null
        val details = localized.details
        val resolvedLang = localized.language ?: langPriority.lastOrNull()
        val issueCount = tracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
        val primaryCompany = details.productionCompanies.firstOrNull()
        val tmdbTags = tmdb.getMovieKeywords(details.id)
        val syncExtIds = tmdb.getExternalIds(details.id, isMovie = true)
        val syncCertifications = tmdb.getMovieCertifications(details.id)
        val syncTrailer = buildTrailer(tmdb.getMovieVideos(details.id, details.originalLanguage))
        return item.copy(
            title = details.title,
            originalTitle = details.originalTitle.takeIf { it.isNotBlank() },
            tmdbId = details.id,
            year = details.releaseDate.take(4).toIntOrNull() ?: item.year,
            originalLanguage = details.originalLanguage.takeIf { it.isNotBlank() },
            // Phase 128: honest display language — see the scanMovie comment above.
            resolvedLanguage = resolvedLang.takeIf { audioLangs.isNotEmpty() },
            posterPath = details.posterPath,
            backdropPath = details.backdropPath,
            overview = details.overview.takeIf { it.isNotBlank() },
            genres = mergeUserGenres(item, details.genres.map { it.name }),  // Phase 94: keep user genre edits across sync
            tmdbGenres = details.genres.map { it.name },
            studio = primaryCompany?.name,
            studioTmdbId = primaryCompany?.id,
            studioLogoPath = primaryCompany?.logoPath,
            secondaryStudios = details.productionCompanies.drop(1).map { it.name }.filter { it.isNotBlank() }.distinct(),
            tracks = tracks,
            issueCount = issueCount,
            tags = mergeRepullTags(tmdbTags, item),
            scannedAt = epochSeconds(),
            imdbId = syncExtIds?.imdbId?.takeIf { it.isNotBlank() } ?: item.imdbId,
            runtime = details.runtime,
            certifications = syncCertifications.ifEmpty { item.certifications },
            trailer = syncTrailer,
            // Phase 142: self-heals if the library mapping changed since the last scan; `lib` above
            // already resolves by the stored item's local path (the correct direction for this call).
            libraryId = lib?.jellyfinId?.ifBlank { null } ?: item.libraryId,
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
        // Backfill missing jellyfinIds: fetch Jellyfin episode meta if the series has a jellyfinId
        // and any episode is still missing one (e.g. scanned before R82 or via old sync path).
        val scanBaseUrl = config.apiKeys.jellyfinUrl
        val scanAdminToken = config.apiKeys.jellyfinToken
        val jfBySeasonEp: Map<Pair<Int, Int>, JellyfinEpisodeItem> = if (
            item.jellyfinId != null && scanBaseUrl.isNotBlank() && scanAdminToken.isNotBlank() &&
            item.episodes.any { it.jellyfinId == null }
        ) {
            jellyfinClient.getSeriesEpisodesMeta(scanBaseUrl.trimEnd('/'), scanAdminToken, item.jellyfinId)
                .associateBy { (it.parentIndexNumber ?: 0) to (it.indexNumber ?: 0) }
        } else emptyMap()
        val episodes = mutableListOf<Episode>()
        for (file in episodeFiles) {
            val tracks = FfprobeRunner.probe(file)
            val epIssueCount = tracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
            val audioLangs = tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
            val epLangPriority = LanguageResolver.priorityList(audioLangs, fallback)
            val (seasonNum, epNums) = parseSeasonEpisodes(file)
            val partCount = epNums.size.coerceAtLeast(1)
            val partEpisodeNums: List<Int?> = if (epNums.isEmpty()) listOf(null) else epNums
            val chapterMarkers = if (partCount > 1) FfprobeRunner.chapters(file) else emptyList()
            val hasMatchingChapters = chapterMarkers.size == partCount

            partEpisodeNums.forEachIndexed { partIdx, epNum ->
                // Bug fix (dev-review addendum §2, Phase 149): this used to match by filename equality,
                // which collapses every episode of a multi-episode file (they share one filename) onto
                // the SAME stale match — corrupting all but one of them on every rescan. Match by
                // (season, episode) instead, which is unique per contained episode; only fall back to
                // filename equality for the (rare) unparseable-filename case, preserving the old behaviour
                // there since there's no (season, episode) identity to match on.
                val existingEp = if (epNum != null)
                    item.episodes.firstOrNull { it.seasonNumber == seasonNum && it.episodeNumber == epNum }
                else
                    item.episodes.firstOrNull { it.filename == file.substringAfterLast('/') }
                val epDetails = if (seriesTmdbId != null && seasonNum != null && epNum != null) {
                    tmdb.getEpisodeDetailsLocalized(seriesTmdbId, seasonNum, epNum, epLangPriority)
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
                    // Phase 128: honest per-episode display language — see the scanMovie comment above.
                    resolvedLanguage = epLangPriority.firstOrNull().takeIf { audioLangs.isNotEmpty() },
                    title = epDetails?.name?.takeIf { it.isNotBlank() } ?: existingEp?.title,
                    overview = epDetails?.overview?.takeIf { it.isNotBlank() } ?: existingEp?.overview,
                    stillPath = epDetails?.stillPath ?: existingEp?.stillPath,
                    tmdbEpisodeId = epDetails?.id ?: existingEp?.tmdbEpisodeId,
                    guestStars = epGuests,
                    crew = epCrew,
                    jellyfinId = existingEp?.jellyfinId
                        ?: (if (seasonNum != null && epNum != null) jfBySeasonEp[seasonNum to epNum]?.id else null),
                    runtime = epDetails?.runtime ?: existingEp?.runtime,
                    airDate = epDetails?.airDate?.takeIf { it.isNotBlank() } ?: existingEp?.airDate,  // R148
                    jellyfinCreatedAt = existingEp?.jellyfinCreatedAt
                        ?: (if (seasonNum != null && epNum != null) jfBySeasonEp[seasonNum to epNum]?.dateCreated?.let { isoToEpochSeconds(it) } else null),  // Phase 108
                    partIndex = partIdx,
                    partCount = partCount,
                    chapterStartMs = if (hasMatchingChapters) chapterMarkers[partIdx].startMs else existingEp?.chapterStartMs,
                    chapterEndMs = if (hasMatchingChapters) chapterMarkers[partIdx].endMs else existingEp?.chapterEndMs,
                    hasChapters = hasMatchingChapters || (existingEp?.hasChapters ?: false),
                )
            }
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
            seriesTmdbId?.let { tmdb.getTvDetailsLocalized(it, mixPriority) }?.details
        } else {
            val audioLangs = firstTracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
            val langPriority = LanguageResolver.priorityList(audioLangs, fallback)
            val localized = seriesTmdbId?.let { tmdb.getTvDetailsLocalized(it, langPriority) }
            resolvedLang = localized?.let { it.language ?: langPriority.lastOrNull() }
            localized?.details
        }
        val syncNetwork = updatedDetails?.networks?.firstOrNull()
        val syncSeriesFinalId = updatedDetails?.id ?: seriesTmdbId
        val tmdbTags = syncSeriesFinalId?.let { tmdb.getTvKeywords(it) } ?: emptyList()
        val syncSeriesExtIds = syncSeriesFinalId?.let { tmdb.getExternalIds(it, isMovie = false) }
        val syncSeriesCertifications = syncSeriesFinalId?.let { tmdb.getTvCertifications(it) } ?: emptyMap()
        val syncSeriesTrailer = syncSeriesFinalId?.let { buildTrailer(tmdb.getTvVideos(it, updatedDetails?.originalLanguage.orEmpty())) }
        return item.copy(
            title = updatedDetails?.name ?: item.title,
            originalTitle = updatedDetails?.originalName?.takeIf { it.isNotBlank() } ?: item.originalTitle,
            tmdbId = syncSeriesFinalId,
            year = updatedDetails?.firstAirDate?.take(4)?.toIntOrNull() ?: item.year,
            originalLanguage = updatedDetails?.originalLanguage?.takeIf { it.isNotBlank() } ?: item.originalLanguage,
            // Phase 128: honest display language — see the scanMovie comment above. Covers both the
            // majority-vote branch (already naturally null with zero audio anywhere) and the single-
            // language branch, uniformly, off the item-level tracks (= firstTracks).
            resolvedLanguage = resolvedLang.takeIf { firstTracks.any { t -> t.kind == TrackKind.AUDIO } },
            posterPath = updatedDetails?.posterPath ?: item.posterPath,
            backdropPath = updatedDetails?.backdropPath ?: item.backdropPath,
            overview = updatedDetails?.overview?.takeIf { it.isNotBlank() } ?: item.overview,
            genres = updatedDetails?.genres?.map { it.name }?.let { mergeUserGenres(item, it) } ?: item.genres,  // Phase 94
            tmdbGenres = updatedDetails?.genres?.map { it.name } ?: item.tmdbGenres,
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
            certifications = syncSeriesCertifications.ifEmpty { item.certifications },
            trailer = syncSeriesTrailer,
            libraryId = lib?.jellyfinId?.ifBlank { null } ?: item.libraryId,   // Phase 142: self-heal
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
                tmdb.getEpisodeDetailsLocalized(seriesTmdbId, seasonNumber, epNum, epLangPriority)
            } else null
            updatedEpisodes[idx] = ep.copy(
                tracks = tracks,
                issueCount = epIssueCount,
                // Phase 128: honest per-episode display language — see the scanMovie comment above.
                resolvedLanguage = epLangPriority.firstOrNull().takeIf { audioLangs.isNotEmpty() },
                title = epDetails?.name?.takeIf { it.isNotBlank() } ?: ep.title,
                overview = epDetails?.overview?.takeIf { it.isNotBlank() } ?: ep.overview,
                stillPath = epDetails?.stillPath ?: ep.stillPath,
                tmdbEpisodeId = epDetails?.id ?: ep.tmdbEpisodeId,
                runtime = epDetails?.runtime ?: ep.runtime,
                airDate = epDetails?.airDate?.takeIf { it.isNotBlank() } ?: ep.airDate,  // R148
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
        // Series have an explicit language override (the language-mix control writes it to
        // resolvedLanguage); honour it as the first TMDB query language. Movies have NO manual
        // override — their resolvedLanguage is always auto-derived from the audio order — so treating
        // a movie's stale resolvedLanguage as an override would pin the old language forever and make
        // reordering audio (or changing the fallback) unable to ever change the fetched language.
        // For movies, follow the current audio order instead.
        val overrideLang = if (item.kind == MediaKind.TV_SHOW)
            item.resolvedLanguage?.ifBlank { null }?.let { LanguageResolver.normalize(it) }
        else null
        val langPriority = if (overrideLang != null && basePriority.firstOrNull() != overrideLang)
            listOf(overrideLang) + basePriority.filter { it != overrideLang }
        else basePriority

        return when (item.kind) {
            MediaKind.MOVIE -> {
                val tmdbId = item.tmdbId ?: tmdb.searchMovie(item.title, item.year)?.id
                val localized = tmdbId?.let { tmdb.getMovieDetailsLocalized(it, langPriority) }
                    ?: return null
                val details = localized.details
                // Resolve the stored language through the SAME shared resolver the UI uses, fed with
                // the languages TMDB actually has — so resolvedLanguage and the on-screen trace always
                // agree. Falls back to the language we actually fetched content in.
                val available = tmdb.getTranslationLanguages(tmdbId, isMovie = true).toSet()
                val resolvedLang = LanguageResolver.resolve(sourceTracks, fallback, available).language
                    ?: localized.language
                val rescanCompany = details.productionCompanies.firstOrNull()
                val rescanTmdbTags = tmdb.getMovieKeywords(details.id)
                val rescanMovieExtIds = tmdb.getExternalIds(details.id, isMovie = true)
                val rescanCertifications = tmdb.getMovieCertifications(details.id)
                val rescanTrailer = buildTrailer(tmdb.getMovieVideos(details.id, details.originalLanguage))
                val (rescanCast, rescanCrew) = fetchCredits(details.id, isMovie = true)
                item.copy(
                    title = details.title,
                    originalTitle = details.originalTitle.takeIf { it.isNotBlank() },
                    tmdbId = details.id,
                    year = details.releaseDate.take(4).toIntOrNull() ?: item.year,
                    originalLanguage = details.originalLanguage.takeIf { it.isNotBlank() },
                    // Phase 128: honest display language — see the scanMovie comment above.
                    resolvedLanguage = resolvedLang.takeIf { audioLangs.isNotEmpty() },
                    posterPath = details.posterPath,
                    backdropPath = details.backdropPath,
                    overview = details.overview.takeIf { it.isNotBlank() },
                    genres = mergeUserGenres(item, details.genres.map { it.name }),  // Phase 94: keep user genre edits across sync
                    tmdbGenres = details.genres.map { it.name },
                    studio = rescanCompany?.name,
                    studioTmdbId = rescanCompany?.id,
                    studioLogoPath = rescanCompany?.logoPath,
                    secondaryStudios = details.productionCompanies.drop(1).map { it.name }.filter { it.isNotBlank() }.distinct(),
                    tags = mergeRepullTags(rescanTmdbTags, item),
                    imdbId = rescanMovieExtIds?.imdbId?.takeIf { it.isNotBlank() } ?: item.imdbId,
                    cast = rescanCast,
                    crew = rescanCrew,
                    runtime = details.runtime,
                    certifications = rescanCertifications.ifEmpty { item.certifications },
                    trailer = rescanTrailer,
                    libraryId = lib?.jellyfinId?.ifBlank { null } ?: item.libraryId,   // Phase 142: self-heal
                )
            }
            MediaKind.TV_SHOW -> {
                val tmdbId = item.tmdbId ?: tmdb.searchTv(item.title, item.year)?.id
                // When the user has an explicit language override, accept a title-only TMDB result
                // (non-blank name, blank overview) rather than falling through to English. Minority-
                // language original shows (e.g. Faroese) often have no contributed overview on TMDB
                // but do have the correct title in the original language.
                val localized = tmdbId?.let {
                    tmdb.getTvDetailsLocalized(it, langPriority, acceptTitleOnly = overrideLang != null)
                } ?: return null
                val details = localized.details
                val resolvedLang = localized.language ?: langPriority.lastOrNull()
                // Re-fetch per-episode TMDB details using the series langPriority, which already
                // puts the user's selected series language first, then falls through the chain.
                val updatedEpisodes = item.episodes.map { ep ->
                    val s = ep.seasonNumber
                    val e = ep.episodeNumber
                    if (s != null && e != null) {
                        val epDetails = tmdb.getEpisodeDetailsLocalized(details.id, s, e, langPriority)
                        if (epDetails != null) ep.copy(
                            title = epDetails.name.takeIf { it.isNotBlank() },
                            overview = epDetails.overview.takeIf { it.isNotBlank() },
                            stillPath = epDetails.stillPath,
                            tmdbEpisodeId = epDetails.id,
                            // Phase 128: the series' resolved language is applied per-episode here, but
                            // an episode with no audio tracks of its own (e.g. a corrupt file) shouldn't
                            // inherit it — check THIS episode's tracks, not the series-level audioLangs.
                            resolvedLanguage = resolvedLang.takeIf { ep.tracks.any { t -> t.kind == TrackKind.AUDIO } },
                            runtime = epDetails.runtime ?: ep.runtime,
                            airDate = epDetails.airDate?.takeIf { it.isNotBlank() } ?: ep.airDate,  // R148
                        ) else ep
                    } else ep
                }
                val rescanNetwork = details.networks.firstOrNull()
                val rescanTmdbTags = tmdb.getTvKeywords(details.id)
                val rescanTvExtIds = tmdb.getExternalIds(details.id, isMovie = false)
                val rescanTvCertifications = tmdb.getTvCertifications(details.id)
                val rescanTvTrailer = buildTrailer(tmdb.getTvVideos(details.id, details.originalLanguage))
                val (rescanTvCast, rescanTvCrew) = fetchCredits(details.id, isMovie = false, seasons = item.episodes.mapNotNull { it.seasonNumber }.distinct())
                item.copy(
                    title = details.name,
                    originalTitle = details.originalName.takeIf { it.isNotBlank() },
                    tmdbId = details.id,
                    year = details.firstAirDate.take(4).toIntOrNull() ?: item.year,
                    originalLanguage = details.originalLanguage.takeIf { it.isNotBlank() },
                    // Phase 128: honest display language — see the scanMovie comment above, off the
                    // item-level sourceTracks/audioLangs computed earlier in this function.
                    resolvedLanguage = resolvedLang.takeIf { audioLangs.isNotEmpty() },
                    posterPath = details.posterPath,
                    backdropPath = details.backdropPath,
                    overview = details.overview.takeIf { it.isNotBlank() },
                    genres = mergeUserGenres(item, details.genres.map { it.name }),  // Phase 94: keep user genre edits across sync
                    tmdbGenres = details.genres.map { it.name },
                    network = rescanNetwork?.name ?: item.network,
                    networkTmdbId = rescanNetwork?.id ?: item.networkTmdbId,
                    networkLogoPath = rescanNetwork?.logoPath ?: item.networkLogoPath,
                    tags = mergeRepullTags(rescanTmdbTags, item),
                    episodes = updatedEpisodes,
                    imdbId = rescanTvExtIds?.imdbId?.takeIf { it.isNotBlank() } ?: item.imdbId,
                    tvdbId = rescanTvExtIds?.tvdbId ?: item.tvdbId,
                    cast = rescanTvCast,
                    crew = rescanTvCrew,
                    certifications = rescanTvCertifications.ifEmpty { item.certifications },
                    trailer = rescanTvTrailer,
                    libraryId = lib?.jellyfinId?.ifBlank { null } ?: item.libraryId,   // Phase 142: self-heal
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

    /**
     * Phase 75 — fetch full cast + crew from TMDB and map to Person model.
     * Phase 80 — for TV, [seasons] (the show's season numbers) drives per-season `aggregate_credits`
     * so each cast member carries real `seasonEpisodeCounts` (accurate season-level presence).
     */
    suspend fun fetchCredits(tmdbId: Int, isMovie: Boolean, seasons: List<Int> = emptyList()): Pair<List<Person>, List<Person>> {
        if (!isMovie) {
            // Phase 76: use aggregate_credits for TV series to get total_episode_count per actor
            val agg = tmdb.getTvAggregateCredits(tmdbId)
            // Phase 80: per-season episode counts per cast member (personId -> season -> count)
            val perSeason = HashMap<Int, MutableMap<String, Int>>()
            for (s in seasons.filter { it > 0 }.distinct().sorted()) {
                val sc = tmdb.getTvSeasonAggregateCredits(tmdbId, s)
                for (m in sc.cast) {
                    if (m.totalEpisodeCount > 0) perSeason.getOrPut(m.id) { mutableMapOf() }[s.toString()] = m.totalEpisodeCount
                }
            }
            val cast = agg.cast.sortedBy { it.order }.map { m ->
                Person(
                    tmdbId = m.id,
                    name = m.name,
                    profilePath = m.profilePath,
                    character = m.roles.firstOrNull()?.character?.takeIf { it.isNotBlank() },
                    order = m.order,
                    type = "Actor",
                    episodeCount = m.totalEpisodeCount,
                    seasonEpisodeCounts = perSeason[m.id]?.toMap() ?: emptyMap(),
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

    /** Phase 130: resolve one [TmdbVideo] selection into a stored [MediaTrailer], fetching the Vimeo
     *  thumbnail once at ingest (YouTube thumbnails are derived client-side from the key — no fetch). */
    private suspend fun buildTrailer(video: dev.jellystructure.tmdb.TmdbVideo?): MediaTrailer? {
        if (video == null) return null
        val site = if (video.site.equals("Vimeo", ignoreCase = true)) "vimeo" else "youtube"
        val thumb = if (site == "vimeo") tmdb.resolveVimeoThumb(video.key) else null
        return MediaTrailer(site = site, key = video.key, name = video.name, thumb = thumb)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun epochSeconds(): Long = platform.posix.time(null)
}
