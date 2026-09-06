package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinEpisodeItem
import dev.jellystructure.auth.JellyfinItem
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.log.Logger
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.MediaTrailer
import dev.jellystructure.model.Person
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LanguageResolver
import dev.jellystructure.tmdb.Localized
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.tmdb.TmdbMovieDetails
import dev.jellystructure.util.isoToEpochSeconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

// Bug fix (auto-play-next resumes minutes in, on shows with duplicate episode files): when Jellyfin
// has two items for the same (season, episode) — duplicate physical files each imported separately —
// `associateBy` keeps whichever one is LAST in the `/Episodes` response, and that response carries no
// SortBy param, so its order isn't guaranteed stable across scans. A flip hands a later `jellyfinId`
// lookup a different Jellyfin item than a previous scan did; since resume position is tied to Jellyfin's
// per-item `PlaybackPositionTicks` and nothing here migrates it across ids, the "new" primary can carry
// a stale position from whatever it was last played as. Grouping + a path-based tiebreak (rather than
// response order) makes the winner the same physical file on every scan, independent of API ordering.
private fun bySeasonEpDeterministic(items: List<JellyfinEpisodeItem>): Map<Pair<Int, Int>, JellyfinEpisodeItem> =
    items.groupBy { (it.parentIndexNumber ?: 0) to (it.indexNumber ?: 0) }
        .mapValues { (_, group) -> group.minByOrNull { it.path ?: it.id } ?: group.first() }

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

/**
 * Phase 160 (FR-SCAN2): when [filenameParsed] found no episode numbers at all — a filename
 * convention `parseSeasonEpisodes` doesn't recognize (e.g. La Linea's bare `SEE` scheme,
 * `La Linea - 101.mkv`) — fall back to the season/episode Jellyfin's own scanner already
 * assigned to the same file (matched by path via `jfByPath`, upstream). Jellyfin's numbers are
 * naming-convention-agnostic (folder structure + its own broader heuristics), so this recovers a
 * number for any file Jellyfin placed correctly even when our regexes can't parse the filename at
 * all. Never overrides a filename parse that found at least one episode — the filename is the
 * more specific signal when it succeeds.
 */
internal fun resolveSeasonEpisode(filenameParsed: Pair<Int?, List<Int>>, jfSeason: Int?, jfEpisode: Int?): Pair<Int?, List<Int>> =
    if (filenameParsed.second.isEmpty() && jfSeason != null && jfEpisode != null) {
        Pair(jfSeason, listOf(jfEpisode))
    } else {
        filenameParsed
    }

/**
 * Phase 168 (FR-168-3): filename-only metadata for a music video, matching Jellyfin's own recognized
 * `Artist - Title.ext` convention. Split on the *first* " - " only: left side → artist, right side
 * (extension stripped) → title. No separator found → the whole filename (minus extension) becomes the
 * title, artist stays null. Deliberately does not parse album/track/year — filename-only, nothing else.
 */
internal fun parseMusicVideoArtistTitle(filename: String): Pair<String?, String> {
    val base = filename.substringBeforeLast('.')
    val sepIdx = base.indexOf(" - ")
    if (sepIdx < 0) return Pair(null, base)
    val artist = base.substring(0, sepIdx).trim()
    val title = base.substring(sepIdx + 3).trim()
    return Pair(artist.ifBlank { null }, title.ifBlank { base })
}

/**
 * Phase 191 — an operator's explicit [dev.jellystructure.model.MediaItem.metadataLanguage] choice must
 * win the TMDB query-language order on EVERY fetch, not only [Scanner.rescanMetadata] (the one path an
 * operator actually triggers by hand). Before this, a routine scheduled scan or a per-item Sync rebuilt
 * [MediaItem] from scratch via [LanguageResolver.priorityList] alone, ignoring the override entirely —
 * [MediaStore.preserveMetadataLanguage] then restored the *field* post-hoc, but the metadata it had
 * already fetched (title/overview/resolvedLanguage) stayed in the wrong language. Extracted from the
 * inline logic Phase 184 wrote for rescanMetadata so every call site shares one implementation.
 */
internal fun normalizedMetadataLanguageOverride(raw: String?): String? =
    raw?.ifBlank { null }?.let { LanguageResolver.normalize(it) }

/** Moves [overrideLang] to the front of [basePriority] (de-duplicated), or returns [basePriority]
 *  unchanged when there's no override or it's already first. See [normalizedMetadataLanguageOverride]. */
internal fun overriddenLangPriority(basePriority: List<String>, overrideLang: String?): List<String> =
    if (overrideLang != null && basePriority.firstOrNull() != overrideLang)
        listOf(overrideLang) + basePriority.filter { it != overrideLang }
    else basePriority

