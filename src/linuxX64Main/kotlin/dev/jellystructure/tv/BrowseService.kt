package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.GenreCatalog
import dev.jellystructure.media.LogoDownloader
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.TrackKind
import dev.jellystructure.model.recencyKey
import dev.jellystructure.resolver.CertificationResolver
import dev.jellystructure.shared.tv.BrowseCard
import dev.jellystructure.shared.tv.BrowseFacets
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.shared.tv.FacetItem
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.SearchResults
import dev.jellystructure.shared.tv.SeededBrowseResponse
import dev.jellystructure.shared.tv.TvImdbRating
import dev.jellystructure.shared.tv.effectiveQuery
import dev.jellystructure.shared.tv.ALL_KIND
import dev.jellystructure.shared.tv.MUSIC_KIND
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import platform.posix.time

private const val SEARCH_SUGGESTION_LIMIT = 20
// Phase 216 (FR-216-8/11) — the facets cache's TTL backstop, same window as HomeFeedService's feed
// cache. feedVersion + allowedHash are the real invalidation signals; the TTL only bounds staleness
// for anything neither of them can see.
private const val FACETS_TTL_MS = 5 * 60_000L
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun nowMs(): Long = time(null) * 1000L
// Bug fix: shorter queries fall back to the suggestions path instead of a full-library contains-scan.
private const val MIN_SEARCH_LEN = 2

