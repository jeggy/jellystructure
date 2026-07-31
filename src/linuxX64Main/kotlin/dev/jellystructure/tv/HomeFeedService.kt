package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.recencyKey
import dev.jellystructure.resolver.CertificationResolver
import dev.jellystructure.shared.tv.Channel
import dev.jellystructure.shared.tv.ChannelConfig
import dev.jellystructure.shared.tv.Hero
import dev.jellystructure.shared.tv.HeroConfig
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.Row
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.effectiveQuery
import dev.jellystructure.shared.tv.isLive
import dev.jellystructure.shared.tv.CardPlayState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

private const val ROW_ITEM_LIMIT = 30
private const val HERO_AUTO_COUNT = 5
private const val FEED_TTL_MS = 5 * 60_000L  // Continue row freshness window
private const val CONTINUE_TIMEOUT_MS = 6_000L  // R102: cap the live Jellyfin resume/next-up wait
private const val WATCHED_TIMEOUT_MS = 2_500L  // R142: cap the played-state overlay so it never hangs the feed
// Bug fix: shorter than FEED_TTL_MS on purpose — watched/in-progress state changes far more often than
// the structural feed (rows/heroes/channels), so it needs its own, tighter freshness window.
private const val PLAYSTATE_TTL_MS = 20_000L