class Scanner(
    private val configStore: ConfigStore,
    private val tmdb: TmdbClient,
    private val jellyfinClient: JellyfinClient,
    private val jsTagStore: JsTagStore,
    // Phase 183 (FR-183-4) — lets scanSeries skip a per-episode TMDB re-fetch when the previous scan
    // already holds good data for that episode (the single largest lever against the reported rate-
    // limit incident: a re-scan of an already-fully-fetched series drops from ~1,500-2,000 requests to
    // ~0). Nullable + defaulted so any test construction that doesn't care about this optimization
    // doesn't need a MediaStore just to compile; a null store simply never skips (today's behavior).
    private val store: MediaStore? = null,
) {
    // Phase 183 (FR-183-3) — bounds concurrent per-episode dispatch (ffprobe + TMDB) across EVERY
    // series this Scanner processes, not per-series. Without this, one worker scanning a single large
    // series could alone dispatch hundreds of concurrent per-episode TMDB fetches — exactly what the
    // reported incident's log proved was happening (18 simultaneous requests, all one worker id). Sized
    // off the operator's own scan_workers so "N workers" means something end to end, not "N items, each
    // fanning out unboundedly underneath." Fixed at construction, not live-rescalable like
    // runPipelineStepPool's worker count — kotlinx.coroutines.sync.Semaphore has no resize API, and a
    // live-rescalable version of this specific gate wasn't judged worth the extra complexity; a
    // scan_workers config change takes effect on this gate at the next process restart.
    private val episodeFanoutGate = Semaphore(
        (configStore.current.behavior.scanWorkers.coerceIn(1, 100) * 3).coerceIn(3, 24)
    )

    /**
     * Phase 195 (FR-195-2) — bounds concurrent **ffprobe dispatch** the same way [episodeFanoutGate]
     * bounds TMDB. Phase 183 gated the TMDB call inside the per-episode block and left the `ffprobe`
     * beside it ungoverned, so a series still dispatched one waiter per episode file (Klovn: 102) into
     * [dev.jellystructure.ops.ProcessGate]. That gate grants background work 12 permits with a **30 s
     * acquire deadline**, so queue depth alone — not slow work — made every waiter time out. With 117
     * ids replayed at once by the dirty-set retry, essentially nothing completed, everything was
     * re-marked dirty, and the next cycle reproduced it: the retry set never drained in 12 hours and
     * a new Klovn episode sat unscanned for four hours.
     *
     * Deliberately **smaller** than ProcessGate's background allowance rather than equal to it: what
     * matters is that the number of scan-originated waiters inside ProcessGate stays under its permit
     * count, so the 30 s deadline is only ever reached by genuinely slow work. Waiting here is
     * unbounded and free; waiting in ProcessGate costs a timeout, a failed ingest, and a dirty-set
     * entry. Leaves headroom for the other background users (artwork, other series) sharing that gate.
     *
     * Separate from [episodeFanoutGate] rather than widening it to cover the whole per-episode block:
     * that block already acquires [episodeFanoutGate] internally for its TMDB calls, and a coroutine
     * holding one permit of a non-reentrant semaphore while waiting for a second is a deadlock under
     * contention, not a bound.
     */
    private val fileProbeGate = Semaphore(
        (configStore.current.behavior.scanWorkers.coerceIn(1, 100) * 2).coerceIn(2, 8)
    )

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
            "Series" -> scanSeries(jItem, localPath, effectiveFallback, libraryId, lib)
            "MusicVideo" -> scanMusicVideo(jItem, localPath, effectiveFallback, libraryId)
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

    /** Phase 175 (§8) — the movie/music-video-shaped TMDB fetch (localized details, credits, external
     *  ids, certifications, trailer), extracted so [scanMovie]/[scanMusicVideo]'s fresh-scan fetch and
     *  [rescanMetadata]'s MOVIE/MUSIC_VIDEO branches share one implementation instead of two
     *  independently hand-maintained copies (which had already, live, drifted — see the Phase 175 spec).
     *  [tmdbId] is always populated when an id was resolved, even if the details fetch itself then
     *  failed (matches the pre-175 behavior of still storing a bare id in that case) — check
     *  [TmdbMovieFetch.localized] for whether real metadata came back. */
    private data class TmdbMovieFetch(
        val tmdbId: Int?,
        val localized: Localized<TmdbMovieDetails>?,
        val cast: List<Person>,
        val crew: List<Person>,
        val imdbId: String?,
        val certifications: Map<String, String>,
        val trailer: MediaTrailer?,
    )

    private suspend fun fetchTmdbMovieMetadata(
        tmdbIdHint: Int?, searchTitle: String, searchYear: Int?, langPriority: List<String>,
        // Phase 184 — true only when [langPriority]'s first entry is an operator's explicit
        // metadataLanguage choice (rescanMetadata's own overrideLang != null): accept a title-only TMDB
        // result in that language rather than falling through the rest of the priority list, mirroring
        // getTvDetailsLocalized's existing acceptTitleOnly behavior for the series case. A fresh scan
        // (scanMovie/scanMusicVideo) never sets this — it has no operator override to honour.
        acceptTitleOnly: Boolean = false,
    ): TmdbMovieFetch {
        val tmdbId = tmdbIdHint ?: tmdb.searchMovie(searchTitle, searchYear)?.id
        val localized = tmdbId?.let { tmdb.getMovieDetailsLocalized(it, langPriority, acceptTitleOnly) }
        val details = localized?.details
        val (cast, crew) = if (details != null) fetchCredits(details.id, isMovie = true) else Pair(emptyList(), emptyList())
        val extIds = details?.let { tmdb.getExternalIds(it.id, isMovie = true) }
        val certifications = details?.let { tmdb.getMovieCertifications(it.id) } ?: emptyMap()
        val trailer = details?.let { buildTrailer(tmdb.getMovieVideos(it.id, it.originalLanguage)) }
        return TmdbMovieFetch(tmdbId, localized, cast, crew, extIds?.imdbId?.takeIf { it.isNotBlank() }, certifications, trailer)
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
        val basePriority = LanguageResolver.priorityList(audioLangs, fallback)
        // Phase 191 — a routine scan re-scans this item from scratch (no MediaItem in scope), so the
        // only way to see an operator's earlier metadataLanguage choice is to look up the record it was
        // set on. `store` is null only in test construction (Scanner's own doc comment), in which case
        // there's nothing to honour and this degrades to the pre-191 behaviour.
        val overrideLang = normalizedMetadataLanguageOverride(store?.resolveByJellyfinId(jItem.id)?.metadataLanguage)
        val langPriority = overriddenLangPriority(basePriority, overrideLang)

        val fetch = fetchTmdbMovieMetadata(jItem.providerIds?.tmdb?.toIntOrNull(), title, searchYear, langPriority, acceptTitleOnly = overrideLang != null)
        val details = fetch.localized?.details
        val resolvedLang = fetch.localized?.let { it.language ?: langPriority.lastOrNull() }

        val issueCount = tracks.count {
            (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
        }

        val primaryCompany = details?.productionCompanies?.firstOrNull()
        val tmdbFinalId = details?.id ?: fetch.tmdbId
        // Stored/display year prefers TMDB's release year, then the search year.
        val storedYear = details?.releaseDate?.take(4)?.toIntOrNull() ?: searchYear
        val titlesByLang = buildTitlesByLang(tmdbFinalId, isMovie = true, details?.title, details?.originalLanguage, details?.originalTitle)
        val cast = fetch.cast
        val crew = fetch.crew
        val certifications = fetch.certifications
        val trailer = fetch.trailer
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
            imdbId = fetch.imdbId,
            runtime = details?.runtime,
            certifications = certifications,
            trailer = trailer,
            libraryId = libraryId,
        )
    }

    /**
     * Phase 168 (FR-168-2/168-3) → Phase 171 (a concert-film/live-DVD music video CAN have a real
     * TMDB movie entry — reported live: "Muse Haarp Tour" filename-searches to nothing, but the real
     * concert film is TMDB movie 25352, "Muse: HAARP - Live from Wembley Stadium"). Structurally
     * closer to [scanMovie] than [scanSeries] — one file, no episodes — and now genuinely mirrors
     * [scanMovie]'s TMDB fetch when a match exists: title/overview/poster/backdrop/genres/cast/crew/
     * certifications/trailer/imdbId. **Never required** — filename-only metadata is still the floor,
     * a search miss is normal for a short clip with no formal release, and (FR-168-5, unchanged) a
     * miss is never flagged in `notifyOnNoMatch`. `director` always stays the filename-parsed artist
     * (FR-168-1) regardless of a TMDB match — the "Artist" field is about who performs, not TMDB's
     * own director/crew credit for the film.
     */
    private suspend fun scanMusicVideo(jItem: JellyfinItem, localPath: String, fallback: String, libraryId: String?): MediaItem? {
        if (!SystemFileSystem.exists(Path(localPath))) {
            Logger.warn("Music video file not found on disk: $localPath")
            return null
        }
        val (artist, title) = parseMusicVideoArtistTitle(localPath.substringAfterLast('/'))
        Logger.info("Scanning music video: $title${artist?.let { " — $it" } ?: ""}", "scan")

        val tracks = FfprobeRunner.probe(localPath)
        val audioLangs = tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
        val basePriority = LanguageResolver.priorityList(audioLangs, fallback)
        // Phase 191 — same override lookup as scanMovie.
        val overrideLang = normalizedMetadataLanguageOverride(store?.resolveByJellyfinId(jItem.id)?.metadataLanguage)
        val langPriority = overriddenLangPriority(basePriority, overrideLang)

        // Phase 171: search combines artist + title when both are known — TMDB concert-film titles
        // routinely include the artist name (e.g. "Muse: HAARP"), so the bare filename title alone
        // under-searches. Still just a best-effort search: providerIds first, like every other kind.
        val searchQuery = if (artist != null) "$artist $title" else title
        val fetch = fetchTmdbMovieMetadata(jItem.providerIds?.tmdb?.toIntOrNull(), searchQuery, jItem.year, langPriority, acceptTitleOnly = overrideLang != null)
        val details = fetch.localized?.details
        val resolvedLang = fetch.localized?.let { it.language ?: langPriority.lastOrNull() }

        val issueCount = tracks.count {
            (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
        }
        val primaryCompany = details?.productionCompanies?.firstOrNull()
        val tmdbFinalId = details?.id ?: fetch.tmdbId
        val storedYear = details?.releaseDate?.take(4)?.toIntOrNull() ?: jItem.year
        val cast = fetch.cast
        val crew = fetch.crew
        val certifications = fetch.certifications
        val trailer = fetch.trailer
        return MediaItem(
            id = itemId(title, jItem.year, jItem.id),
            title = details?.title ?: title,
            year = storedYear,
            kind = MediaKind.MUSIC_VIDEO,
            path = localPath,
            jellyfinId = jItem.id,
            tmdbId = tmdbFinalId,
            originalLanguage = details?.originalLanguage?.takeIf { it.isNotBlank() },
            resolvedLanguage = resolvedLang.takeIf { audioLangs.isNotEmpty() },
            posterPath = details?.posterPath,
            backdropPath = details?.backdropPath,
            overview = details?.overview?.takeIf { it.isNotBlank() },
            genres = details?.genres?.map { it.name } ?: emptyList(),
            tmdbGenres = details?.genres?.map { it.name } ?: emptyList(),
            studio = primaryCompany?.name,
            studioTmdbId = primaryCompany?.id,
            studioLogoPath = primaryCompany?.logoPath,
            secondaryStudios = details?.productionCompanies?.drop(1)?.map { it.name }?.filter { it.isNotBlank() }?.distinct() ?: emptyList(),
            director = artist,
            tracks = tracks,
            issueCount = issueCount,
            languageMix = false,
            scannedAt = epochSeconds(),
            addedAt = jItem.dateCreated?.let { isoToEpochSeconds(it) },
            jellyfinUpdatedAt = jItem.dateLastSaved?.let { isoToEpochSeconds(it) },
            jellyfinLockData = jItem.lockData,
            jellyfinLockedFields = jItem.lockedFields,
            tags = jItem.tags,
            cast = cast,
            crew = crew,
            imdbId = fetch.imdbId,
            runtime = details?.runtime,
            certifications = certifications,
            trailer = trailer,
            libraryId = libraryId,
        )
    }

    private suspend fun scanSeries(jItem: JellyfinItem, localPath: String, fallback: String, libraryId: String?, lib: LibraryMapping): MediaItem? {
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
        val jfBySeasonEp = bySeasonEpDeterministic(jfEpsMeta)
        // Phase 152: fallback join for a file Jellyfin's own scanner placed on disk but never numbered
        // (no IndexNumber) — jfBySeasonEp can't key it, but jellystructure's own parseSeasonEpisodes
        // below often still derives the right (season, episode) from the filename. Match by path instead,
        // translated to Jellyfin's own view of it via the same library prefix substitution scanItem
        // already computes for the series directory.
        val jfByPath = jfEpsMeta.mapNotNull { ep -> ep.path?.let { it to ep } }.toMap()
        val seasonNamesMap: Map<Int, String> = jfEpsMeta
            .groupBy { it.parentIndexNumber ?: 0 }
            .mapValues { (_, eps) -> eps.firstOrNull()?.seasonName ?: "" }
            .filterValues { it.isNotBlank() }

        val existingSeriesItem = store?.resolveByJellyfinId(jItem.id)
        val previousEpisodes = existingSeriesItem?.episodes ?: emptyList()
        // Phase 191 — same override lookup as scanMovie/scanMusicVideo, from the record this fresh scan
        // is about to replace; applied below to both the series-level fetch and every per-episode fetch.
        val overrideLang = normalizedMetadataLanguageOverride(existingSeriesItem?.metadataLanguage)

        // Phase 183 (FR-183-4) — the previously-stored episode for each (season, episode), so the loop
        // below can skip a per-episode TMDB re-fetch when we already hold good data for it (the single
        // largest lever against the reported rate-limit incident) instead of unconditionally re-fetching
        // every episode of every series on every scan, matched or not. Built once, not per-episode.
        val existingBySeasonEp: Map<Pair<Int, Int>, dev.jellystructure.model.Episode> =
            previousEpisodes
                .filter { it.seasonNumber != null && it.episodeNumber != null }
                .associateBy { it.seasonNumber!! to it.episodeNumber!! }

        // Phase 188 — a positive scan_episode_cap makes filesToProbe a strict subset of episodeFiles;
        // without this, the episode list built below (from filesToProbe alone) silently replaced every
        // previously-known episode for a file outside this pass's sample with nothing, permanently, on
        // every subsequent scan (135 of 184 prod TV shows were found stuck at exactly episode_count 8 —
        // scan_episode_cap's value). Keyed by path rather than (season, episode) so it also carries over
        // files jellystructure never numbered.
        val existingByPath: Map<String, dev.jellystructure.model.Episode> = previousEpisodes.associateBy { it.path }

        // Bug fix: this was a plain sequential `for` loop — one ffprobe + TMDB round trip per episode,
        // one after another. A big show (e.g. a 300+-episode series) monopolized its entire worker slot
        // for however long that took in total, while every other worker sat idle once the rest of the
        // library was done. Every file's work is independent (its own ffprobe probe, its own per-episode
        // TMDB fetches), so dispatch them all concurrently instead — real concurrency stays bounded by
        // the existing ProcessGate (ffprobe) and OutboundHttp (TMDB) gates exactly as before, this only
        // changes how many files get DISPATCHED into those gates at once, matching the same dispatch-
        // only-bounds-nothing-new principle `runPipelineStepPool`/`runScan` already rely on.
        val episodes = coroutineScope {
            filesToProbe.map { file ->
                async {
                    // Phase 195 (FR-195-2): bound how many of these reach ProcessGate at once — see
                    // [fileProbeGate]. Held across diagnose() too, which is a second ffprobe.
                    val tracks = fileProbeGate.withPermit {
                        val probed = FfprobeRunner.probe(file)
                        // Phase 128: diagnose() re-probes with stderr kept, so the scan log says WHY (corrupt,
                        // unreadable, etc.) instead of just that the track list came back empty. Rare path — only
                        // runs for a file that already produced zero tracks — so the extra ffprobe call is fine.
                        if (probed.isEmpty()) {
                            val reason = FfprobeRunner.diagnose(file)
                            Logger.warn("ffprobe returned no tracks for episode: $file (${reason.status}: ${reason.detail})", "scan")
                        }
                        probed
                    }
                    val epIssueCount = tracks.count {
                        (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
                    }
                    val audioLangs = tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
                    val epLangPriority = LanguageResolver.priorityList(audioLangs, fallback)
                    val epResolvedLang = epLangPriority.firstOrNull()
                    // Phase 191 — only the TMDB *fetch* order honours the series-level override; the
                    // episode's own displayed resolvedLanguage above stays audio-derived, matching the
                    // existing per-episode "honest display language" contract (Phase 128).
                    val epFetchPriority = overriddenLangPriority(epLangPriority, overrideLang)
                    val translatedJfPath = if (lib.jellyfinPath.isNotBlank()) file.replaceFirst(lib.localPath, lib.jellyfinPath) else file
                    val jfPathMatch = jfByPath[translatedJfPath]
                    // Phase 160: when our own filename regexes find nothing (e.g. a bare `SEE` scheme like
                    // "La Linea - 101.mkv"), fall back to the season/episode Jellyfin's own scanner already
                    // assigned this same file — see resolveSeasonEpisode.
                    val (seasonNum, epNums) = resolveSeasonEpisode(parseSeasonEpisodes(file), jfPathMatch?.parentIndexNumber, jfPathMatch?.indexNumber)
                    // Phase 149: a multi-episode file (`S01E01E02E03.mkv`) yields >1 contained episode number.
                    // An unparseable filename still yields exactly one Episode (episodeNumber = null), matching
                    // pre-149 behaviour — partCount is 1 either way.
                    val partCount = epNums.size.coerceAtLeast(1)
                    val partEpisodeNums: List<Int?> = if (epNums.isEmpty()) listOf(null) else epNums
                    // Chapters are only worth reading for the (rare) multi-episode case — skip the extra
                    // ffprobe invocation entirely for the overwhelmingly common single-episode file.
                    val chapterMarkers = if (partCount > 1) fileProbeGate.withPermit { FfprobeRunner.chapters(file) } else emptyList()
                    val hasMatchingChapters = chapterMarkers.size == partCount

                    partEpisodeNums.mapIndexed { partIdx, epNum ->
                        // Phase 183 (FR-183-4) — the episode this same (season, episode) resolved to last
                        // scan, if any. `existingEp != null` also means "not this file's first scan," which
                        // is exactly the condition under which skipping a re-fetch is safe.
                        val existingEp = if (seasonNum != null && epNum != null) existingBySeasonEp[seasonNum to epNum] else null

                        // Fetch per-episode TMDB details in the episode's own resolved language — independent
                        // per contained episode, exactly like a normal single-episode file. Skipped when the
                        // previous scan already has both a title and an overview for this episode — TMDB
                        // details for an already-matched episode don't change on their own, so a re-scan
                        // gains nothing by re-asking every time (this was the ~1,500-2,000-request-per-series
                        // storm's largest single contributor).
                        val hasGoodDetails = existingEp != null &&
                            !existingEp.title.isNullOrBlank() && !existingEp.overview.isNullOrBlank()
                        val epDetails = if (!hasGoodDetails && seriesTmdbId != null && seasonNum != null && epNum != null) {
                            // Phase 183 (FR-183-3) — bounded, not one coroutine per episode unconditionally.
                            episodeFanoutGate.withPermit {
                                tmdb.getEpisodeDetailsLocalized(seriesTmdbId, seasonNum, epNum, epFetchPriority)
                            }
                        } else null

                        // Phase 76: fetch guest stars + episode crew from TMDB. Phase 183 (FR-183-4): same
                        // skip — an episode already carrying cast/crew from a prior fetch isn't re-asked.
                        // An episode TMDB genuinely has no credits for (guestStars/crew both empty) is NOT
                        // "good" by this check, so it keeps being retried every scan exactly as before —
                        // deliberately conservative: this only skips a call proven to have returned data,
                        // never risks mistaking "TMDB has none" for "not fetched yet".
                        val hasGoodCredits = existingEp != null &&
                            (existingEp.guestStars.isNotEmpty() || existingEp.crew.isNotEmpty())
                        val (epGuests, epCrew) = if (!hasGoodCredits && seriesTmdbId != null && seasonNum != null && epNum != null) {
                            episodeFanoutGate.withPermit { fetchEpisodeCredits(seriesTmdbId, seasonNum, epNum) }
                        } else Pair(existingEp?.guestStars ?: emptyList(), existingEp?.crew ?: emptyList())

                        // R82: map (season, ep) → Jellyfin id from the pre-fetched meta. Phase 152: fall
                        // back to a path match when Jellyfin never numbered this file — see jfByPath above.
                        val jfEp = (if (seasonNum != null && epNum != null) jfBySeasonEp[seasonNum to epNum] else null)
                            ?: jfPathMatch

                        Episode(
                            filename = file.substringAfterLast('/'),
                            path = file,
                            seasonNumber = seasonNum,
                            episodeNumber = epNum,
                            tracks = tracks,
                            issueCount = epIssueCount,
                            // Phase 128: honest per-episode display language — see the scanMovie comment above.
                            resolvedLanguage = epResolvedLang.takeIf { audioLangs.isNotEmpty() },
                            // Phase 183: fall back to the existing value when the fetch was skipped (or TMDB
                            // genuinely returned nothing) instead of overwriting good data with null — this
                            // was a real latent data-loss bug independent of the skip itself: a transient TMDB
                            // failure/rate-limit used to blank these fields outright on the very next scan.
                            title = epDetails?.name?.takeIf { it.isNotBlank() } ?: existingEp?.title,
                            overview = epDetails?.overview?.takeIf { it.isNotBlank() } ?: existingEp?.overview,
                            stillPath = epDetails?.stillPath ?: existingEp?.stillPath,
                            tmdbEpisodeId = epDetails?.id ?: existingEp?.tmdbEpisodeId,
                            guestStars = epGuests,
                            crew = epCrew,
                            jellyfinId = jfEp?.id,
                            runtime = epDetails?.runtime ?: existingEp?.runtime,
                            airDate = epDetails?.airDate?.takeIf { it.isNotBlank() } ?: existingEp?.airDate,  // R148
                            jellyfinCreatedAt = jfEp?.dateCreated?.let { isoToEpochSeconds(it) },  // Phase 108
                            partIndex = partIdx,
                            partCount = partCount,
                            chapterStartMs = if (hasMatchingChapters) chapterMarkers[partIdx].startMs else null,
                            chapterEndMs = if (hasMatchingChapters) chapterMarkers[partIdx].endMs else null,
                            hasChapters = hasMatchingChapters,
                            // Phase 153 (FR-SCAN2-5) — Jellyfin knows this file but never assigned it an
                            // episode number, and never retries; flag it so write_nfo/sync_jellyfin repair it.
                            jellyfinIndexMissing = epNum != null && jfEp != null && jfEp.indexNumber == null,
                        )
                    }
                }
            }.awaitAll().flatten()
        }

        // Phase 188 — carry over a previously-known episode for every file this pass's cap excluded from
        // probing (filesToProbe is a strict subset of episodeFiles whenever scan_episode_cap is positive).
        // Filtered against the live `episodeFiles` listing, not blindly against everything the DB held, so
        // a file actually deleted from disk still drops out exactly as before — only files still present
        // but unsampled this pass are restored. A file with no prior record (never yet probed) is left
        // out, matching Phase 49's original "gap until an uncapped rescan" behaviour.
        val carriedOverEpisodes = (episodeFiles - filesToProbe.toSet()).mapNotNull { existingByPath[it] }
        val episodesWithCarryOver = episodes + carriedOverEpisodes

        // Bug fix (Ravilo auto-play-next loop): `jfBySeasonEp` above is keyed by (season, episode), so
        // every file that parses to the same code was handed the SAME Jellyfin id — two rail entries with
        // one id, and a "next episode" that was the episode already playing. The redundant copies keep
        // their row (the operator needs to see and fix them — they surface as the `duplicate_episode`
        // triage type) but lose the borrowed id, so only one entry ever owns an episode's identity.
        val sortedEpisodes = DuplicateEpisodes.withUniqueIds(
            episodesWithCarryOver.sortedWith(compareBy({ it.seasonNumber ?: 999 }, { it.episodeNumber ?: 999 }))
        )
        DuplicateEpisodes.describe(sortedEpisodes).forEach {
            Logger.warn("Series '$title' has duplicate episode files — $it", "scan")
        }

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
            val mixBasePriority = LanguageResolver.priorityList(
                majorityLang?.let { listOf(it) } ?: emptyList(), fallback
            )
            // Phase 191 — the override still wins even when episodes disagree on audio language.
            val mixPriority = overriddenLangPriority(mixBasePriority, overrideLang)
            val mixDetails = seriesTmdbId?.let { tmdb.getTvDetailsLocalized(it, mixPriority, acceptTitleOnly = overrideLang != null) }?.details
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
        val basePriority = LanguageResolver.priorityList(audioLangs, fallback)
        // Phase 191 — see the override lookup at the top of this function.
        val langPriority = overriddenLangPriority(basePriority, overrideLang)

        val localized = seriesTmdbId?.let { tmdb.getTvDetailsLocalized(it, langPriority, acceptTitleOnly = overrideLang != null) }
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
        val basePriority = LanguageResolver.priorityList(audioLangs, fallback)
        // Phase 191 — the Sync button rebuilds this item from scratch just like a routine scan; the
        // operator's override must survive it exactly like rescanMetadata (the Re-pull button).
        val overrideLang = normalizedMetadataLanguageOverride(item.metadataLanguage)
        val langPriority = overriddenLangPriority(basePriority, overrideLang)
        val tmdbId = item.tmdbId ?: tmdb.searchMovie(item.title, item.year)?.id
        val localized = tmdbId?.let { tmdb.getMovieDetailsLocalized(it, langPriority, acceptTitleOnly = overrideLang != null) } ?: return null
        val details = localized.details
        val resolvedLang = localized.language ?: langPriority.lastOrNull()
        val issueCount = tracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
        val primaryCompany = details.productionCompanies.firstOrNull()
        val tmdbTags = tmdb.getMovieKeywords(details.id)
        // Phase 150 (FR-SEG1-6): "Trust TMDB stinger tags" toggle, defaulting to trust when the
        // detect_segments step isn't configured at all (matches PipelineStep.trustStingerTags' own default).
        val trustStingers = config.scan.pipeline.firstOrNull { it.step == "detect_segments" }?.trustStingerTags != false
        val syncStinger = if (trustStingers) SegmentDetection.stingerFromTmdbKeywords(tmdbTags) else null
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
            // Phase 150: same never-touch-a-manual-record / only-add-never-clear rule as rescanMetadata.
            segments = if (item.segments.manuallyConfirmed) item.segments
                       else item.segments.copy(stinger = syncStinger ?: item.segments.stinger),
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
        // Phase 191 — the Sync button rebuilds every episode + the series-level fields from scratch,
        // same as scanSeries; the override must survive it identically.
        val overrideLang = normalizedMetadataLanguageOverride(item.metadataLanguage)
        // Backfill missing jellyfinIds: fetch Jellyfin episode meta if the series has a jellyfinId
        // and any episode is still missing one (e.g. scanned before R82 or via old sync path).
        val scanBaseUrl = config.apiKeys.jellyfinUrl
        val scanAdminToken = config.apiKeys.jellyfinToken
        // Phase 153: also refetch when an episode is flagged jellyfinIndexMissing — that flag has to be
        // re-derived from Jellyfin to clear once a repair lands, and those episodes DO have a jellyfinId
        // (which is exactly why the `jellyfinId == null` condition alone never covered them).
        val jfEpsMetaSync: List<JellyfinEpisodeItem> = if (
            item.jellyfinId != null && scanBaseUrl.isNotBlank() && scanAdminToken.isNotBlank() &&
            item.episodes.any { it.jellyfinId == null || it.jellyfinIndexMissing }
        ) {
            jellyfinClient.getSeriesEpisodesMeta(scanBaseUrl.trimEnd('/'), scanAdminToken, item.jellyfinId)
        } else emptyList()
        val jfBySeasonEp: Map<Pair<Int, Int>, JellyfinEpisodeItem> = bySeasonEpDeterministic(jfEpsMetaSync)
        // Phase 152/153: an unnumbered Jellyfin item keys into jfBySeasonEp under (season, 0) and can
        // only be found by path — same fallback scanSeries uses.
        val jfByPathSync: Map<String, JellyfinEpisodeItem> =
            jfEpsMetaSync.mapNotNull { ep -> ep.path?.let { it to ep } }.toMap()
        /** Local path → Jellyfin's own view of it, via this library's prefix mapping. */
        fun toJellyfinPath(localFile: String): String =
            if (lib != null && lib.jellyfinPath.isNotBlank()) localFile.replaceFirst(lib.localPath, lib.jellyfinPath)
            else localFile
        // Phase 169: was a plain sequential `for` loop — one ffprobe + TMDB round trip per episode file,
        // one after another, unlike scanSeries's already-concurrent dispatch (Scanner.kt:436-517). Same
        // fix here: dispatch every file's independent work concurrently under one coroutineScope, bounded
        // by the existing ProcessGate (ffprobe)/OutboundHttp (TMDB) gates — no new concurrency knob.
        val episodes = coroutineScope {
            episodeFiles.map { file ->
                async {
                    // Phase 195 (FR-195-2): the `POST /api/media/{id}/sync` path had the identical
                    // ungoverned dispatch — one bug in two places. See [fileProbeGate].
                    val tracks = fileProbeGate.withPermit { FfprobeRunner.probe(file) }
                    val epIssueCount = tracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
                    val audioLangs = tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
                    val epLangPriority = LanguageResolver.priorityList(audioLangs, fallback)
                    // Phase 191 — see scanSeries's identical epFetchPriority split: the fetch order honours
                    // the override, the episode's own displayed resolvedLanguage below stays audio-derived.
                    val epFetchPriority = overriddenLangPriority(epLangPriority, overrideLang)
                    val jfPathMatchSync = jfByPathSync[toJellyfinPath(file)]
                    // Phase 160: same filename-parse fallback scanSeries uses — see resolveSeasonEpisode.
                    val (seasonNum, epNums) = resolveSeasonEpisode(parseSeasonEpisodes(file), jfPathMatchSync?.parentIndexNumber, jfPathMatchSync?.indexNumber)
                    val partCount = epNums.size.coerceAtLeast(1)
                    val partEpisodeNums: List<Int?> = if (epNums.isEmpty()) listOf(null) else epNums
                    val chapterMarkers = if (partCount > 1) fileProbeGate.withPermit { FfprobeRunner.chapters(file) } else emptyList()
                    val hasMatchingChapters = chapterMarkers.size == partCount

                    partEpisodeNums.mapIndexed { partIdx, epNum ->
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
                        // Phase 183 (FR-183-4) — same skip as scanSeries: don't re-ask TMDB for data this
                        // episode already has. See scanSeries's hasGoodDetails/hasGoodCredits comments for
                        // the full reasoning (deliberately conservative — only skips a call proven to have
                        // returned data, never mistakes "TMDB has none" for "not fetched yet").
                        val hasGoodDetails = existingEp != null &&
                            !existingEp.title.isNullOrBlank() && !existingEp.overview.isNullOrBlank()
                        val epDetails = if (!hasGoodDetails && seriesTmdbId != null && seasonNum != null && epNum != null) {
                            // Phase 183 (FR-183-3) — bounded, not one coroutine per episode unconditionally.
                            episodeFanoutGate.withPermit {
                                tmdb.getEpisodeDetailsLocalized(seriesTmdbId, seasonNum, epNum, epFetchPriority)
                            }
                        } else null
                        // Phase 76: preserve existing guest stars/crew; re-fetch from TMDB if available
                        val hasGoodCredits = existingEp != null &&
                            (existingEp.guestStars.isNotEmpty() || existingEp.crew.isNotEmpty())
                        val (epGuests, epCrew) = if (!hasGoodCredits && seriesTmdbId != null && seasonNum != null && epNum != null) {
                            episodeFanoutGate.withPermit { fetchEpisodeCredits(seriesTmdbId, seasonNum, epNum) }
                        } else Pair(existingEp?.guestStars ?: emptyList(), existingEp?.crew ?: emptyList())
                        Episode(
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
                            // Phase 153 (FR-SCAN2-5/8) — re-derived from Jellyfin whenever we refetched its meta,
                            // so a landed repair clears the flag; otherwise keep whatever the last scan recorded.
                            jellyfinIndexMissing = if (jfEpsMetaSync.isEmpty()) (existingEp?.jellyfinIndexMissing ?: false)
                                else epNum != null && jfByPathSync[toJellyfinPath(file)]?.indexNumber == null &&
                                    jfByPathSync.containsKey(toJellyfinPath(file)),
                        )
                    }
                }
            }.awaitAll().flatten()
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
            val mixBasePriority = LanguageResolver.priorityList(majorityLang?.let { listOf(it) } ?: emptyList(), fallback)
            // Phase 191 — the override still wins even when episodes disagree on audio language.
            val mixPriority = overriddenLangPriority(mixBasePriority, overrideLang)
            seriesTmdbId?.let { tmdb.getTvDetailsLocalized(it, mixPriority, acceptTitleOnly = overrideLang != null) }?.details
        } else {
            val audioLangs = firstTracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
            val basePriority = LanguageResolver.priorityList(audioLangs, fallback)
            // Phase 191 — see the override lookup above.
            val langPriority = overriddenLangPriority(basePriority, overrideLang)
            val localized = seriesTmdbId?.let { tmdb.getTvDetailsLocalized(it, langPriority, acceptTitleOnly = overrideLang != null) }
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
        // Phase 191 — a single-season re-sync is the same "rebuild from scratch" shape as syncSeriesEpisodes.
        val overrideLang = normalizedMetadataLanguageOverride(item.metadataLanguage)
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
            val epFetchPriority = overriddenLangPriority(epLangPriority, overrideLang)
            val epNum = ep.episodeNumber
            val epDetails = if (seriesTmdbId != null && epNum != null) {
                tmdb.getEpisodeDetailsLocalized(seriesTmdbId, seasonNumber, epNum, epFetchPriority)
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
        // Phase 174: an operator has decided this item has no correct TMDB entry. Every branch below
        // reads `item.tmdbId ?: tmdb.searchXxx(...)` — a null id is exactly what triggers a fresh
        // search — so without this guard the rejected match would come straight back on the next
        // `pull_tmdb`/Sync/Re-pull (same title+year ⇒ same top hit). Null means "nothing pulled";
        // every caller already writes nothing on null. Only an explicit re-match (`PATCH .../tmdb-id`
        // with a real id) lifts the lock.
        if (item.tmdbMatchLocked) {
            Logger.info("Skipping TMDB re-pull for '${item.id}' — match cleared by an operator", "scan")
            return null
        }
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
        // Phase 184 (FR-184-1) — an operator's explicit metadataLanguage choice is consulted here,
        // ABOVE everything else: it wins over both the series-only resolvedLanguage override below and
        // the ordinary audio-cascade order, for either MOVIE or TV_SHOW alike. The resolver itself is
        // untouched — this only decides which language goes first in the list it's handed.
        //
        // Series ALSO have a pre-existing, narrower override (the language-mix control writes it to
        // resolvedLanguage); honoured as the first TMDB query language when no explicit metadataLanguage
        // is set. Movies have no such override outside Phase 184 — their resolvedLanguage is always
        // auto-derived from the audio order, so treating a movie's stale resolvedLanguage as an
        // override would pin the old language forever and make reordering audio (or changing the
        // fallback) unable to ever change the fetched language. For movies with no metadataLanguage,
        // follow the current audio order instead.
        val overrideLang = normalizedMetadataLanguageOverride(item.metadataLanguage)
            ?: if (item.kind == MediaKind.TV_SHOW)
                normalizedMetadataLanguageOverride(item.resolvedLanguage)
            else null
        val langPriority = overriddenLangPriority(basePriority, overrideLang)

        return when (item.kind) {
            MediaKind.MOVIE -> {
                // Phase 175 (§8): the id-resolve/localized-details/credits/extIds/certs/trailer sequence
                // is now shared with scanMovie via fetchTmdbMovieMetadata — this branch keeps its own
                // rescan-only extras (the sophisticated resolvedLang below, stinger/keyword detection)
                // exactly as before, on top of the shared fetch.
                val fetch = fetchTmdbMovieMetadata(item.tmdbId, item.title, item.year, langPriority, acceptTitleOnly = overrideLang != null)
                val localized = fetch.localized ?: return null
                val details = localized.details
                // Resolve the stored language through the SAME shared resolver the UI uses, fed with
                // the languages TMDB actually has — so resolvedLanguage and the on-screen trace always
                // agree. Falls back to the language we actually fetched content in.
                val available = tmdb.getTranslationLanguages(details.id, isMovie = true).toSet()
                val resolvedLang = LanguageResolver.resolve(sourceTracks, fallback, available).language
                    ?: localized.language
                val rescanCompany = details.productionCompanies.firstOrNull()
                val rescanTmdbTags = tmdb.getMovieKeywords(details.id)
                // Phase 150 (FR-SEG1-6): same raw keyword-name list mergeRepullTags flattens into `tags`
                // below — no second TMDB request. Movies only (a series' top-level `segments` isn't used;
                // each episode carries its own — see SegmentMarkers' doc comment).
                val trustStingers = config.scan.pipeline.firstOrNull { it.step == "detect_segments" }?.trustStingerTags != false
                val rescanStinger = if (trustStingers) SegmentDetection.stingerFromTmdbKeywords(rescanTmdbTags) else null
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
                    imdbId = fetch.imdbId ?: item.imdbId,
                    cast = fetch.cast,
                    crew = fetch.crew,
                    runtime = details.runtime,
                    certifications = fetch.certifications.ifEmpty { item.certifications },
                    trailer = fetch.trailer,
                    libraryId = lib?.jellyfinId?.ifBlank { null } ?: item.libraryId,   // Phase 142: self-heal
                    // Phase 150: never touch a manually-confirmed record; otherwise only ADD a stinger
                    // TMDB currently reports — never clear one an earlier fetch found but this one omits
                    // (keyword lists can be flaky/incomplete; losing a real stinger flag would be worse
                    // than a stale one).
                    segments = if (item.segments.manuallyConfirmed) item.segments
                               else item.segments.copy(stinger = rescanStinger ?: item.segments.stinger),
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
            // Phase 171: reverses Phase 168's "never TMDB, ever" call — a concert-film/live-DVD music
            // video CAN have a real TMDB movie entry (reported live: TMDB 25352 for "Muse: HAARP -
            // Live from Wembley Stadium"), so this now mirrors the MOVIE branch above once a match
            // exists — via an existing/manually-set tmdbId (the admin's `PATCH .../tmdb-id` route
            // already works for any kind) or a fresh search. Unlike MOVIE, a miss is never a failure
            // — `item` unchanged, not `return null` — since a miss is the *expected*, common case
            // (FR-168-5's notifyOnNoMatch exclusion is unchanged and still applies). `director` always
            // stays the filename-parsed artist (FR-168-1) — TMDB's own director/crew credit for the
            // film is a different concept, folded into `crew` instead, never overwriting it.
            MediaKind.MUSIC_VIDEO -> {
                // Phase 175 (§8): shares fetchTmdbMovieMetadata with scanMusicVideo — see the MOVIE
                // branch's comment above. This branch never had the movie branch's extras (stinger
                // detection, the sophisticated resolvedLang) — that asymmetry is unchanged.
                val fetch = fetchTmdbMovieMetadata(item.tmdbId, item.title, item.year, langPriority, acceptTitleOnly = overrideLang != null)
                val details = fetch.localized?.details
                if (details == null) item else {
                    val rescanCompany = details.productionCompanies.firstOrNull()
                    item.copy(
                        title = details.title,
                        tmdbId = details.id,
                        year = details.releaseDate.take(4).toIntOrNull() ?: item.year,
                        originalLanguage = details.originalLanguage.takeIf { it.isNotBlank() },
                        posterPath = details.posterPath,
                        backdropPath = details.backdropPath,
                        overview = details.overview.takeIf { it.isNotBlank() },
                        genres = details.genres.map { it.name },
                        tmdbGenres = details.genres.map { it.name },
                        studio = rescanCompany?.name,
                        studioTmdbId = rescanCompany?.id,
                        studioLogoPath = rescanCompany?.logoPath,
                        secondaryStudios = details.productionCompanies.drop(1).map { it.name }.filter { it.isNotBlank() }.distinct(),
                        imdbId = fetch.imdbId ?: item.imdbId,
                        cast = fetch.cast,
                        crew = fetch.crew,
                        runtime = details.runtime,
                        certifications = fetch.certifications.ifEmpty { item.certifications },
                        trailer = fetch.trailer,
                        libraryId = lib?.jellyfinId?.ifBlank { null } ?: item.libraryId,
                    )
                }
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
        if (jItem.type != "Movie" && jItem.type != "Series" && jItem.type != "MusicVideo") return "unsupported-type"
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
            return if (jItem.type == "Series") "dir-not-found" else "file-not-found"
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

    /** Phase 184 (FR-184-4) — the picker's coverage list; see [dev.jellystructure.tmdb.TmdbClient.getTranslationCoverage]. */
    suspend fun translationCoverage(tmdbId: Int, isMovie: Boolean): List<dev.jellystructure.tmdb.TmdbLanguageCoverage> =
        tmdb.getTranslationCoverage(tmdbId, isMovie)

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