class BrowseService(
    private val mediaStore: MediaStore,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
    private val raviloConfigService: RaviloConfigService,
    private val artwork: ArtworkDownloader,
    // Phase 216 (FR-216-5) — the ONLY permitted source for `logoUrl`: `hasLogo` says whether a logo
    // file is really on disk. Nullable so a test can construct the service without a data dir.
    private val logoDownloader: LogoDownloader? = null,
) {
    // Phase 216 (FR-216-8/11) — facets() used to iterate the whole visible library on every call and
    // cache nothing, which was fine for a rarely-opened facet bar and is not fine once R243 puts a
    // Discover wall in front of it. One entry per Jellyfin user, validated against feedVersion (Phase
    // 204 — NOT libraryVersion, which bumps on every write and reproduced Phase 182's measured 120×
    // home-feed regression) and allowedHash (the Phase 142 visibility scope). Never keyed on the
    // requested kind or a selected value: filtering is a view, never a re-derivation (R233 FR-R233-5).
    // The entry holds all three kind slices (all / movie / series) from ONE pass over the library.
    // R267 (FR-R267-5c) — `music` is new. Before it, `facets(kind)` sliced exactly three ways and
    // **any other kind silently fell through to `all`**, so a music-video browse would have rendered
    // the whole library's counts rather than an error. The phone's Library dropdown offers Music, so
    // the slice has to be real.
    private class FacetsEntry(
        val all: BrowseFacets,
        val movie: BrowseFacets,
        val series: BrowseFacets,
        val music: BrowseFacets,
        val builtAt: Long,
        val feedVer: Long,
        val allowedHash: Int,
    )
    private val facetsCache = HashMap<String, FacetsEntry>()
    /**
     * R187 — resolves a "→ See all" seed (a [Row.seedQuery] condition tree, already channel-ANDed
     * where relevant, plus [mediaKind]) to the FULL matching set as [BrowseCard]s (genres, audio
     * languages, quality, channel membership, IMDb rating — everything the browse page's facet bar
     * needs), device-scoped. Deliberately returns everything in one call rather than a paginated/
     * narrowed-per-facet API: the live catalog is a few hundred items (confirmed cheap at this scale
     * during the spec's backend review), so the Ravilo client computes facet counts/filtering/sorting
     * reactively from this one response with no further round trips as the viewer toggles facets.
     */
    suspend fun browseByQuery(device: DeviceData, query: ConditionGroup?, mediaKind: String?): SeededBrowseResponse = coroutineScope {
        // Phase 205 (FR-205-1) — this used to fetch a tvToken it needed only for the fetchPlaystate call
        // below; now that playstate is a PlaystateCache read (no Jellyfin call, no timeout), this
        // function makes no Jellyfin call of its own at all.
        val allDeferred = async { mediaStore.liveItems(device) }
        val cfg = raviloConfigService.getConfig(device.jellyfinUserId)
        val heroIds = cfg.heroes.map { it.itemId }.toSet()
        val cascade = configStore.current.metadata.ageRatingCascade

        val all = allDeferred.await()
        val kindFiltered = when (mediaKind) {
            "MOVIE"  -> all.filter { it.kind == MediaKind.MOVIE }
            "SERIES" -> all.filter { it.kind == MediaKind.TV_SHOW }
            "MUSIC_VIDEO" -> all.filter { it.kind == MediaKind.MUSIC_VIDEO }
            else     -> all
        }
        // Bug fix: this endpoint never sorted its result at all -- items came back in raw DB scan
        // order (SELECT with no ORDER BY), which happens to read as roughly alphabetical, so the
        // client's RECENT sort (SeededBrowseScreen.sortedFiltered's `filtered.asReversed()`, which
        // trusts the incoming order is already newest-first) silently sorted alphabetically instead
        // of by recency. Every other "recently added" surface (Newly Added rows, the old plain
        // browse()/search() below) already uses this same recencyKey() -- this endpoint was the one
        // gap, since it backs the Movies/Series tabs and every "-> See all" page in ravilo-ui.
        val matched = (if (query == null) kindFiltered
            else kindFiltered.filter { ConditionEvaluator.matches(it, query, heroIds, cascade) })
            .sortedByDescending { it.recencyKey() }

        SeededBrowseResponse(items = browseCards(device, matched, cfg), total = matched.size)
    }

    /** Phase 269 (FR-269-2/7) — the Recommended row's *See all*: [items] (already the viewer's
     *  still-eligible list, in its order) as browse cards, never re-sorted. */
    fun recommendationCards(device: DeviceData, items: List<MediaItem>): SeededBrowseResponse =
        SeededBrowseResponse(items = browseCards(device, items, raviloConfigService.getConfig(device.jellyfinUserId)), total = items.size)

    private fun browseCards(device: DeviceData, matched: List<MediaItem>, cfg: dev.jellystructure.shared.tv.RaviloConfig): List<BrowseCard> {
        val heroIds = cfg.heroes.map { it.itemId }.toSet()
        val cascade = configStore.current.metadata.ageRatingCascade
        val channels = cfg.channels.filter { it.enabled }
        val ps = PlaystateCache.get(device.jellyfinUserId)
        // Phase 271 (FR-271-3/5) — the viewer's app language, without the title step: the browse page
        // counts and filters genres across these cards itself, so one genre must read the same on each.
        val lang = cfg.uiLanguage

        return matched.map { item ->
            BrowseCard(
                card = item.toMediaCard(lang).withPlaystate(ps),
                genres = GenreCatalog.displayNames(item, lang, withTitle = false),
                genreIds = GenreCatalog.displayIds(item),
                audioLanguages = item.audioLanguages(),
                quality = item.qualityLabel(),
                channels = channels.filter { ch -> ConditionEvaluator.matches(item, ch.effectiveQuery(), heroIds, cascade) }.map { it.id },
                imdbRating = item.imdbRating?.let { TvImdbRating(aggregateRating = it.aggregateRating, voteCount = it.voteCount) },
                sortName = item.sortName,   // R253 (FR-R253-3)
            )
        }
    }

    /** R187 (Quality facet) — the best (largest, since a scan only probes one file per episode/movie
     *  today) video track's resolution+HDR flag as one label. Null when no video track was probed yet. */
    private fun MediaItem.qualityLabel(): String? {
        val tracks = if (kind == MediaKind.TV_SHOW) episodes.flatMap { it.tracks } else tracks
        val video = tracks.filter { it.kind == TrackKind.VIDEO }.maxByOrNull { (it.width ?: 0) * (it.height ?: 0) } ?: return null
        val tier = when {
            (video.width ?: 0) >= 3840 || (video.height ?: 0) >= 2160 -> "4K"
            (video.width ?: 0) >= 1920 || (video.height ?: 0) >= 1080 -> "1080p"
            (video.width ?: 0) >= 1280 || (video.height ?: 0) >= 720  -> "720p"
            video.width != null || video.height != null -> "SD"
            else -> return null
        }
        return if (video.videoRange == "HDR") "$tier HDR" else tier
    }

    /** R187 (Audio facet) — distinct, tagged audio languages across every track (episodes flattened for
     *  a series), matching the same "und"/blank = untagged exclusion [FfprobeRunner] already applies. */
    private fun MediaItem.audioLanguages(): List<String> {
        val tracks = if (kind == MediaKind.TV_SHOW) episodes.flatMap { it.tracks } else tracks
        return tracks.filter { it.kind == TrackKind.AUDIO }.mapNotNull { it.language }.distinct()
    }
    /** Paged browse grid. kind: "movie" | "series" | "mylist" | null (all). */
    suspend fun browse(
        device: DeviceData,
        kind: String?,
        genres: List<String> = emptyList(),
        studios: List<String> = emptyList(),
        networks: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        sort: String? = null,
        page: Int = 1,
        // R118: null = return the whole filtered set (the Ravilo browse grid wants the full catalog;
        // the lazy grid only renders visible cells). A non-null pageSize keeps paging available.
        pageSize: Int? = null,
    ): SearchResults = coroutineScope {
        val jellyfinBase  = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val tokenDeferred = async { jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken) }
        // liveItems() doesn't need the token; start it immediately in parallel.
        val allDeferred   = async { mediaStore.liveItems(device) }

        val mediaKind = when (kind) {
            "movie"    -> MediaKind.MOVIE
            "series"   -> MediaKind.TV_SHOW
            // R267 (FR-R267-5c) — music videos are a real library type (phase 172) and the phone's
            // Library dropdown offers them. Without this they fell into the `else` and browsed
            // everything.
            MUSIC_KIND -> MediaKind.MUSIC_VIDEO
            else       -> null
        }

        val token = tokenDeferred.await()
        val all = if (kind == "mylist") {
            // While we waited for the token, allItems may have already finished. Kick off favorites
            // and await both — they overlap for whatever time remains.
            val favDeferred = async { jellyfinClient.getFavoriteItemIds(configStore.current.apiKeys.jellyfinUrl, token, device.jellyfinUserId) }
            val items       = allDeferred.await()
            val favoriteIds = favDeferred.await()
            items.filter { it.jellyfinId != null && favoriteIds.contains(it.jellyfinId) }
        } else {
            allDeferred.await()
        }

        // Phase 216 (FR-216-9) — the four taxonomy predicates compare under TaxonomyKey, the same
        // resolver facets() counts with, so a tile's count and its grid cannot disagree over a spelling.
        // Phase 271 (FR-271-4) — genres by identity: an installed app sends a genre NAME, in whatever
        // language its facet list or detail page showed it, and it resolves to the id here.
        val wantedGenres = genres.mapTo(HashSet()) { GenreCatalog.keyOf(it) }
        val filtered = all
            .let { items -> if (mediaKind != null) items.filter { it.kind == mediaKind } else items }
            .let { items -> if (wantedGenres.isNotEmpty()) items.filter { i -> GenreCatalog.keys(i).any { it in wantedGenres } } else items }
            .let { items -> if (studios.isNotEmpty())  items.filter { i -> studios.any  { s -> TaxonomyKey.matches(i.studio, s) || i.secondaryStudios.any { TaxonomyKey.matches(it, s) } } } else items }
            .let { items -> if (networks.isNotEmpty()) items.filter { i -> networks.any { n -> TaxonomyKey.matches(i.network, n) } } else items }
            .let { items -> if (tags.isNotEmpty())     items.filter { i -> tags.any     { t -> i.tags.any    { TaxonomyKey.matches(it, t) } } } else items }

        val sorted = when (sort) {
            "title" -> filtered.sortedBy { it.title.lowercase() }
            "year"  -> filtered.sortedByDescending { it.year ?: 0 }
            else    -> filtered.sortedByDescending { it.recencyKey() }
        }

        val lang = raviloConfigService.getConfig(device.jellyfinUserId).uiLanguage
        val cards = (if (pageSize == null) sorted
                     else sorted.drop((page - 1) * pageSize).take(pageSize))
            .map { it.toMediaCard(lang) }
            .distinctBy { it.id }

        // Phase 205 (FR-205-1) — PlaystateCache read, no Jellyfin call (see browseByQuery's doc above).
        val ps = PlaystateCache.get(device.jellyfinUserId)
        SearchResults(query = kind ?: "all", items = cards.map { it.withPlaystate(ps) }, total = sorted.size)
    }

    /** Multi-language search: matches title, originalTitle, and every titlesByLang value. */
    suspend fun search(device: DeviceData, query: String): SearchResults {
        val all = mediaStore.liveItems(device)
        val lang = raviloConfigService.getConfig(device.jellyfinUserId).uiLanguage

        // Bug fix: a 1-char query used to trigger the same full-library contains-scan as any other
        // query, with no min-length guard — fall back to the same suggestions path as a blank query.
        val cards = if (query.isBlank() || query.length < MIN_SEARCH_LEN) {
            all.sortedByDescending { it.recencyKey() }
                .take(SEARCH_SUGGESTION_LIMIT)
                .map { it.toMediaCard(lang) }
                .distinctBy { it.id }
        } else {
            val q = query.lowercase()
            all.asSequence().filter { item ->
                item.title.lowercase().contains(q) ||
                item.originalTitle?.lowercase()?.contains(q) == true ||
                item.titlesByLang.values.any { it.lowercase().contains(q) }
            }.sortedByDescending { it.recencyKey() }
                .take(100)
                .map { it.toMediaCard(lang) }
                .distinctBy { it.id }
                .toList()
        }

        // Phase 205 (FR-205-1) — PlaystateCache read, no Jellyfin call (see browseByQuery's doc above).
        val ps = PlaystateCache.get(device.jellyfinUserId)
        return SearchResults(query = query, items = cards.map { it.withPlaystate(ps) })
    }

    /**
     * Available filter values + counts for browse filter chips — and, since Phase 216, the Discover
     * wall's index (R243). Phase 142: scoped to [device]'s allowed libraries — a restricted user's
     * chips (and counts) never leak a blocked title.
     *
     * Phase 216: cached per user (see [facetsCache]), normalised through [TaxonomyKey] (FR-216-9),
     * `logoUrl` only where [LogoDownloader.hasLogo] is true (FR-216-5), zero-count values never ship
     * (FR-216-6 — a value only exists here because a visible title named it), and the summary fields
     * ([BrowseFacets.library]/[BrowseFacets.titles]/[BrowseFacets.scoped]) are accumulated in the same
     * single pass as the four count maps, never as a second traversal (FR-216-11).
     *
     * FR-216-4, settled in one direction: **networks are counted for series only**, adopting the admin
     * Metadata page's rule (`MetadataRoutes` `/metadata/networks` filters to `TV_SHOW`), so the viewer
     * wall and the admin page state the same number for the same network. The scanner only ever writes
     * `network` on the series path, so this excludes nothing real — it just makes a hand-edited film
     * carrying a broadcaster the metadata problem it is rather than a wall entry. Studios stay
     * kind-neutral, exactly as the admin page counts them.
     */
    suspend fun facets(device: DeviceData, kind: String?): BrowseFacets {
        val userId = device.jellyfinUserId
        val feedVer = mediaStore.feedVersion
        val allowedHash = (device.allowedLibraries.hashCode() * 31 + device.allowedTags.hashCode()) * 31 + device.blockedTags.hashCode()
        val now = nowMs()
        val entry = facetsCache[userId]?.takeIf {
            it.feedVer == feedVer && it.allowedHash == allowedHash && (now - it.builtAt) < FACETS_TTL_MS
        } ?: buildFacets(device).also { facetsCache[userId] = FacetsEntry(it.all, it.movie, it.series, it.music, now, feedVer, allowedHash) }
        val slice = when (kind) {
            "movie"       -> entry.movie
            "series"      -> entry.series
            MUSIC_KIND    -> entry.music
            else          -> entry.all
        }
        // Phase 271 (FR-271-5) — genre labels for this viewer's app language, applied AFTER the cache
        // read so the cache is never keyed by language.
        return slice.withGenreLabels(raviloConfigService.getConfig(userId).uiLanguage)
    }

    private fun BrowseFacets.withGenreLabels(lang: String?): BrowseFacets = copy(
        genres = genres.map { f -> f.id?.let { id -> GenreCatalog.label(id, lang) }?.let { f.copy(name = it) } ?: f }
            .sortedWith(compareByDescending<FacetItem> { it.count }.thenBy { it.name.lowercase() }),
    )

    /** Phase 271 (FR-271-4) — genres counted by id (one tile per genre, 24 not 41), a hand-added genre by
     *  its name under [TaxonomyKey] as before. A title counts once per genre, however many of its names
     *  say it. The cached name is the English label; [withGenreLabels] relabels per viewer. */
    private class GenreCounter {
        private val byId = LinkedHashMap<Int, Int>()
        private val named = TaxonomyKey.Counter()
        fun add(item: MediaItem) {
            for (r in GenreCatalog.refs(item)) {
                if (r.id != null) byId[r.id] = (byId[r.id] ?: 0) + 1 else named.add(r.name)
            }
        }
        fun items(): List<FacetItem> =
            (byId.map { (id, n) -> FacetItem(GenreCatalog.label(id, GenreCatalog.ENGLISH) ?: "#$id", n, id = id) } +
                named.entries().map { FacetItem(it.name, it.count) })
                .sortedWith(compareByDescending<FacetItem> { it.count }.thenBy { it.name.lowercase() })
    }

    /** One accumulator per kind slice; [add] is called once per item for the slice(s) it belongs to. */
    private class FacetsAcc {
        val genres = GenreCounter(); val studios = TaxonomyKey.Counter()
        val networks = TaxonomyKey.Counter(); val tags = TaxonomyKey.Counter()
        var library = 0
        var genreTitles = 0; var studioTitles = 0; var networkTitles = 0; var tagTitles = 0

        fun add(item: MediaItem) {
            library++
            if (item.genres.any { it.isNotBlank() }) genreTitles++
            genres.add(item)
            val studioValues = (listOfNotNull(item.studio) + item.secondaryStudios).filter { it.isNotBlank() }
            if (studioValues.isNotEmpty()) studioTitles++
            // distinct under the key, so a film credited to `HBO` and `hbo` counts once for HBO
            studioValues.distinctBy { TaxonomyKey.key(it) }.forEach { studios.add(it) }
            if (item.kind == MediaKind.TV_SHOW && !item.network.isNullOrBlank()) { networkTitles++; networks.add(item.network) }
            if (item.tags.any { it.isNotBlank() }) tagTitles++
            item.tags.distinctBy { TaxonomyKey.key(it) }.forEach { tags.add(it) }
        }

        fun toFacets(scoped: Boolean, logoUrl: (String, String) -> String?, logoInk: (String, String) -> String?): BrowseFacets = BrowseFacets(
            genres   = genres.items(),
            studios  = studios.entries().map { FacetItem(it.name, it.count, logoUrl("studios", it.name), logoInk("studios", it.name)) },
            networks = networks.entries().map { FacetItem(it.name, it.count, logoUrl("networks", it.name), logoInk("networks", it.name)) },
            tags     = tags.entries().map { FacetItem(it.name, it.count) },
            library  = library,
            titles   = mapOf("studios" to studioTitles, "networks" to networkTitles, "genres" to genreTitles, "tags" to tagTitles),
            scoped   = scoped,
        )
    }

    private suspend fun buildFacets(device: DeviceData): FacetsEntry {
        val visible = mediaStore.liveItems(device)
        val all = FacetsAcc(); val movie = FacetsAcc(); val series = FacetsAcc(); val music = FacetsAcc()
        for (item in visible) {
            all.add(item)
            when (item.kind) {
                MediaKind.MOVIE       -> movie.add(item)
                MediaKind.TV_SHOW     -> series.add(item)
                MediaKind.MUSIC_VIDEO -> music.add(item)
                else -> {}
            }
        }
        // FR-216-2 — "scoped" is exactly what visibleTo(device) is: a library allow-list or a Jellyfin
        // tag policy. There is no age filter in the server-side catalog and none is claimed here.
        val scoped = device.allowedLibraries != null || device.allowedTags.isNotEmpty() || device.blockedTags.isNotEmpty()
        // FR-216-5 — the key is ABSENT unless a logo file is really on disk; genres/tags never get one.
        val logoUrl: (String, String) -> String? = { k, name ->
            if (logoDownloader?.hasLogo(k, name) == true) RaviloImageUrl.taxonomyLogo(k, name) else null
        }
        // Phase 232 (FR-232-3) — a sidecar READ only; judging happens in the background (LogoDownloader).
        val logoInk: (String, String) -> String? = { k, name -> if (logoDownloader?.hasLogo(k, name) == true) logoDownloader.logoInk(k, name) else null }
        // R267 (FR-R267-5c) — every kind's count, in ONE response. The phone's Library dropdown shows
        // four counts at once, and `facets(kind)` answers for one slice per call: four round trips
        // would be four chances to disagree, and the client may not sum them itself
        // (render-never-compute). The accumulators are all built here anyway, so this is free.
        val counts = mapOf(
            ALL_KIND to all.library,
            "movie" to movie.library,
            "series" to series.library,
            MUSIC_KIND to music.library,
        )
        fun FacetsAcc.facets() = toFacets(scoped, logoUrl, logoInk).copy(kindCounts = counts)
        return FacetsEntry(all.facets(), movie.facets(), series.facets(), music.facets(), 0L, 0L, 0)
    }

    private fun MediaItem.toMediaCard(lang: String?): MediaCard {
        val jId = jellyfinId
        val sonarrEnabled = configStore.current.sonarr?.enabled == true
        return MediaCard(
            id = jId ?: id,
            kind = when (kind) {
                MediaKind.TV_SHOW -> dev.jellystructure.shared.tv.MediaKind.SERIES
                MediaKind.MUSIC_VIDEO -> dev.jellystructure.shared.tv.MediaKind.MUSIC_VIDEO
                MediaKind.MOVIE -> dev.jellystructure.shared.tv.MediaKind.MOVIE
            },
            title = title,
            year = year,
            genre = GenreCatalog.displayNames(this, lang, withTitle = false).firstOrNull(),  // Phase 271
            rating = CertificationResolver.resolve(configStore.current.metadata.ageRatingCascade, certifications)?.code,
            ageRating = CertificationResolver.normalizedAge(configStore.current.metadata.ageRatingCascade, configStore.current.metadata.ageRatingMap, certifications),
            posterUrl = RaviloImageUrl.poster(id, artwork.assetVersion(this, "poster")),     // R133/R214
            backdropUrl = RaviloImageUrl.backdrop(id, artwork.assetVersion(this, "backdrop")),
            upcomingEpisode = if (sonarrEnabled && kind == MediaKind.TV_SHOW &&
                sonarrStatus != "ended" && sonarrNextAiringDate != null &&
                sonarrNextAiringSeason != null && sonarrNextAiringEpisode != null)
                "S${sonarrNextAiringSeason.toString().padStart(2,'0')}E${sonarrNextAiringEpisode.toString().padStart(2,'0')}" else null,
        )
    }
}