class HomeFeedService(
    private val mediaStore: MediaStore,
    private val configService: RaviloConfigService,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
    private val tvEventBus: TvEventBus,
) {
    private val json = Json { encodeDefaults = true }

    // Phase R86-A: stale-while-revalidate home feed cache per Jellyfin user.
    // Key = jellyfinUserId; invalidated on library write (libraryVersion), config change (cfgHash),
    // a Phase 142 (+ tag follow-up) policy change (allowedHash), or TTL (Continue stays fresh within FEED_TTL_MS).
    private data class FeedEntry(val feed: HomeFeed, val builtAt: Long, val libVer: Long, val cfgHash: Int, val allowedHash: Int)
    private val feedCache = HashMap<String, FeedEntry>()

    // Bug fix: per-user cache for the WHOLE catalog's playstate (not just one feed's rows) — a cache hit
    // costs zero Jellyfin calls regardless of which rows end up in the feed, and it's what lets the
    // structural-feed build and the playstate fetch run concurrently below (neither needs the other's
    // output — the old code fetched playstate AFTER building the feed, purely because it read the
    // feed's own row ids as its candidate list; fetching for the whole catalog removes that dependency).
    private data class PlaystateEntry(val data: Map<String, CardPlayState>, val builtAt: Long)
    private val playstateCache = HashMap<String, PlaystateEntry>()

    suspend fun getHomeFeed(device: DeviceData): HomeFeed = coroutineScope {
        val userId = device.jellyfinUserId
        val libVer = mediaStore.libraryVersion
        val config = configService.getConfig(userId)
        val cfgHash = config.hashCode()
        val allowedHash = (device.allowedLibraries.hashCode() * 31 + device.allowedTags.hashCode()) * 31 + device.blockedTags.hashCode()
        val now = nowMs()

        val cachedStructural = feedCache[userId]?.takeIf {
            it.libVer == libVer && it.cfgHash == cfgHash && it.allowedHash == allowedHash && (now - it.builtAt) < FEED_TTL_MS
        }

        // Bug fix: these two used to run sequentially (build the whole feed — including its own live
        // Continue-row Jellyfin calls — THEN fetch playstate afterward), stacking their worst-case
        // latencies. Neither depends on the other's result (see PlaystateEntry doc above), so run them
        // concurrently; a cache hit on either side just returns immediately without touching Jellyfin.
        val feedDeferred = async {
            cachedStructural?.feed ?: buildHomeFeed(device, config).also {
                feedCache[userId] = FeedEntry(it, now, libVer, cfgHash, allowedHash)
            }
        }
        val playstateDeferred = async { playstateFor(device, now) }
        applyPlaystate(feedDeferred.await(), playstateDeferred.await())
    }

    /**
     * Bug fix: a playback stop (and the played write-through) used to only forward to Jellyfin — nothing
     * touched [feedCache] (FEED_TTL_MS = 5 min) or [playstateCache] (PLAYSTATE_TTL_MS = 20s). The Continue
     * row is built INSIDE the cached feed from Jellyfin's Resume + NextUp, so even a perfectly correct
     * stop stayed invisible on Home for up to five minutes: the row still showed the episode at its old
     * position, or still showed one the viewer had just finished. Drop both caches for this user so the
     * next load rebuilds the row, and re-read the playstate right away so the R176 `playstate_changed`
     * push patches any Home/Browse screen that is already open (on this device or another of the
     * viewer's). Best-effort: a failure here must never turn a successful stop into an error response.
     */
    suspend fun invalidatePlaystate(device: DeviceData) {
        val userId = device.jellyfinUserId
        feedCache.remove(userId)
        playstateCache.remove(userId)
        runCatching { playstateFor(device, nowMs()) }
    }

    /**
     * Returns this user's cached whole-catalog playstate if still fresh; otherwise fetches it live,
     * caches it, and — since a fresh fetch is the whole point of the exercise — broadcasts it to every
     * OTHER device signed in as this user (see [TvEventBus.notifyPlaystateChanged]) so an already-open
     * Home/Browse/Search screen elsewhere patches its tiles instantly instead of waiting for its own
     * next load to independently pay the same live round trip.
     */
    private suspend fun playstateFor(device: DeviceData, now: Long): Map<String, CardPlayState> {
        val userId = device.jellyfinUserId
        playstateCache[userId]?.takeIf { (now - it.builtAt) < PLAYSTATE_TTL_MS }?.let { return it.data }
        val ps = fetchAllPlaystate(device)
        playstateCache[userId] = PlaystateEntry(ps, now)
        if (ps.isNotEmpty()) tvEventBus.notifyPlaystateChanged(userId, json.encodeToString(ps))
        return ps
    }

    /**
     * Fetch played/in-progress state for this user's ENTIRE visible catalog, not just whatever ends up
     * in one particular feed's rows — the ids come straight from [MediaStore]'s own cache, so this has
     * no data dependency on [buildHomeFeed] and can run concurrently with it. Bounded by
     * [WATCHED_TIMEOUT_MS] — on a slow Jellyfin the feed ships without fresh watched-state, same as before.
     */
    private suspend fun fetchAllPlaystate(device: DeviceData): Map<String, CardPlayState> {
        val ids = mediaStore.liveItems(device).mapNotNull { it.jellyfinId }
        if (ids.isEmpty()) return emptyMap()
        val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        return withTimeoutOrNull(WATCHED_TIMEOUT_MS) {
            val token = jellyfinClient.tvToken(base, device, configStore.current.apiKeys.jellyfinToken)
            fetchPlaystate(jellyfinClient, base, token, device.jellyfinUserId, ids)
        } ?: emptyMap()
    }

    /**
     * R142 — overlay each tile's Jellyfin played / in-progress state. Continue Watching rows keep their
     * episode-level progress (carried in the card already); all other rows get a ✓ when played or a resume
     * sliver when in-progress.
     */
    private fun applyPlaystate(feed: HomeFeed, ps: Map<String, CardPlayState>): HomeFeed {
        if (ps.isEmpty()) return feed
        return feed.copy(rows = feed.rows.map { row ->
            if (row.kind == RowKind.CONTINUE) row else row.copy(items = row.items.map { it.withPlaystate(ps) })
        })
    }

    private suspend fun buildHomeFeed(device: DeviceData, config: RaviloConfig): HomeFeed = coroutineScope {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val allDeferred   = async { mediaStore.liveItems(device) }
        // R85: token no longer needed for image URLs; still needed for buildContinueRow.
        val tokenDeferred = async { jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken) }
        val all   = allDeferred.await()
        val token = tokenDeferred.await()
        HomeFeed(
            heroes = buildHeroes(config, all),
            channels = buildChannels(config),
            rows = buildRows(config, device, all, all, jellyfinBase, token, channelFilter = null),
            heroHeightPct = config.heroHeightPct,
            autoAdvanceSeconds = config.autoAdvanceSeconds,
            tileShape = config.tileShape,
            portraitHeroHeightPct = config.portrait?.heroHeightPct,
            liveTvHome = config.liveTvHome,
        )
    }

    suspend fun getChannelFeed(device: DeviceData, channelId: String): HomeFeed = coroutineScope {
        val config     = configService.getConfig(device.jellyfinUserId)
        val channelCfg = config.channels.find { it.id == channelId }
            ?: return@coroutineScope HomeFeed(emptyList(), emptyList(), emptyList())
        val jellyfinBase  = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val allDeferred   = async { mediaStore.liveItems(device) }
        // R85: token no longer needed for image URLs; still needed for buildContinueRow.
        val tokenDeferred = async { jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken) }
        val playstateDeferred = async { playstateFor(device, nowMs()) }
        val allItems = allDeferred.await()
        val token    = tokenDeferred.await()
        val heroIds  = config.heroes.map { it.itemId }.toSet()
        val filtered = allItems.filter { it.matchesChannel(channelCfg, heroIds) }
        val pageHero = channelCfg.pageHero
        val heroes   = if (pageHero?.enabled == true && pageHero.items.isNotEmpty())
            buildHeroesFromList(pageHero.items, allItems)
        else
            emptyList()
        applyPlaystate(HomeFeed(
            heroes = heroes,
            channels = buildChannels(config),
            rows = buildRows(config, device, filtered, allItems, jellyfinBase, token, channelFilter = channelCfg),
            heroHeightPct = config.heroHeightPct,
            autoAdvanceSeconds = config.autoAdvanceSeconds,
            tileShape = config.tileShape,
            portraitHeroHeightPct = config.portrait?.heroHeightPct,
        ), playstateDeferred.await())
    }

    // ─── Heroes ───────────────────────────────────────────────────────────────

    private fun buildHeroes(config: RaviloConfig, all: List<MediaItem>): List<Hero> {
        // Auto mode: pick the most-recently-scanned items with no dressing.
        if (config.heroes.isEmpty()) {
            return all.sortedWith(compareByDescending<MediaItem> { it.recencyKey() }.thenBy { it.title }).take(HERO_AUTO_COUNT).mapNotNull { item ->
                item.jellyfinId ?: return@mapNotNull null   // hero must be navigable (detail opens by jellyfinId)
                Hero(
                    item = item.toMediaCard(),
                    taglineKicker = null,
                    backdropUrl = RaviloImageUrl.heroBackdrop(item.id),
                    logoUrl = RaviloImageUrl.logo(item.id),
                    badge = null,
                    synopsis = item.overview,
                )
            }
        }
        // Curated mode: keep the HeroConfig alongside the resolved MediaItem so badge/tagline survive.
        return config.heroes
            .filter { it.enabled }
            .sortedBy { it.order }
            .mapNotNull { hc ->
                val item = all.firstOrNull { it.jellyfinId == hc.itemId } ?: all.firstOrNull { it.id == hc.itemId }
                    ?: return@mapNotNull null
                item.jellyfinId ?: return@mapNotNull null   // hero must be navigable (detail opens by jellyfinId)
                Hero(
                    item = item.toMediaCard(),
                    taglineKicker = hc.tagline,
                    backdropUrl = RaviloImageUrl.heroBackdrop(item.id),
                    logoUrl = if (hc.clearlogoOverlay) RaviloImageUrl.logo(item.id) else null,
                    badge = hc.badge,
                    synopsis = item.overview,
                )
            }
    }

    private fun buildHeroesFromList(heroConfigs: List<HeroConfig>, all: List<MediaItem>): List<Hero> =
        heroConfigs
            .filter { it.enabled }
            .sortedBy { it.order }
            .mapNotNull { hc ->
                val item = all.firstOrNull { it.jellyfinId == hc.itemId } ?: all.firstOrNull { it.id == hc.itemId }
                    ?: return@mapNotNull null
                item.jellyfinId ?: return@mapNotNull null   // hero must be navigable (detail opens by jellyfinId)
                Hero(
                    item = item.toMediaCard(),
                    taglineKicker = hc.tagline,
                    backdropUrl = RaviloImageUrl.heroBackdrop(item.id),
                    logoUrl = if (hc.clearlogoOverlay) RaviloImageUrl.logo(item.id) else null,
                    badge = hc.badge,
                    synopsis = item.overview,
                )
            }

    // ─── Channels ─────────────────────────────────────────────────────────────

    private fun buildChannels(config: RaviloConfig): List<Channel> =
        config.channels
            .filter { it.enabled }
            .sortedBy { it.order }
            .map { ch ->
                Channel(
                    id = ch.id,
                    name = ch.name,
                    logoUrl = ch.logoUrl,
                    style = ch.style,
                    brandColor = ch.brandColor,
                    paddingLogo = ch.paddingLogo,
                    paddingText = ch.paddingText,
                )
            }

    // ─── Rows ─────────────────────────────────────────────────────────────────

    /**
     * R143: [all] is the context list (the channel-scoped list inside a channel, the full library on Home);
     * [libraryAll] is always the full unscoped library — used for system rows whose scope is "all".
     */
    private suspend fun buildRows(
        config: RaviloConfig,
        device: DeviceData,
        all: List<MediaItem>,
        libraryAll: List<MediaItem>,
        jellyfinBase: String,
        token: String,
        channelFilter: ChannelConfig?,
    ): List<Row> {
        val channelRows = channelFilter?.rows
        val heroIds = config.heroes.map { it.itemId }.toSet()  // Phase R86-C: hoist out of CUSTOM-row loop
        val result = mutableListOf<Row>()

        // ── R143: custom channel — emit the configured system rows (Continue, Newly Added) ABOVE the
        // filter rows, each honouring its show / scope ("all" library-wide vs "channel" channel-scoped) /
        // merge setting. Continue is resolved against the chosen source so scope="channel" yields only the
        // viewer's in-progress titles that belong to this channel.
        if (channelRows?.mode == "custom") {
            val sys = channelRows.system
            if (sys.cont.show) {
                val src = if (sys.cont.scope == "channel") all else libraryAll
                val cards = buildContinueRow(device, src, jellyfinBase, token)
                if (cards.isNotEmpty()) result.add(Row("continue", "Continue Watching", RowKind.CONTINUE, cards))
            }
            if (sys.newly.show) {
                val src = if (sys.newly.scope == "channel") all else libraryAll
                addNewlyAddedRows(result, src, merge = sys.newly.merge)
            }
            for (rowCfg in channelRows.items.filter { it.enabled }.sortedBy { it.order }) {
                buildFilterRow(rowCfg, all, heroIds)?.let { result.add(it) }
            }
            return result
        }

        // ── Home / inherit-mode channel — unchanged behaviour (config.rows drives system + filter rows).
        val enabledRows = config.rows.filter { it.enabled }.sortedBy { it.order }
        val mergeNewly = config.mergeNewlyAdded
        for (rowCfg in enabledRows) {
            when (rowCfg.kind) {
                RowKind.CONTINUE -> {
                    if (channelFilter != null) continue // inherit-mode channels keep R59 behaviour (no Continue)
                    val cards = buildContinueRow(device, all, jellyfinBase, token)
                    if (cards.isNotEmpty()) result.add(Row(rowCfg.id, rowCfg.title ?: "Continue Watching", RowKind.CONTINUE, cards))
                }

                RowKind.NEWLY_ADDED -> {
                    if (mergeNewly) continue  // merged → single row injected below
                    // mediaKind=null (the "newly-all" system row) → split into Movies then Series (R71).
                    if (rowCfg.mediaKind == null) {
                        addNewlyAddedRows(result, all, merge = false, idPrefix = rowCfg.id, titlePrefix = rowCfg.title)
                        continue
                    }
                    val filtered = when (rowCfg.mediaKind) {
                        "MOVIE"  -> all.filter { it.kind == MediaKind.MOVIE }
                        "SERIES" -> all.filter { it.kind == MediaKind.TV_SHOW }
                        else     -> all
                    }
                    val cards = filtered
                        .sortedWith(compareByDescending<MediaItem> { it.recencyKey() }.thenBy { it.title })
                        .take(ROW_ITEM_LIMIT)
                        .mapNotNull { it.toMediaCardOrNull() }
                        .distinctBy { it.id }
                    if (cards.isNotEmpty()) result.add(Row(rowCfg.id, rowCfg.title ?: "Newly Added", RowKind.NEWLY_ADDED, cards))
                }

                RowKind.GENRE, RowKind.CUSTOM -> buildFilterRow(rowCfg, all, heroIds)?.let { result.add(it) }
            }
        }

        // Merge on → inject one merged NEWLY_ADDED row at the first configured NEWLY_ADDED position.
        if (mergeNewly) {
            val mergedCards = all
                .sortedWith(compareByDescending<MediaItem> { it.recencyKey() }.thenBy { it.title })
                .take(ROW_ITEM_LIMIT)
                .mapNotNull { it.toMediaCardOrNull() }
                .distinctBy { it.id }
            if (mergedCards.isNotEmpty()) {
                val firstIdx = enabledRows.indexOfFirst { it.kind == RowKind.NEWLY_ADDED }
                val insertAt = when {
                    firstIdx < 0 -> result.size
                    firstIdx == 0 -> 0
                    else -> {
                        val predecessorId = enabledRows[firstIdx - 1].id
                        val pos = result.indexOfFirst { it.id == predecessorId }
                        if (pos < 0) result.size else pos + 1
                    }
                }
                result.add(insertAt.coerceIn(0, result.size), Row("newly-added", "Newly Added", RowKind.NEWLY_ADDED, mergedCards))
            }
        }

        return result
    }

    /** R143: append the Newly Added section from [src] — one merged row, or split Movies + Series rows. */
    private fun addNewlyAddedRows(
        result: MutableList<Row>,
        src: List<MediaItem>,
        merge: Boolean,
        idPrefix: String = "newly-added",
        titlePrefix: String? = null,
    ) {
        if (merge) {
            val cards = src.sortedWith(compareByDescending<MediaItem> { it.recencyKey() }.thenBy { it.title }).take(ROW_ITEM_LIMIT).mapNotNull { it.toMediaCardOrNull() }.distinctBy { it.id }
            if (cards.isNotEmpty()) result.add(Row(idPrefix, titlePrefix ?: "Newly Added", RowKind.NEWLY_ADDED, cards))
            return
        }
        val movies = src.asSequence().filter { it.kind == MediaKind.MOVIE }
            .sortedWith(compareByDescending<MediaItem> { it.recencyKey() }.thenBy { it.title }).take(ROW_ITEM_LIMIT).mapNotNull { it.toMediaCardOrNull() }.distinctBy { it.id }.toList()
        val series = src.asSequence().filter { it.kind == MediaKind.TV_SHOW }
            .sortedWith(compareByDescending<MediaItem> { it.recencyKey() }.thenBy { it.title }).take(ROW_ITEM_LIMIT).mapNotNull { it.toMediaCardOrNull() }.distinctBy { it.id }.toList()
        if (movies.isNotEmpty()) result.add(Row("$idPrefix-movies", titlePrefix?.let { "$it — Movies" } ?: "Movies — Newly Added", RowKind.NEWLY_ADDED, movies))
        if (series.isNotEmpty()) result.add(Row("$idPrefix-series", titlePrefix?.let { "$it — Series" } ?: "Series — Newly Added", RowKind.NEWLY_ADDED, series))
    }

    /** R143: build one GENRE or CUSTOM filter row from [all] (already channel-scoped in channel context). */
    private fun buildFilterRow(rowCfg: RowConfig, all: List<MediaItem>, heroIds: Set<String>): Row? = when (rowCfg.kind) {
        RowKind.GENRE -> {
            val genreTerms = (rowCfg.title ?: "").split("&", ",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
            val cards = all
                .asSequence()
                .filter { item -> genreTerms.isEmpty() || item.genres.any { g -> genreTerms.any { t -> g.lowercase().contains(t) } } }
                .sortedWith(compareByDescending<MediaItem> { it.recencyKey() }.thenBy { it.title })
                .take(ROW_ITEM_LIMIT)
                .mapNotNull { it.toMediaCardOrNull() }
                .distinctBy { it.id }
                .toList()
            if (cards.isNotEmpty()) Row(rowCfg.id, rowCfg.title ?: "Genre", RowKind.GENRE, cards) else null
        }
        RowKind.CUSTOM -> {
            // Phase 140 — effectiveQuery() reads rowCfg.query when the editor has migrated this row to
            // the blocks tree (and cleared match/conditions on save); falls back to migrating the
            // legacy flat shape on the fly otherwise. Reading match/conditions directly here would
            // silently stop filtering the moment a row is saved as a tree.
            val query = rowCfg.effectiveQuery()
            val matched = all.filter { ConditionEvaluator.matches(it, query, heroIds, configStore.current.metadata.ageRatingCascade) }
            val filtered = when (rowCfg.mediaKind) {
                "MOVIE"  -> matched.filter { it.kind == MediaKind.MOVIE }
                "SERIES" -> matched.filter { it.kind == MediaKind.TV_SHOW }
                else     -> matched
            }
            val cards = filtered
                .sortedWith(compareByDescending<MediaItem> { it.recencyKey() }.thenBy { it.title })
                .take(ROW_ITEM_LIMIT)
                .mapNotNull { it.toMediaCardOrNull() }
                .distinctBy { it.id }
            if (cards.isNotEmpty()) Row(rowCfg.id, rowCfg.title ?: "Custom", RowKind.CUSTOM, cards) else null
        }
        else -> null
    }

    private suspend fun buildContinueRow(
        device: DeviceData,
        all: List<MediaItem>,
        jellyfinBase: String,
        token: String,
    ): List<MediaCard> = coroutineScope {
        val jellyfinUrl = configStore.current.apiKeys.jellyfinUrl.takeIf { it.isNotBlank() }
            ?: return@coroutineScope emptyList()

        // Both calls are independent — fetch in parallel to halve the Jellyfin round-trips.
        // R102: bound the wait so a cold/slow Jellyfin can't hang the whole home response on the 30s
        // HttpTimeout. On timeout the asyncs are cancelled and home ships WITHOUT the Continue row —
        // a missing row is atomic-safe (no reflow), and the R86-A SWR cache refreshes it next load.
        val fetched = withTimeoutOrNull(CONTINUE_TIMEOUT_MS) {
            coroutineScope {
                val resumeDeferred = async { jellyfinClient.getResumeItems(jellyfinUrl, token, device.jellyfinUserId) }
                val nextUpDeferred = async { jellyfinClient.getNextUp(jellyfinUrl, token, device.jellyfinUserId) }
                resumeDeferred.await() to nextUpDeferred.await()
            }
        } ?: return@coroutineScope emptyList()
        val (resumeItems, nextUpItems) = fetched

        val cards = mutableListOf<MediaCard>()
        val seen = mutableSetOf<String>()
        // R186 (FR-RV-CW2-3): resolved via one map instead of a linear scan per entry — the candidate
        // pool is now up to 200 entries (was 20), and this runs on every home/channel load.
        val byJellyfinId = all.asSequence().mapNotNull { mi -> mi.jellyfinId?.let { it to mi } }.toMap()

        for (play in resumeItems) {
            // R185 — Jellyfin's own IsResumable filter is PlaybackPositionTicks > 0 only, with no Played
            // check; the two can disagree (stale/leaked position outliving a played flag — see the spec)
            // regardless of what caused it. Never show an already-watched title as in-progress.
            if (play.userData?.played == true) continue
            val itemId = play.seriesId ?: play.id
            if (!seen.add(itemId)) continue
            val mediaItem = byJellyfinId[itemId] ?: continue
            val pct = play.userData?.playedPercentage?.toFloat()?.div(100f)
            // R113: carry the resumed episode's season/episode for the on-image badge (null for movies).
            cards.add(mediaItem.toMediaCard(progressPct = pct, seasonNumber = play.seasonNumber, episodeNumber = play.episodeNumber))
        }

        for (play in nextUpItems) {
            val itemId = play.seriesId ?: play.id
            if (!seen.add(itemId)) continue
            val mediaItem = byJellyfinId[itemId] ?: continue
            val s = play.seasonNumber; val e = play.episodeNumber
            val label = if (s != null && e != null) "S${s}E${e} · ${play.name}" else play.name
            cards.add(mediaItem.toMediaCard(nextUpLabel = label, seasonNumber = s, episodeNumber = e))
        }

        cards.take(ROW_ITEM_LIMIT)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun MediaItem.toMediaCardOrNull(): MediaCard? {
        jellyfinId ?: return null
        return toMediaCard()
    }

    private fun MediaItem.toMediaCard(
        progressPct: Float? = null,
        nextUpLabel: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        badge: String? = null,
    ): MediaCard {
        val jId = jellyfinId
        val sonarrEnabled = configStore.current.sonarr?.enabled == true
        return MediaCard(
            id = jId ?: id,
            kind = if (kind == MediaKind.TV_SHOW) dev.jellystructure.shared.tv.MediaKind.SERIES
                   else dev.jellystructure.shared.tv.MediaKind.MOVIE,
            title = title,
            year = year,
            genre = genres.firstOrNull(),
            rating = CertificationResolver.resolve(configStore.current.metadata.ageRatingCascade, certifications)?.code,
            ageRating = CertificationResolver.normalizedAge(configStore.current.metadata.ageRatingCascade, configStore.current.metadata.ageRatingMap, certifications),
            posterUrl = RaviloImageUrl.poster(id),     // R133: keyed by MediaItem.id (on-disk artwork)
            backdropUrl = RaviloImageUrl.backdrop(id),
            progressPct = progressPct,
            nextUpLabel = nextUpLabel,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            badge = badge,
            upcomingEpisode = if (sonarrEnabled && kind == MediaKind.TV_SHOW &&
                sonarrStatus != "ended" && sonarrNextAiringDate != null &&
                sonarrNextAiringSeason != null && sonarrNextAiringEpisode != null)
                "S${sonarrNextAiringSeason.toString().padStart(2,'0')}E${sonarrNextAiringEpisode.toString().padStart(2,'0')}" else null,
        )
    }

    private fun MediaItem.matchesChannel(ch: ChannelConfig, heroIds: Set<String>): Boolean {
        // Phase 140: the blocks tree (ch.query, or the legacy flat shape migrated on the fly by
        // effectiveQuery()) supersedes the legacy single typed filters — same fallback chain as
        // before, just query-aware so a channel doesn't silently stop filtering the moment it's
        // saved as a tree (which clears match/conditions).
        val query = ch.effectiveQuery()
        if (query.isLive()) return ConditionEvaluator.matches(this, query, heroIds, configStore.current.metadata.ageRatingCascade)
        if (ch.filterNetwork != null && network.equals(ch.filterNetwork, ignoreCase = true)) return true
        if (ch.filterStudio  != null && studio.equals(ch.filterStudio,  ignoreCase = true)) return true
        if (ch.filterGenre   != null && genres.any { it.equals(ch.filterGenre, ignoreCase = true) }) return true
        if (ch.filterTag     != null && tags.any { it.equals(ch.filterTag, ignoreCase = true) }) return true
        return false
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
